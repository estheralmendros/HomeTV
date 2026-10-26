"""Library folders managed from Administración (HOMETV-1): the stored folder list, its
seeding, validation, change plans and the admin folder browser.

The database owns the folders (``library_folder``). They are seeded once per library
(``library.folders_seeded_at``) from the env/YAML library keys, else from the roots of present
``media_file`` rows, else empty; afterwards env/YAML library keys are ignored.

Pure helpers (no DB; filesystem only through ``FolderFs``): ``normalize_folder``,
``same_folders``, ``choose_seed``, ``validate_change``, ``split_removals``, ``browse_listing``.
``LibraryFolders`` (``app.state.libraries``) holds an immutable in-memory ``LibraryFolderSet``
(``current()``, the scanner's folder provider) and implements plan / save / status / browse.

Paths are stored normalized (``config._normalize_root``), never realpath'ed, so they equal the
``media_file.root_path`` strings the scanner writes; nesting and guard checks use realpaths.

A **relink** is a known file (present or missing) inside a proposed folder ``P`` of library
``L`` that the scanner would reparse and relink: a **move** (the row belongs to the other
library) or a **relayout** (a Series row whose ``root_path`` is not ``P``). ``plan()`` reports
them; only the scan started by a confirmed save (trigger ``libraries``) applies them.
"""

import errno
import logging
import os
import posixpath
import stat as stat_mod
from collections import defaultdict
from collections.abc import Callable, Iterable
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Literal

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from hometv.api.errors import ApiError
from hometv.api.schemas import (
    BrowseEntry,
    BrowseResponse,
    BrowseRoot,
    LibrariesPlan,
    LibrariesResponse,
    LibraryFolderOut,
    PlanCoveredOut,
    PlanMovedOut,
    PlanRelayoutOut,
    PlanRemovedOut,
)
from hometv.config import LibrariesSettings, Settings, _normalize_root
from hometv.db import models as m
from hometv.db.engine import Database

logger = logging.getLogger("hometv.libraries")

LibraryType = Literal["films", "shows"]
LIBRARY_TYPES: tuple[LibraryType, LibraryType] = ("films", "shows")
FolderStatus = Literal["available", "not_found", "not_readable"]
FolderErrorCode = Literal[
    "not_absolute",
    "not_found",
    "not_directory",
    "not_readable",
    "duplicate",
    "overlaps_folder",
    "contains_data_dir",
    "inside_data_dir",
    "contains_hls_dir",
    "inside_hls_dir",
]
SeedSource = Literal["config", "media_files", "empty"]
_NOT_FOUND_ERRNOS = {errno.ENOENT, errno.ENOTDIR}


@dataclass(frozen=True)
class LibraryFolderSet:
    films: tuple[str, ...] = ()
    shows: tuple[str, ...] = ()

    def roots(self) -> list[tuple[str, LibraryType]]:
        """Films first, then shows, each in list order."""
        return [*((r, "films") for r in self.films), *((r, "shows") for r in self.shows)]

    def of(self, library: LibraryType) -> tuple[str, ...]:
        return self.films if library == "films" else self.shows

    @classmethod
    def from_settings(cls, libraries: LibrariesSettings) -> "LibraryFolderSet":
        return cls(films=tuple(libraries.films), shows=tuple(libraries.shows))


@dataclass(frozen=True)
class FolderError:
    library: LibraryType
    path: str  # as sent by the client (normalized when absolute)
    code: FolderErrorCode
    other: str | None  # the conflicting folder for duplicate / overlaps_folder


@dataclass(frozen=True)
class PlanRemoved:
    """A removed folder whose titles are hidden now, purged after the grace period."""

    library: LibraryType
    path: str
    title_count: int


@dataclass(frozen=True)
class PlanCovered:
    """A removed Películas folder inside an added Películas folder (path-only re-root)."""

    library: LibraryType
    path: str
    by: str
    title_count: int


@dataclass(frozen=True)
class PlanMoved:
    """Relink: known files of ``from_library`` under root ``from_path`` now inside ``to_path``."""

    from_library: LibraryType
    from_path: str
    to_library: LibraryType
    to_path: str
    title_count: int


