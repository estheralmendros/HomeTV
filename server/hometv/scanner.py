"""Library scanner (PRD §4): walk -> diff -> parse -> probe -> link -> purge -> sidecars.

Read-only on media: only ``scandir``/``stat`` (through ``FsOps``, injectable for tests) and the
injectable ``probe_fn``. Every DB access runs in ``asyncio.to_thread``; writes go through
``db.write_session()`` in short batches (<= ``BATCH_SIZE`` files). No transaction is open while
walking or probing.

Removal is two-stage: files not seen under a *fully walked* root (and not under a protected
prefix) get ``missing_since``; they are purged after ``scan.missing_grace_days``.

Phase 4 hook: ``keyframe_hook(media_file_ids)`` is called at the end of the probe phase with the
present files that need a background keyframe index (§4 step 5a), new/changed files first.
"""

import asyncio
import contextlib
import errno as errno_mod
import functools
import logging
import os
import shutil
import stat as stat_mod
import unicodedata
from collections import defaultdict
from collections.abc import Awaitable, Callable, Iterable, Sequence
from dataclasses import dataclass, field
from datetime import timedelta
from pathlib import Path, PurePosixPath
from typing import Any, Literal

from sqlalchemy import delete, select, update
from sqlalchemy.orm import Session

from hometv.config import Settings
from hometv.db import models as m
from hometv.db.engine import Database
from hometv.parser import (
    SIDECAR_EXTENSIONS,
    VIDEO_EXTENSIONS,
    Ignored,
    MultiPart,
    ParsedEpisode,
    ParsedFilm,
    ParseResult,
    Unparseable,
    parse,
    parse_sidecar,
)
from hometv.probe import ProbeError, ProbeResult, probe
from hometv.scan_report import ScanReport
from hometv.text import normalize_title, search_text, sort_title

logger = logging.getLogger("hometv.scanner")

BATCH_SIZE = 100
PARSE_REASONS = frozenset({"no_title", "no_season", "no_episode_number", "multi_part"})
KEYFRAME_CONTAINERS = ("ts", "avi", "mpeg", "asf", "mkv", "webm")
_BROKEN_LINK_ERRNOS = {errno_mod.ENOENT, errno_mod.ENOTDIR, errno_mod.ELOOP}

LibraryType = Literal["films", "shows"]
ProbeFn = Callable[[str], Awaitable[ProbeResult]]
KeyframeHook = Callable[[list[int]], None]


@dataclass(frozen=True)
class FsOps:
    """Filesystem primitives used by the walk (override in tests to inject errors)."""

    scandir: Callable[..., Any] = os.scandir
    stat: Callable[..., os.stat_result] = os.stat


@dataclass
class _Video:
    path: str
    root: str
    library_type: LibraryType
    rel: str
    dir: str
    size: int
    mtime_ns: int


@dataclass
class _Sub:
    path: str
    rel: str
    size: int
    mtime_ns: int


@dataclass
class _RootWalk:
    root: str
    library_type: LibraryType
    videos: list[_Video] = field(default_factory=list)
    subs: dict[str, list[_Sub]] = field(default_factory=lambda: defaultdict(list))
    stems: dict[str, set[str]] = field(default_factory=lambda: defaultdict(set))
    walked_dirs: list[str] = field(default_factory=list)
    protected_dirs: list[str] = field(default_factory=list)
    protected_files: set[str] = field(default_factory=set)
    fully_walked: bool = True

    def is_protected(self, path: str) -> bool:
        return path in self.protected_files or any(path.startswith(p) for p in self.protected_dirs)


@dataclass
class _Row:
    id: int
    path: str
    size: int
    mtime_ns: int
    missing: bool
    probe_error: bool
    root_path: str


@dataclass
class _Work:
    video: _Video
    row_id: int | None
    reparse: bool  # new or changed: parse + link
    changed: bool  # existing row whose size/mtime changed
    restore: bool
    needs_probe: bool
    parsed: ParseResult | None = None