@dataclass(frozen=True)
class PlanRelayout:
    """Relink: known Series files stored under root ``from_path``, now inside the proposed
    Series folder ``to_path`` (reparsed from the new root)."""

    from_path: str
    to_path: str
    title_count: int


@dataclass(frozen=True)
class ChangePlan:
    removed: tuple[PlanRemoved, ...] = ()
    covered: tuple[PlanCovered, ...] = ()
    moved: tuple[PlanMoved, ...] = ()
    relayout: tuple[PlanRelayout, ...] = ()

    @property
    def has_relinks(self) -> bool:
        return bool(self.moved or self.relayout)


@dataclass(frozen=True)
class SeedPlan:
    folders: LibraryFolderSet
    source: SeedSource
    dropped: tuple[tuple[LibraryType, str], ...] = ()


@dataclass(frozen=True)
class FolderFs:
    """Filesystem primitives (injectable, like ``scanner.FsOps``). Read-only."""

    stat: Callable[[str], os.stat_result] = os.stat
    scandir: Callable[..., Any] = os.scandir
    realpath: Callable[[str], str] = os.path.realpath


# --- Pure helpers -------------------------------------------------------------------------------


def _library_type(value: str | None) -> LibraryType | None:
    if value == "films":
        return "films"
    if value == "shows":
        return "shows"
    return None


def _within(path: str, root: str) -> bool:
    """Same nesting rule as the scanner (on normalized or real paths)."""
    return path == root or path.startswith(root.rstrip("/") + "/")


def normalize_folder(raw: str) -> str | None:
    """``None`` unless ``raw`` (stripped) is absolute; else the normalized path."""
    stripped = raw.strip()
    if not stripped.startswith("/"):
        return None
    return _normalize_root(stripped)


def same_folders(current: LibraryFolderSet, films: list[str], shows: list[str]) -> bool:
    """True when every entry normalizes and each library's set equals the stored one."""
    for stored, proposed in ((current.films, films), (current.shows, shows)):
        normalized = [normalize_folder(p) for p in proposed]
        if any(p is None for p in normalized) or set(normalized) != set(stored):
            return False
    return True


def choose_seed(
    configured: LibraryFolderSet, existing_roots: list[tuple[LibraryType, str]]
) -> SeedPlan:
    """Seed source: env/YAML when any key is set, else present ``media_file`` roots, else
    nothing. Deduped (normalized; films before shows, list order; first occurrence wins)."""
    candidates: list[tuple[LibraryType, str]]
    source: SeedSource
    if configured.films or configured.shows:
        source, candidates = "config", [(lib, path) for path, lib in configured.roots()]
    elif existing_roots:
        source = "media_files"
        candidates = [r for r in existing_roots if r[0] == "films"]
        candidates += [r for r in existing_roots if r[0] == "shows"]
    else:
        return SeedPlan(LibraryFolderSet(), "empty")
    kept: dict[LibraryType, list[str]] = {"films": [], "shows": []}
    seen: set[str] = set()
    dropped: list[tuple[LibraryType, str]] = []
    for library, raw in candidates:
        path = _normalize_root(raw)
        if path in seen:
            dropped.append((library, path))
            continue
        seen.add(path)
        kept[library].append(path)
    folders = LibraryFolderSet(films=tuple(kept["films"]), shows=tuple(kept["shows"]))
    return SeedPlan(folders, source, tuple(dropped))


def _check_added(path: str, fs: FolderFs) -> FolderErrorCode | None:
    try:
        st = fs.stat(path)
    except OSError as exc:
        return "not_found" if exc.errno in _NOT_FOUND_ERRNOS else "not_readable"
    if not stat_mod.S_ISDIR(st.st_mode):
        return "not_directory"
    try:
        with fs.scandir(path):
            pass
    except OSError:
        return "not_readable"
    return None


def validate_change(
    current: LibraryFolderSet,
    proposed_films: list[str],
    proposed_shows: list[str],
    *,
    data_dir: str | Path,
    hls_dir: str | Path,
    fs: FolderFs,
) -> list[FolderError]:
    """At most one error per proposed entry (the first failing check); films first."""
    stored = set(current.films) | set(current.shows)
    data_real = fs.realpath(str(data_dir))
    hls_real = fs.realpath(str(hls_dir))
    earlier: list[tuple[str, str]] = []  # (normalized, realpath) of earlier absolute entries
    errors: list[FolderError] = []
    entries: list[tuple[LibraryType, str]] = [("films", p) for p in proposed_films]
    entries += [("shows", p) for p in proposed_shows]
    for library, raw in entries:
        path = normalize_folder(raw)
        if path is None:
            errors.append(FolderError(library, raw, "not_absolute", None))
            continue
        real = fs.realpath(path)
        code: FolderErrorCode | None = None
        other: str | None = None
        if path not in stored:
            code = _check_added(path, fs)
        if code is None:
            other = next((p for p, _ in earlier if p == path), None)
            code = "duplicate" if other is not None else None
        if code is None:
            other = next((p for p, r in earlier if _within(real, r) or _within(r, real)), None)
            code = "overlaps_folder" if other is not None else None
        if code is None:
            if _within(data_real, real):
                code = "contains_data_dir"
            elif _within(real, data_real):
                code = "inside_data_dir"
            elif _within(hls_real, real):
                code = "contains_hls_dir"
            elif _within(real, hls_real):
                code = "inside_hls_dir"
        earlier.append((path, real))
        if code is not None:
            errors.append(FolderError(library, path, code, other))
    return errors


def split_removals(
    current: LibraryFolderSet, films: list[str], shows: list[str]
) -> tuple[list[tuple[LibraryType, str]], list[tuple[LibraryType, str, str]]]:
    """Stored folders not proposed in their library -> ``removed`` or (Películas inside an
    added Películas folder) ``covered``. A folder proposed in the other library is neither.
    Inputs are validated and normalized."""
    proposed: dict[LibraryType, set[str]] = {"films": set(films), "shows": set(shows)}
    added_films = [f for f in films if f not in current.films]
    removed: list[tuple[LibraryType, str]] = []
    covered: list[tuple[LibraryType, str, str]] = []
    for path, library in current.roots():
        other: LibraryType = "shows" if library == "films" else "films"
        if path in proposed[library] or path in proposed[other]:
            continue
        by = (
            next((a for a in added_films if _within(path, a)), None) if library == "films" else None
        )
        if by is not None:
            covered.append((library, path, by))
        else:
            removed.append((library, path))
    return removed, covered


def browse_listing(base: str, path: str | None, *, fs: FolderFs) -> BrowseResponse:
    """Subfolders of ``path`` (default ``base``), never outside ``realpath(base)``."""
    raw = base if path is None else path
    normalized = normalize_folder(raw)
    if normalized is None:
        raise ApiError(
            422,
            "validation_error",
            "Path must be absolute",
            {"errors": [{"loc": ["query", "path"], "msg": "Path must be absolute"}]},
        )
    base_norm = _normalize_root(base)
    base_real = fs.realpath(base_norm)
    real = fs.realpath(normalized)
    if not _within(real, base_real):
        raise ApiError(403, "outside_browse_root", "Path is outside the browse root")
    try:
        st = fs.stat(normalized)
    except OSError as exc:
        if exc.errno in _NOT_FOUND_ERRNOS:
            raise ApiError(404, "folder_not_found", "Folder not found") from exc
        raise ApiError(403, "folder_not_readable", "Folder cannot be listed") from exc
    if not stat_mod.S_ISDIR(st.st_mode):
        raise ApiError(404, "folder_not_found", "Folder not found")
    entries: list[BrowseEntry] = []
    try:
        with fs.scandir(normalized) as it:
            for entry in it:
                name = entry.name
                if name.startswith(".") or _undecodable(name):
                    continue
                try:
                    if not entry.is_dir():
                        continue
                    child_real = (
                        fs.realpath(entry.path)
                        if entry.is_symlink()
                        else posixpath.join(real, name)
                    )
                except OSError:
                    continue
                if not _within(child_real, base_real):
                    continue
                entries.append(BrowseEntry(name=name, path=posixpath.join(normalized, name)))
    except OSError as exc:
        raise ApiError(403, "folder_not_readable", "Folder cannot be listed") from exc
    entries.sort(key=lambda e: (e.name.casefold(), e.name))
    parent = None if normalized == base_norm else posixpath.dirname(normalized)
    return BrowseResponse(path=normalized, parent=parent, entries=entries)