@dataclass
class _ProbeOutcome:
    result: ProbeResult | None = None
    error: str | None = None


def _errno_name(exc: OSError) -> str:
    return errno_mod.errorcode.get(exc.errno or 0, str(exc.errno))


def _undecodable(name: str) -> bool:
    try:
        name.encode("utf-8")
    except UnicodeEncodeError:
        return True
    return False


def _within(path: str, root: str) -> bool:
    return path == root or path.startswith(root.rstrip(os.sep) + os.sep)


def serialize_parse(result: ParseResult) -> dict[str, Any] | None:
    """``media_file.parsed`` shape (= ``UnmatchedEntry.parsed``, REST API Reference)."""
    if isinstance(result, ParsedEpisode):
        return {
            "kind": "episode",
            "show_title": result.show_title,
            "show_year": result.show_year,
            "season": result.season,
            "episodes": sorted(set(result.episodes)),
        }
    if isinstance(result, ParsedFilm):
        return {"kind": "film", "title": result.title, "year": result.year, "part": None}
    if isinstance(result, MultiPart):
        return {"kind": "film", "title": result.title, "year": result.year, "part": result.part}
    return None


def _chunks[T](items: Sequence[T], size: int) -> Iterable[Sequence[T]]:
    for start in range(0, len(items), size):
        yield items[start : start + size]


# --- Item lookup / creation (shared with metadata.enrich) ---------------------------------------


def _year_clause(column: Any, year: int | None) -> Any:
    return column.is_(None) if year is None else column == year


def find_or_create_film(session: Session, library_id: int, title: str, year: int | None) -> int:
    norm = normalize_title(title)
    alias_film = session.scalar(
        select(m.ItemAlias.film_id).where(
            m.ItemAlias.library_id == library_id,
            m.ItemAlias.parsed_title_norm == norm,
            _year_clause(m.ItemAlias.parsed_year, year),
            m.ItemAlias.film_id.is_not(None),
        )
    )
    if alias_film is not None:
        return alias_film
    film_id = session.scalar(
        select(m.Film.id)
        .where(
            m.Film.library_id == library_id,
            m.Film.parsed_title_norm == norm,
            _year_clause(m.Film.parsed_year, year),
        )
        .order_by(m.Film.id)
        .limit(1)
    )
    if film_id is not None:
        return film_id
    film = m.Film(
        library_id=library_id,
        match_status="pending",
        parsed_title=title,
        parsed_title_norm=norm,
        parsed_year=year,
        title=title,
        year=year,
        sort_title=sort_title(title),
        search_text=search_text(title, None),
    )
    session.add(film)
    session.flush()
    return film.id


def find_or_create_show(session: Session, library_id: int, title: str, year: int | None) -> int:
    norm = normalize_title(title)
    alias_show = session.scalar(
        select(m.ItemAlias.show_id).where(
            m.ItemAlias.library_id == library_id,
            m.ItemAlias.parsed_title_norm == norm,
            _year_clause(m.ItemAlias.parsed_year, year),
            m.ItemAlias.show_id.is_not(None),
        )
    )
    if alias_show is not None:
        return alias_show
    show_id = session.scalar(
        select(m.Show.id)
        .where(
            m.Show.library_id == library_id,
            m.Show.parsed_title_norm == norm,
            _year_clause(m.Show.parsed_year, year),
        )
        .order_by(m.Show.id)
        .limit(1)
    )
    if show_id is not None:
        return show_id
    show = m.Show(
        library_id=library_id,
        match_status="pending",
        parsed_title=title,
        parsed_title_norm=norm,
        parsed_year=year,
        title=title,
        year=year,
        sort_title=sort_title(title),
        search_text=search_text(title, None),
    )
    session.add(show)
    session.flush()
    return show.id