def _undecodable(name: str) -> bool:
    try:
        name.encode("utf-8")
    except UnicodeEncodeError:
        return True
    return False


def folder_status(path: str, fs: FolderFs) -> FolderStatus:
    """Live availability of a stored folder."""
    try:
        st = fs.stat(path)
    except OSError as exc:
        return "not_found" if exc.errno in _NOT_FOUND_ERRNOS else "not_readable"
    if not stat_mod.S_ISDIR(st.st_mode):
        return "not_found"
    try:
        with fs.scandir(path):
            pass
    except OSError as exc:
        return "not_found" if exc.errno in _NOT_FOUND_ERRNOS else "not_readable"
    return "available"


def plan_out(plan: ChangePlan) -> LibrariesPlan:
    return LibrariesPlan(
        removed=[PlanRemovedOut(**asdict(r)) for r in plan.removed],
        covered=[PlanCoveredOut(**asdict(c)) for c in plan.covered],
        moved=[PlanMovedOut(**asdict(mv)) for mv in plan.moved],
        relayout=[PlanRelayoutOut(**asdict(r)) for r in plan.relayout],
    )


# --- Service ------------------------------------------------------------------------------------


@dataclass
class _KnownRow:
    id: int
    library: LibraryType
    root_path: str
    titles: set[tuple[str, int]] = field(default_factory=set)  # ("film"|"show", id)


def _normalized(paths: list[str]) -> list[str]:
    """Normalized form of entries that passed ``validate_change`` (all absolute)."""
    return [_normalize_root(p.strip()) for p in paths]


def _range(folder: str) -> tuple[str, str]:
    """``path`` bounds of every file strictly inside ``folder`` (``/`` < ``0``; no LIKE)."""
    stem = folder.rstrip("/")
    return stem + "/", stem + "0"


class LibraryFolders:
    """Stored library folders (``app.state.libraries``)."""

    def __init__(self, db: Database, settings: Settings, *, fs: FolderFs | None = None) -> None:
        self.db = db
        self.settings = settings
        self.fs = fs or FolderFs()
        self._current = LibraryFolderSet()

    # -- seeding / cache --

    def seed_once(self) -> None:
        """Seed each library whose marker is NULL (once ever), then load the cache."""
        from hometv.scanner import ensure_libraries  # scanner imports this module

        configured = LibraryFolderSet.from_settings(self.settings.libraries)
        with self.db.write_session() as session:
            ensure_libraries(session)
            libs = {lib.type: lib for lib in session.scalars(select(m.Library))}
            unseeded = [t for t in LIBRARY_TYPES if libs[t].folders_seeded_at is None]
            if unseeded:
                existing: list[tuple[LibraryType, str]] = []
                if not (configured.films or configured.shows):
                    existing = self._present_roots(session)
                seed = choose_seed(configured, existing)
                for library, path in seed.dropped:
                    logger.warning("library_seed_duplicate_dropped: %s %s", library, path)
                stored = set(session.scalars(select(m.LibraryFolder.path)))
                now = m.utcnow()
                for library in unseeded:
                    for path in seed.folders.of(library):
                        if path not in stored:
                            session.add(m.LibraryFolder(library_id=libs[library].id, path=path))
                            stored.add(path)
                    libs[library].folders_seeded_at = now
                logger.info(
                    "library_folders_seeded source=%s films=%d shows=%d",
                    seed.source,
                    len(seed.folders.films) if "films" in unseeded else 0,
                    len(seed.folders.shows) if "shows" in unseeded else 0,
                )
        current = self.load()
        for library in LIBRARY_TYPES:
            configured_paths = configured.of(library)
            if configured_paths and set(configured_paths) != set(current.of(library)):
                logger.warning(
                    "library_config_ignored: %s (library folders are managed in "
                    "Administración → Bibliotecas)",
                    self.settings.library_source(library),
                )

    @staticmethod
    def _present_roots(session: Session) -> list[tuple[LibraryType, str]]:
        rows = session.execute(
            select(m.Library.type, m.MediaFile.root_path)
            .join(m.Library, m.Library.id == m.MediaFile.library_id)
            .where(m.MediaFile.missing_since.is_(None))
            .group_by(m.Library.type, m.MediaFile.root_path)
            .order_by(m.Library.type, func.min(m.MediaFile.id))
        )
        roots: list[tuple[LibraryType, str]] = []
        for lib, root in rows:
            library = _library_type(lib)
            if library is not None:
                roots.append((library, root))
        return roots

    def load(self) -> LibraryFolderSet:
        with self.db.read_session() as session:
            rows = session.execute(
                select(m.Library.type, m.LibraryFolder.path)
                .join(m.Library, m.Library.id == m.LibraryFolder.library_id)
                .order_by(m.LibraryFolder.id)
            ).all()
        folders = LibraryFolderSet(
            films=tuple(p for t, p in rows if t == "films"),
            shows=tuple(p for t, p in rows if t == "shows"),
        )
        self._current = folders  # atomic swap: a scan's snapshot keeps the old object
        return folders

    def current(self) -> LibraryFolderSet:
        return self._current

    def ignored_env(self) -> list[str]:
        """Names of every set (non-empty) env/YAML library key, films first."""
        return [
            self.settings.library_source(t)
            for t in LIBRARY_TYPES
            if getattr(self.settings.libraries, t)
        ]

    # -- plan / save --

    def _validated(self, films: list[str], shows: list[str]) -> tuple[list[str], list[str]]:
        errors = validate_change(
            self.current(),
            films,
            shows,
            data_dir=self.settings.data_dir,
            hls_dir=self.settings.hls_dir,
            fs=self.fs,
        )
        if errors:
            raise ApiError(
                422,
                "invalid_library_folders",
                "Invalid library folders",
                {"folders": [asdict(e) for e in errors]},
            )
        return _normalized(films), _normalized(shows)

    def plan(self, films: list[str], shows: list[str]) -> ChangePlan:
        """Validate (422 ``invalid_library_folders``) and describe a change; writes nothing."""
        norm_films, norm_shows = self._validated(films, shows)
        current = self.current()
        removed, covered = split_removals(current, norm_films, norm_shows)
        proposed: list[tuple[str, LibraryType]] = [(p, "films") for p in norm_films]
        proposed += [(p, "shows") for p in norm_shows]
        with self.db.read_session() as session:
            lib_types = {
                lib_id: lib_type
                for lib_id, lib_type in session.execute(select(m.Library.id, m.Library.type))
            }
            inside: set[int] = set()  # rows inside any proposed folder (relinked or re-rooted)
            relinked: set[int] = set()
            moved_groups: dict[tuple[LibraryType, str, LibraryType, str], set[Any]] = defaultdict(
                set
            )
            relayout_groups: dict[tuple[str, str], set[Any]] = defaultdict(set)
            for folder, library in proposed:
                for row in self._rows_inside(session, folder, lib_types).values():
                    inside.add(row.id)
                    if row.library != library:
                        relinked.add(row.id)
                        key = (row.library, row.root_path, library, folder)
                        moved_groups[key].update(row.titles)
                    elif library == "shows" and row.root_path != folder:
                        relinked.add(row.id)
                        relayout_groups[(row.root_path, folder)].update(row.titles)
            counted = [p for _, p in removed] + [p for _, p, _ in covered]
            present = self._present_titles_by_root(session, counted, lib_types)
        removed_out = []
        for library, path in removed:
            rows = present.get(path, [])
            outside = [t for row_id, t in rows if row_id not in inside]
            if rows and not outside:
                continue  # every present file stays visible (relinked or re-rooted)
            removed_out.append(PlanRemoved(library, path, len(set(outside))))
        covered_out = [
            PlanCovered(
                library,
                path,
                by,
                len({t for row_id, t in present.get(path, []) if row_id not in relinked}),
            )
            for library, path, by in covered
        ]
        moved = [
            PlanMoved(fl, fp, tl, tp, len(titles))
            for (fl, fp, tl, tp), titles in moved_groups.items()
        ]
        relayout = [PlanRelayout(fp, tp, len(t)) for (fp, tp), t in relayout_groups.items()]
        order = {"films": 0, "shows": 1}
        return ChangePlan(
            removed=tuple(sorted(removed_out, key=lambda r: (order[r.library], r.path))),
            covered=tuple(sorted(covered_out, key=lambda c: (order[c.library], c.path))),
            moved=tuple(
                sorted(moved, key=lambda mv: (order[mv.from_library], mv.from_path, mv.to_path))
            ),
            relayout=tuple(sorted(relayout, key=lambda r: (r.from_path, r.to_path))),
        )

    @staticmethod
    def _rows_inside(
        session: Session, folder: str, lib_types: dict[int, str]
    ) -> dict[int, _KnownRow]:
        """Known rows (present or missing) strictly inside ``folder``: one indexed range query
        on the unique ``media_file.path`` index."""
        low, high = _range(folder)
        rows: dict[int, _KnownRow] = {}
        result = session.execute(
            select(
                m.MediaFile.id,
                m.MediaFile.library_id,
                m.MediaFile.root_path,
                m.MediaFile.film_id,
                m.Episode.show_id,
            )
            .outerjoin(m.MediaFileEpisode, m.MediaFileEpisode.media_file_id == m.MediaFile.id)
            .outerjoin(m.Episode, m.Episode.id == m.MediaFileEpisode.episode_id)
            .where(m.MediaFile.path >= low, m.MediaFile.path < high)
        )
        for row_id, library_id, root_path, film_id, show_id in result:
            library = _library_type(lib_types.get(library_id))
            if library is None:
                continue
            known = rows.get(row_id)
            if known is None:
                known = rows[row_id] = _KnownRow(row_id, library, root_path)
            if library == "films" and film_id is not None:
                known.titles.add(("film", film_id))
            elif library == "shows" and show_id is not None:
                known.titles.add(("show", show_id))
        return rows

    @staticmethod
    def _present_titles_by_root(
        session: Session, roots: list[str], lib_types: dict[int, str]
    ) -> dict[str, list[tuple[int, tuple[str, int]]]]:
        """``root_path`` -> [(media_file id, title key)] of present files (two queries)."""
        out: dict[str, list[tuple[int, tuple[str, int]]]] = defaultdict(list)
        if not roots:
            return out
        films = session.execute(
            select(
                m.MediaFile.id, m.MediaFile.root_path, m.MediaFile.film_id, m.MediaFile.library_id
            ).where(
                m.MediaFile.root_path.in_(roots),
                m.MediaFile.missing_since.is_(None),
                m.MediaFile.film_id.is_not(None),
            )
        )
        for row_id, root, film_id, library_id in films:
            if film_id is not None and lib_types.get(library_id) == "films":
                out[root].append((row_id, ("film", film_id)))
        shows = session.execute(
            select(m.MediaFile.id, m.MediaFile.root_path, m.Episode.show_id, m.MediaFile.library_id)
            .join(m.MediaFileEpisode, m.MediaFileEpisode.media_file_id == m.MediaFile.id)
            .join(m.Episode, m.Episode.id == m.MediaFileEpisode.episode_id)
            .where(m.MediaFile.root_path.in_(roots), m.MediaFile.missing_since.is_(None))
        )
        for row_id, root, show_id, library_id in shows:
            if lib_types.get(library_id) == "shows":
                out[root].append((row_id, ("show", show_id)))
        return out

    def save(
        self, films: list[str], shows: list[str], *, confirm_relinks: bool
    ) -> LibraryFolderSet:
        """Replace both lists (call under the scheduler lock). 422 ``invalid_library_folders``;
        409 ``relinks_unconfirmed`` unless confirmed. Nothing is written on error."""
        plan = self.plan(films, shows)
        if plan.has_relinks and not confirm_relinks:
            raise ApiError(
                409,
                "relinks_unconfirmed",
                "Relinks need confirmation",
                {
                    "moves": [asdict(mv) for mv in plan.moved],
                    "relayouts": [asdict(r) for r in plan.relayout],
                },
            )
        norm_films, norm_shows = _normalized(films), _normalized(shows)  # validated by plan()
        proposed: dict[str, LibraryType] = {p: "films" for p in norm_films}
        proposed.update({p: "shows" for p in norm_shows})
        from hometv.scanner import ensure_libraries

        with self.db.write_session() as session:
            ensure_libraries(session)
            libs = {lib.type: lib for lib in session.scalars(select(m.Library))}
            stored = {f.path: f for f in session.scalars(select(m.LibraryFolder))}
            for path, folder in stored.items():
                if path not in proposed:
                    session.delete(folder)
            session.flush()
            for path, library in proposed.items():
                existing = stored.get(path)
                if existing is None:
                    session.add(m.LibraryFolder(library_id=libs[library].id, path=path))
                elif existing.library_id != libs[library].id:
                    existing.library_id = libs[library].id  # moved: UPDATE, not delete + insert
            now = m.utcnow()
            for lib in libs.values():
                if lib.folders_seeded_at is None:
                    lib.folders_seeded_at = now
        logger.info("library_folders_saved films=%d shows=%d", len(norm_films), len(norm_shows))
        return self.load()

    # -- status / browse --

    def folder_status(
        self, session: Session
    ) -> tuple[list[LibraryFolderOut], list[LibraryFolderOut]]:
        """Stored folders with live status and present-title counts (two grouped queries)."""
        current = self.current()
        film_counts: dict[str, int] = {
            root: count
            for root, count in session.execute(
                select(m.MediaFile.root_path, func.count(func.distinct(m.MediaFile.film_id)))
                .join(m.Library, m.Library.id == m.MediaFile.library_id)
                .where(
                    m.Library.type == "films",
                    m.MediaFile.missing_since.is_(None),
                    m.MediaFile.film_id.is_not(None),
                )
                .group_by(m.MediaFile.root_path)
            )
        }
        show_counts: dict[str, int] = {
            root: count
            for root, count in session.execute(
                select(m.MediaFile.root_path, func.count(func.distinct(m.Episode.show_id)))
                .join(m.Library, m.Library.id == m.MediaFile.library_id)
                .join(m.MediaFileEpisode, m.MediaFileEpisode.media_file_id == m.MediaFile.id)
                .join(m.Episode, m.Episode.id == m.MediaFileEpisode.episode_id)
                .where(m.Library.type == "shows", m.MediaFile.missing_since.is_(None))
                .group_by(m.MediaFile.root_path)
            )
        }

        def out(paths: Iterable[str], counts: dict[str, int]) -> list[LibraryFolderOut]:
            return [
                LibraryFolderOut(
                    path=p, status=folder_status(p, self.fs), title_count=counts.get(p, 0)
                )
                for p in paths
            ]

        return out(current.films, film_counts), out(current.shows, show_counts)

    def describe(self, session: Session) -> LibrariesResponse:
        """``GET /api/admin/libraries`` body."""
        films, shows = self.folder_status(session)
        return LibrariesResponse(
            films=films,
            shows=shows,
            browse_root=self.browse_root(),
            ignored_env=self.ignored_env(),
            missing_grace_days=self.settings.scan.missing_grace_days,
        )

    def browse_root(self) -> BrowseRoot:
        base = self.settings.libraries.browse_root
        return BrowseRoot(path=base, available=folder_status(base, self.fs) == "available")

    def browse(self, path: str | None) -> BrowseResponse:
        if not self.browse_root().available:
            raise ApiError(409, "browse_unavailable", "Browse root is missing or unreadable")
        return browse_listing(self.settings.libraries.browse_root, path, fs=self.fs)