def link_episodes(
    session: Session, media_file_id: int, show_id: int, season_number: int, episodes: Iterable[int]
) -> list[int]:
    """Find/create the season and episodes of ``show_id`` and link the file to each episode."""
    season = session.scalar(
        select(m.Season).where(m.Season.show_id == show_id, m.Season.season_number == season_number)
    )
    if season is None:
        season = m.Season(show_id=show_id, season_number=season_number)
        session.add(season)
        session.flush()
    episode_ids: list[int] = []
    for number in sorted(set(episodes)):
        episode = session.scalar(
            select(m.Episode).where(
                m.Episode.season_id == season.id, m.Episode.episode_number == number
            )
        )
        if episode is None:
            episode = m.Episode(
                season_id=season.id,
                show_id=show_id,
                season_number=season_number,
                episode_number=number,
            )
            session.add(episode)
            session.flush()
        episode_ids.append(episode.id)
        if session.get(m.MediaFileEpisode, (media_file_id, episode.id)) is None:
            session.add(m.MediaFileEpisode(media_file_id=media_file_id, episode_id=episode.id))
    session.flush()
    return episode_ids


def unlink_file(session: Session, row: m.MediaFile) -> None:
    row.film_id = None
    session.execute(delete(m.MediaFileEpisode).where(m.MediaFileEpisode.media_file_id == row.id))


def link_manual_match(session: Session, row: m.MediaFile, manual: dict[str, Any]) -> bool:
    """Link ``row`` per ``media_file.manual_match``. False when its target item is gone."""
    tmdb_id = manual.get("tmdb_id")
    if "season" in manual:
        show_id = session.scalar(
            select(m.Show.id).where(m.Show.library_id == row.library_id, m.Show.tmdb_id == tmdb_id)
        )
        if show_id is None:
            return False
        link_episodes(session, row.id, show_id, int(manual["season"]), manual.get("episodes", []))
        return True
    film_id = session.scalar(
        select(m.Film.id).where(m.Film.library_id == row.library_id, m.Film.tmdb_id == tmdb_id)
    )
    if film_id is None:
        return False
    row.film_id = film_id
    return True


def delete_orphans(session: Session) -> None:
    """Episodes without files, seasons without episodes, shows without seasons, films without
    files (PRD §4 step 7)."""
    session.execute(
        delete(m.Episode).where(
            m.Episode.id.not_in(select(m.MediaFileEpisode.episode_id).scalar_subquery())
        )
    )
    session.execute(
        delete(m.Season).where(m.Season.id.not_in(select(m.Episode.season_id).scalar_subquery()))
    )
    session.execute(
        delete(m.Show).where(m.Show.id.not_in(select(m.Season.show_id).scalar_subquery()))
    )
    session.execute(
        delete(m.Film).where(
            m.Film.id.not_in(
                select(m.MediaFile.film_id)
                .where(m.MediaFile.film_id.is_not(None))
                .scalar_subquery()
            )
        )
    )


def ensure_libraries(session: Session) -> dict[str, int]:
    ids = {lib.type: lib.id for lib in session.scalars(select(m.Library))}
    for lib_type in ("films", "shows"):
        if lib_type not in ids:
            lib = m.Library(type=lib_type)
            session.add(lib)
            session.flush()
            ids[lib_type] = lib.id
    return ids


# --- Scanner ------------------------------------------------------------------------------------


class Scanner:
    def __init__(
        self,
        db: Database,
        settings: Settings,
        *,
        probe_fn: ProbeFn | None = None,
        fs: FsOps | None = None,
        keyframe_hook: KeyframeHook | None = None,
    ) -> None:
        self.db = db
        self.settings = settings
        self.probe_fn: ProbeFn = probe_fn or functools.partial(
            probe, ffprobe_path=settings.ffmpeg.ffprobe_path
        )
        self.fs = fs or FsOps()
        self.keyframe_hook = keyframe_hook
        self._report = ScanReport()
        self._run_id: int | None = None
        self._libraries: dict[str, int] = {}

    async def run(self, report: ScanReport | None = None, run_id: int | None = None) -> ScanReport:
        """Scan every configured root. Updates ``report`` (and ``scan_run`` row ``run_id``)."""
        self._report = report or ScanReport()
        self._run_id = run_id
        rep = self._report
        rep.phase = "walking"
        self._libraries = await asyncio.to_thread(self._sync_libraries)

        walks = await asyncio.to_thread(self._walk_all)
        videos = [v for w in walks for v in w.videos]
        rep.total = len(videos)
        rep.counts["files_seen"] = len(videos)

        work, missing_ids = await asyncio.to_thread(self._diff, walks)
        rep.processed = len(videos) - len(work)
        rep.phase = "probing"
        await asyncio.to_thread(self._mark_missing, missing_ids)

        semaphore = asyncio.Semaphore(self.settings.scan.probe_concurrency)
        touched: set[int] = set()
        for chunk in _chunks(work, BATCH_SIZE):
            outcomes = await asyncio.gather(*(self._probe(w, semaphore) for w in chunk))
            ids = await asyncio.to_thread(self._apply_chunk, list(chunk), list(outcomes))
            touched.update(ids)

        await asyncio.to_thread(self._sync_sidecars, walks)
        if self.keyframe_hook is not None:
            queue = await asyncio.to_thread(self._keyframe_candidates, touched)
            if queue:
                self.keyframe_hook(queue)
        await asyncio.to_thread(self._purge)
        logger.info("scan_files_done counts=%s errors=%d", rep.counts, len(rep.errors))
        return rep

    # -- step 1 --

    def _sync_libraries(self) -> dict[str, int]:
        with self.db.write_session() as session:
            ids = ensure_libraries(session)
            self._report.save(session, self._run_id)
        return ids

    # -- step 2: walk --

    def _walk_all(self) -> list[_RootWalk]:
        roots: list[tuple[str, LibraryType]] = [
            *((r, "films") for r in self.settings.libraries.films),
            *((r, "shows") for r in self.settings.libraries.shows),
        ]
        real_roots = [os.path.realpath(r) for r, _ in roots]
        seen: set[str] = set()
        return [self._walk(root, lib_type, real_roots, seen) for root, lib_type in roots]

    def _walk(
        self, root: str, library_type: LibraryType, real_roots: list[str], seen: set[str]
    ) -> _RootWalk:
        rep = self._report
        walk = _RootWalk(root=root, library_type=library_type)
        try:
            self.fs.stat(root)
        except OSError:
            rep.add_error(f"root_unavailable: {root}")
            walk.fully_walked = False
            return walk
        stack = [root]
        while stack:
            directory = stack.pop()
            try:
                with self.fs.scandir(directory) as it:
                    entries = sorted(it, key=lambda e: e.name)
            except OSError as exc:
                if directory == root:
                    rep.add_error(f"root_unavailable: {root}")
                    walk.fully_walked = False
                    return walk
                rep.add_error(f"walk_error: {directory}: {_errno_name(exc)}")
                walk.protected_dirs.append(directory + os.sep)
                continue
            walk.walked_dirs.append(directory)
            subdirs: list[str] = []
            undecodable = False
            for entry in entries:
                name = entry.name
                if _undecodable(name):
                    undecodable = True
                    continue
                if name.startswith("."):
                    continue
                try:
                    is_link = entry.is_symlink()
                    if not is_link and entry.is_dir(follow_symlinks=False):
                        subdirs.append(entry.path)
                        continue
                except OSError as exc:
                    rep.add_error(f"walk_error: {entry.path}: {_errno_name(exc)}")
                    walk.protected_files.add(entry.path)
                    continue
                self._walk_file(walk, directory, entry.path, name, is_link, real_roots, seen)
            if undecodable:
                rep.add_error(f"undecodable_name: {directory}")
            stack.extend(reversed(subdirs))
        try:
            self.fs.stat(root)
        except OSError as exc:
            rep.add_error(f"walk_error: {root}: {_errno_name(exc)}")
            walk.fully_walked = False
        return walk

    def _walk_file(
        self,
        walk: _RootWalk,
        directory: str,
        path: str,
        name: str,
        is_link: bool,
        real_roots: list[str],
        seen: set[str],
    ) -> None:
        rep = self._report
        stem, ext = os.path.splitext(name)
        ext = ext.lower()
        is_video = ext in VIDEO_EXTENSIONS
        if not is_video and ext not in SIDECAR_EXTENSIONS:
            return
        try:
            st = self.fs.stat(path)
        except OSError as exc:
            if is_link and exc.errno in _BROKEN_LINK_ERRNOS:
                rep.add_error(f"broken_symlink: {path}")
                return
            rep.add_error(f"walk_error: {path}: {_errno_name(exc)}")
            walk.protected_files.add(path)
            return
        if not stat_mod.S_ISREG(st.st_mode):
            return  # directory symlinks are never followed; devices/fifos skipped
        if is_link:
            real = os.path.realpath(path)
            if not any(_within(real, r) for r in real_roots):
                rep.add_error(f"symlink_outside_root: {path}")
                return
        rel = PurePosixPath(*Path(os.path.relpath(path, walk.root)).parts).as_posix()
        if is_video:
            if path in seen:
                rep.add_error(f"duplicate_path: {path}")
                return
            seen.add(path)
            video = _Video(
                path, walk.root, walk.library_type, rel, directory, st.st_size, st.st_mtime_ns
            )
            walk.videos.append(video)
            walk.stems[directory].add(stem)
        else:
            walk.subs[directory].append(_Sub(path, rel, st.st_size, st.st_mtime_ns))

    # -- step 3/4: diff + parse --

    def _diff(self, walks: list[_RootWalk]) -> tuple[list[_Work], list[int]]:
        rep = self._report
        with self.db.read_session() as session:
            rows = {
                r.path: _Row(
                    r.id,
                    r.path,
                    r.size_bytes,
                    r.mtime_ns,
                    r.missing_since is not None,
                    r.probe_error is not None,
                    r.root_path,
                )
                for r in session.execute(
                    select(
                        m.MediaFile.id,
                        m.MediaFile.path,
                        m.MediaFile.size_bytes,
                        m.MediaFile.mtime_ns,
                        m.MediaFile.missing_since,
                        m.MediaFile.probe_error,
                        m.MediaFile.root_path,
                    )
                )
            }

        present_by_root: dict[str, int] = defaultdict(int)
        for known in rows.values():
            if not known.missing:
                present_by_root[known.root_path] += 1
        for walk in walks:
            if walk.fully_walked and not walk.videos and present_by_root.get(walk.root, 0) > 0:
                rep.add_error(f"root_empty_skipped_removal: {walk.root}")
                walk.fully_walked = False

        work: list[_Work] = []
        seen: set[str] = set()
        for walk in walks:
            for video in walk.videos:
                seen.add(video.path)
                row = rows.get(video.path)
                if row is None:
                    parsed = self._parse(walk, video)
                    if isinstance(parsed, Ignored):
                        rep.count("ignored")
                        continue
                    rep.count("new")
                    work.append(_Work(video, None, True, False, False, True, parsed))
                    continue
                changed = row.size != video.size or row.mtime_ns != video.mtime_ns
                if row.missing:
                    rep.count("restored")
                if changed:
                    parsed = self._parse(walk, video)
                    if isinstance(parsed, Ignored):
                        rep.count("ignored")
                    else:
                        rep.count("changed")
                    work.append(_Work(video, row.id, True, True, row.missing, True, parsed))
                    continue
                rep.count("unchanged")
                if row.probe_error or row.missing:
                    work.append(_Work(video, row.id, False, False, row.missing, row.probe_error))

        configured = {w.root: w for w in walks}
        missing_ids: list[int] = []
        for row in rows.values():
            if row.missing or row.path in seen:
                continue
            root_walk = configured.get(row.root_path)
            if root_walk is None:
                rep.add_error(f"root_unconfigured: {row.root_path}")
                missing_ids.append(row.id)
            elif root_walk.fully_walked and not root_walk.is_protected(row.path):
                missing_ids.append(row.id)
        return work, missing_ids

    @staticmethod
    def _parse(walk: _RootWalk, video: _Video) -> ParseResult:
        return parse(PurePosixPath(video.rel), walk.library_type, frozenset(walk.stems[video.dir]))

    def _mark_missing(self, ids: list[int]) -> None:
        now = m.utcnow()
        for chunk in _chunks(ids, 500):
            with self.db.write_session() as session:
                session.execute(
                    update(m.MediaFile)
                    .where(m.MediaFile.id.in_(chunk), m.MediaFile.missing_since.is_(None))
                    .values(missing_since=now)
                )
        self._report.count("missing", len(ids))
        with self.db.write_session() as session:  # also publishes phase "probing" + total
            self._report.save(session, self._run_id)

    # -- step 5: probe --

    async def _probe(self, work: _Work, semaphore: asyncio.Semaphore) -> _ProbeOutcome | None:
        if not work.needs_probe or isinstance(work.parsed, Ignored):
            return None
        async with semaphore:
            try:
                return _ProbeOutcome(result=await self.probe_fn(work.video.path))
            except ProbeError as exc:
                return _ProbeOutcome(error=str(exc) or "probe_failed")
            except OSError as exc:
                return _ProbeOutcome(error=f"{_errno_name(exc)}: {exc.strerror or exc}")

    # -- step 4-6 apply (one short transaction per batch) --

    def _apply_chunk(self, chunk: list[_Work], outcomes: list[_ProbeOutcome | None]) -> set[int]:
        touched: set[int] = set()
        stale_caches: list[int] = []
        with self.db.write_session() as session:
            for work, outcome in zip(chunk, outcomes, strict=True):
                row_id = self._apply_one(session, work, outcome)
                if row_id is not None and work.reparse:
                    touched.add(row_id)
                    if work.changed:
                        stale_caches.append(row_id)
            self._report.processed += len(chunk)
            self._report.save(session, self._run_id)
        for media_file_id in stale_caches:
            shutil.rmtree(self.settings.subtitles_dir / str(media_file_id), ignore_errors=True)
        return touched

    def _apply_one(
        self, session: Session, work: _Work, outcome: _ProbeOutcome | None
    ) -> int | None:
        video = work.video
        row = session.get(m.MediaFile, work.row_id) if work.row_id is not None else None
        if isinstance(work.parsed, Ignored):
            if row is not None:
                session.delete(row)
            return None
        library_id = self._libraries[video.library_type]
        if row is None:
            row = m.MediaFile(
                library_id=library_id,
                root_path=video.root,
                path=video.path,
                relative_path=video.rel,
                size_bytes=video.size,
                mtime_ns=video.mtime_ns,
            )
            session.add(row)
            session.flush()
        row.library_id = library_id
        row.root_path = video.root
        row.relative_path = video.rel
        row.size_bytes = video.size
        row.mtime_ns = video.mtime_ns
        row.missing_since = None

        if work.changed:
            session.execute(delete(m.KeyframeIndex).where(m.KeyframeIndex.media_file_id == row.id))
        if outcome is not None:
            self._apply_probe(session, row, outcome)
        if work.reparse:
            assert work.parsed is not None
            self._apply_parse(session, row, work.parsed, video.library_type)
        return row.id

    def _apply_probe(self, session: Session, row: m.MediaFile, outcome: _ProbeOutcome) -> None:
        if outcome.result is not None:
            result = outcome.result
            row.probe = result.to_json()
            row.probe_error = None
            row.container = result.container
            row.duration_seconds = result.duration_seconds
            video = result.video
            row.video_codec = video.codec if video else None
            row.width = video.width if video else None
            row.height = video.height if video else None
            row.hdr = video.hdr if video else None
            session.execute(
                delete(m.UnmatchedEntry).where(
                    m.UnmatchedEntry.media_file_id == row.id,
                    m.UnmatchedEntry.reason == "probe_failed",
                )
            )
            return
        row.probe = None
        row.probe_error = outcome.error
        row.container = row.video_codec = row.hdr = None
        row.duration_seconds = None
        row.width = row.height = None
        self._ensure_entry(session, row.id, "probe_failed", outcome.error)

    def _ensure_entry(self, session: Session, media_file_id: int, reason: str, detail: Any) -> None:
        existing = session.scalar(
            select(m.UnmatchedEntry).where(
                m.UnmatchedEntry.media_file_id == media_file_id, m.UnmatchedEntry.reason == reason
            )
        )
        if existing is None:
            session.add(m.UnmatchedEntry(media_file_id=media_file_id, reason=reason, detail=detail))
            session.flush()
            self._report.count("unmatched")
        else:
            existing.detail = detail

    def _apply_parse(
        self, session: Session, row: m.MediaFile, parsed: ParseResult, library_type: LibraryType
    ) -> None:
        row.parsed = serialize_parse(parsed)
        row.part_number = parsed.part if isinstance(parsed, MultiPart) else None
        unlink_file(session, row)

        reason: str | None = None
        detail: str | None = None
        manual = row.manual_match  # re-read inside this transaction (resolve made mid-scan)
        if manual and link_manual_match(session, row, manual):
            pass
        elif isinstance(parsed, ParsedFilm) and library_type == "films":
            row.film_id = find_or_create_film(session, row.library_id, parsed.title, parsed.year)
        elif isinstance(parsed, ParsedEpisode):
            show_id = find_or_create_show(
                session, row.library_id, parsed.show_title, parsed.show_year
            )
            link_episodes(session, row.id, show_id, parsed.season, parsed.episodes)
            show = session.get(m.Show, show_id)
            assert show is not None
            show.content_updated_at = m.utcnow()
        elif isinstance(parsed, MultiPart):
            reason, detail = "multi_part", f"part {parsed.part} of {parsed.title!r}"
        elif isinstance(parsed, Unparseable):
            reason, detail = parsed.reason, parsed.detail

        existing = session.scalars(
            select(m.UnmatchedEntry).where(
                m.UnmatchedEntry.media_file_id == row.id,
                m.UnmatchedEntry.reason.in_(PARSE_REASONS),
            )
        ).all()
        for entry in existing:
            if entry.reason != reason:
                session.delete(entry)
        session.flush()
        if reason is not None:
            self._ensure_entry(session, row.id, reason, detail)

    # -- step 7: purge --

    def _purge(self) -> None:
        grace = self.settings.scan.missing_grace_days
        cutoff = m.utcnow() - timedelta(days=grace)
        with self.db.write_session() as session:
            ids = list(
                session.scalars(
                    select(m.MediaFile.id).where(
                        m.MediaFile.missing_since.is_not(None), m.MediaFile.missing_since <= cutoff
                    )
                )
            )
            if ids:
                session.execute(delete(m.MediaFile).where(m.MediaFile.id.in_(ids)))
            self._report.count("removed", len(ids))
            self._report.save(session, self._run_id)
        for media_file_id in ids:
            shutil.rmtree(self.settings.subtitles_dir / str(media_file_id), ignore_errors=True)
        with self.db.write_session() as session:
            delete_orphans(session)

    # -- step 8: sidecars --

    def _sync_sidecars(self, walks: list[_RootWalk]) -> None:
        all_videos = [v for w in walks for v in w.videos]
        if not all_videos:
            return
        with self.db.read_session() as session:
            ids: dict[str, int] = {}
            for paths in _chunks([v.path for v in all_videos], 500):
                rows = session.execute(
                    select(m.MediaFile.path, m.MediaFile.id).where(
                        m.MediaFile.path.in_(paths), m.MediaFile.missing_since.is_(None)
                    )
                )
                ids.update((path, media_file_id) for path, media_file_id in rows)
            media_ids = list(ids.values())
            existing: dict[str, m.SubtitleFile] = {}
            for id_chunk in _chunks(media_ids, 500):
                for row in session.scalars(
                    select(m.SubtitleFile).where(m.SubtitleFile.media_file_id.in_(id_chunk))
                ):
                    existing[row.path] = row

        desired: dict[str, dict[str, Any]] = {}
        for walk in walks:
            by_dir: dict[str, list[_Video]] = defaultdict(list)
            for video in walk.videos:
                if video.path in ids:
                    by_dir[video.dir].append(video)
            for directory, videos in by_dir.items():
                videos.sort(key=lambda v: len(PurePosixPath(v.rel).stem), reverse=True)
                for sub in walk.subs.get(directory, []):
                    sub_rel = PurePosixPath(unicodedata.normalize("NFC", sub.rel))
                    for video in videos:
                        info = parse_sidecar(
                            PurePosixPath(unicodedata.normalize("NFC", video.rel)), sub_rel
                        )
                        if info is None:
                            continue
                        desired[sub.path] = {
                            "media_file_id": ids[video.path],
                            "language": info.language,
                            "forced": info.forced,
                            "hearing_impaired": info.hearing_impaired,
                            "format": info.format,
                            "kind": info.kind,
                            "size_bytes": sub.size,
                            "mtime_ns": sub.mtime_ns,
                        }
                        break

        to_delete = [s.id for p, s in existing.items() if p not in desired]
        to_upsert = [
            (path, values)
            for path, values in desired.items()
            if path not in existing
            or any(getattr(existing[path], k) != v for k, v in values.items())
        ]
        for delete_ids in _chunks(to_delete, 500):
            with self.db.write_session() as session:
                session.execute(delete(m.SubtitleFile).where(m.SubtitleFile.id.in_(delete_ids)))
        for upserts in _chunks(to_upsert, BATCH_SIZE):
            with self.db.write_session() as session:
                for path, values in upserts:
                    stored = session.scalar(
                        select(m.SubtitleFile).where(m.SubtitleFile.path == path)
                    )
                    if stored is None:
                        session.add(m.SubtitleFile(path=path, **values))
                    else:
                        for key, value in values.items():
                            setattr(stored, key, value)
        # Cached conversions (``subtitles/{media_file_id}/ext-{id}.vtt``) of removed or changed
        # sidecars are stale; ids are not AUTOINCREMENT, so a reused id must not inherit one.
        stale = [(s.media_file_id, s.id) for p, s in existing.items() if p not in desired]
        stale += [
            (mid, existing[path].id)
            for path, values in to_upsert
            if path in existing
            for mid in {existing[path].media_file_id, values["media_file_id"]}
        ]
        for media_file_id, sub_id in stale:
            with contextlib.suppress(OSError):
                (self.settings.subtitles_dir / str(media_file_id) / f"ext-{sub_id}.vtt").unlink()

    # -- step 5a: keyframe hook --

    def _keyframe_candidates(self, touched: set[int]) -> list[int]:
        with self.db.read_session() as session:
            ids = list(
                session.scalars(
                    select(m.MediaFile.id)
                    .outerjoin(m.KeyframeIndex, m.KeyframeIndex.media_file_id == m.MediaFile.id)
                    .where(
                        m.MediaFile.missing_since.is_(None),
                        m.MediaFile.probe_error.is_(None),
                        m.MediaFile.video_codec.is_not(None),
                        m.MediaFile.duration_seconds.is_not(None),
                        m.MediaFile.container.in_(KEYFRAME_CONTAINERS),
                        m.KeyframeIndex.media_file_id.is_(None),
                    )
                    .order_by(m.MediaFile.id)
                )
            )
        return sorted(ids, key=lambda i: (i not in touched, i))
