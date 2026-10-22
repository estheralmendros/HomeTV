"""Catalog queries (PRD §9 home rows, §10): lists, details, search, genres, home.

Visibility: a film is visible iff it has a present media file (``missing_since IS NULL``); an
episode iff a present file is linked to it; a season/show iff it has a visible episode. Hidden
files never appear in ``media_files``. Counts (``season_count``, ``episode_count``,
``unwatched_episode_count`` of shows) exclude specials (season 0); per-season counts in
``ShowDetail.seasons`` cover that season (specials included for season 0).

Every builder works on batches (one query per kind of data for the whole page, never one per
item) so browse endpoints stay within the p95 budget (G3). Functions take a read ``Session``
and the profile id and return the pydantic response models of ``api/schemas.py``.
"""

import os
from collections import defaultdict
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Literal

from sqlalchemy import and_, case, exists, func, or_, select
from sqlalchemy.orm import Session

from hometv.api.errors import ApiError
from hometv.api.schemas import (
    AudioTrack,
    CastMember,
    EpisodeDetail,
    EpisodeSummary,
    FilmDetail,
    FilmSummary,
    GenreCount,
    GenreRef,
    HomeEpisode,
    HomeResponse,
    LibraryCounts,
    MediaFile,
    Page,
    Progress,
    SeasonDetail,
    ShowDetail,
    ShowSeason,
    ShowSummary,
    SubtitleTrack,
    VideoInfo,
)
from hometv.db import models as m
from hometv.metadata.artwork import image
from hometv.text import normalize_title
from hometv.watch import (
    EpisodeKey,
    EpisodeState,
    film_has_present_file,
    first_unwatched,
    is_in_progress,
    next_up,
    present_file_exists,
    progress_out,
)

UNKNOWN_LABEL = "Desconocido"  # Spanish (es-ES): shown to viewers (PRD §UI Language)
_HDR_LABELS = {"hdr10": "HDR10", "hlg": "HLG", "dolby_vision": "DV"}

SortKey = Literal["title", "year", "added"]
Order = Literal["asc", "desc"]


# --- MediaFile -----------------------------------------------------------------------------------


def _channels(channels: int | None) -> str | None:
    if not channels or channels < 1:
        return None
    return f"{channels}.0" if channels <= 2 else f"{channels - 1}.1"


def default_audio(audio: Sequence[dict[str, Any]]) -> dict[str, Any] | None:
    """The track a player picks by default: the default-flagged one, else the first."""
    return next((a for a in audio if a.get("default")), audio[0] if audio else None)


def media_label(probe: dict[str, Any] | None) -> str:
    """``{height}p {VIDEO_CODEC}[ {HDR}] · {AUDIO_CODEC} {channels}`` of the default audio
    track; ``"Desconocido"`` without a probe. Missing parts are omitted."""
    if not probe:
        return UNKNOWN_LABEL
    parts: list[str] = []
    video = probe.get("video")
    if video:
        words = [f"{video['height']}p"] if video.get("height") else []
        words.append(str(video.get("codec") or "").upper())
        if video.get("hdr") in _HDR_LABELS:
            words.append(_HDR_LABELS[video["hdr"]])
        parts.append(" ".join(w for w in words if w))
    audio = default_audio(probe.get("audio") or [])
    if audio:
        channels = _channels(audio.get("channels"))
        parts.append(" ".join(w for w in (str(audio.get("codec") or "").upper(), channels) if w))
    parts = [p for p in parts if p]
    return " · ".join(parts) if parts else UNKNOWN_LABEL


def _subtitle_url(media_file_id: int, track_id: str, kind: str) -> str | None:
    if kind != "text":
        return None
    return f"/api/media-files/{media_file_id}/subtitles/{track_id}.vtt"


def media_file_out(row: m.MediaFile, sidecars: Iterable[m.SubtitleFile] = ()) -> MediaFile:
    """MediaFile shape from stored probe data + sidecar rows (embedded tracks first, by stream
    index, then external tracks by id)."""
    probe = row.probe if row.probe_error is None else None
    probe = probe or {}
    video = probe.get("video")
    tracks: list[SubtitleTrack] = []
    for sub in probe.get("subtitles") or []:
        track_id = f"emb-{sub['index']}"
        tracks.append(
            SubtitleTrack(
                id=track_id,
                source="embedded",
                kind=sub.get("kind", "image"),
                codec=sub.get("codec", ""),
                language=sub.get("language") or "und",
                title=sub.get("title"),
                forced=bool(sub.get("forced")),
                hearing_impaired=bool(sub.get("hearing_impaired")),
                default=bool(sub.get("default")),
                url=_subtitle_url(row.id, track_id, sub.get("kind", "image")),
            )
        )
    for sidecar in sorted(sidecars, key=lambda x: x.id):
        track_id = f"ext-{sidecar.id}"
        tracks.append(
            SubtitleTrack(
                id=track_id,
                source="external",
                kind=sidecar.kind,  # type: ignore[arg-type]
                codec=sidecar.format,
                language=sidecar.language,
                title=None,
                forced=sidecar.forced,
                hearing_impaired=sidecar.hearing_impaired,
                default=False,
                url=_subtitle_url(row.id, track_id, sidecar.kind),
            )
        )
    return MediaFile(
        id=row.id,
        file_name=os.path.basename(row.relative_path),
        size_bytes=row.size_bytes,
        container=row.container,
        duration_seconds=row.duration_seconds,
        label=media_label(probe or None),
        video=VideoInfo(
            codec=video["codec"],
            width=video.get("width"),
            height=video.get("height"),
            hdr=video.get("hdr"),
            frame_rate=video.get("frame_rate"),
        )
        if video
        else None,
        audio_tracks=[AudioTrack.model_validate(a) for a in probe.get("audio") or []],
        subtitle_tracks=tracks,
        part_number=row.part_number,
        probe_error=row.probe_error,
        added_at=row.added_at,
    )


def _version_order(include_part: bool) -> list[Any]:
    """Rev 8 total order: [part_number ASC NULLS FIRST,] height DESC NULLS LAST, size DESC, id."""
    order: list[Any] = []
    if include_part:
        order += [m.MediaFile.part_number.is_not(None), m.MediaFile.part_number]
    return [
        *order,
        m.MediaFile.height.is_(None),
        m.MediaFile.height.desc(),
        m.MediaFile.size_bytes.desc(),
        m.MediaFile.id,
    ]


def _sidecars(session: Session, file_ids: Sequence[int]) -> dict[int, list[m.SubtitleFile]]:
    result: dict[int, list[m.SubtitleFile]] = defaultdict(list)
    if file_ids:
        for sub in session.scalars(
            select(m.SubtitleFile).where(m.SubtitleFile.media_file_id.in_(file_ids))
        ):
            result[sub.media_file_id].append(sub)
    return result


def film_media_files(session: Session, film_id: int) -> list[MediaFile]:
    rows = session.scalars(
        select(m.MediaFile)
        .where(m.MediaFile.film_id == film_id, m.MediaFile.missing_since.is_(None))
        .order_by(*_version_order(include_part=True))
    ).all()
    subs = _sidecars(session, [r.id for r in rows])
    return [media_file_out(r, subs.get(r.id, ())) for r in rows]


def episode_media_files(session: Session, episode_ids: Sequence[int]) -> dict[int, list[MediaFile]]:
    result: dict[int, list[MediaFile]] = defaultdict(list)
    if not episode_ids:
        return result
    rows = [
        (episode_id, row)
        for episode_id, row in session.execute(
            select(m.MediaFileEpisode.episode_id, m.MediaFile)
            .join(m.MediaFile, m.MediaFile.id == m.MediaFileEpisode.media_file_id)
            .where(m.MediaFileEpisode.episode_id.in_(episode_ids))
            .order_by(*_version_order(include_part=False))
        )
        if row.missing_since is None  # filtered here, see _episode_durations
    ]
    subs = _sidecars(session, list({r.id for _, r in rows}))
    built: dict[int, MediaFile] = {}
    for episode_id, row in rows:
        if row.id not in built:
            built[row.id] = media_file_out(row, subs.get(row.id, ()))
        result[episode_id].append(built[row.id])
    return result


# --- Progress / visibility helpers ---------------------------------------------------------------


def _progress_rows(
    session: Session, profile_id: int, *, film_ids: Sequence[int] = (), episode_ids=()
) -> dict[int, m.PlaybackProgress]:
    if film_ids:
        column, ids = m.PlaybackProgress.film_id, film_ids
    elif episode_ids:
        column, ids = m.PlaybackProgress.episode_id, episode_ids
    else:
        return {}
    rows = session.scalars(
        select(m.PlaybackProgress).where(
            m.PlaybackProgress.profile_id == profile_id, column.in_(ids)
        )
    )
    return {(r.film_id if film_ids else r.episode_id): r for r in rows}  # type: ignore[misc]


def _progress_map(session: Session, profile_id: int, **kw: Any) -> dict[int, Progress | None]:
    return {k: progress_out(v) for k, v in _progress_rows(session, profile_id, **kw).items()}


def _show_visible() -> Any:
    episode = select(m.Episode.id).where(m.Episode.show_id == m.Show.id, present_file_exists())
    return exists(episode)


def _season_visible() -> Any:
    episode = select(m.Episode.id).where(m.Episode.season_id == m.Season.id, present_file_exists())
    return exists(episode)


# --- Summaries -----------------------------------------------------------------------------------


def film_summaries(session: Session, profile_id: int, films: Sequence[m.Film]) -> list[FilmSummary]:
    progress = _progress_map(session, profile_id, film_ids=[f.id for f in films])
    return [
        FilmSummary(
            id=f.id,
            title=f.title,
            year=f.year,
            poster=image("poster", f.poster_path),  # type: ignore[arg-type]
            backdrop=image("backdrop", f.backdrop_path),  # type: ignore[arg-type]
            overview=f.overview,
            match_status=f.match_status,
            added_at=f.added_at,
            progress=progress.get(f.id),
        )
        for f in films
    ]


@dataclass(frozen=True)
class ShowCounts:
    season_count: int = 0
    episode_count: int = 0
    unwatched_episode_count: int = 0


def _watched_flag(profile_id: int) -> Any:
    return (
        select(m.PlaybackProgress.watched)
        .where(
            m.PlaybackProgress.profile_id == profile_id,
            m.PlaybackProgress.episode_id == m.Episode.id,
        )
        .scalar_subquery()
    )


def show_counts(
    session: Session, profile_id: int, show_ids: Sequence[int]
) -> dict[int, ShowCounts]:
    if not show_ids:
        return {}
    watched = func.coalesce(_watched_flag(profile_id), False)
    rows = session.execute(
        select(
            m.Episode.show_id,
            func.count(func.distinct(m.Episode.season_number)),
            func.count(m.Episode.id),
            func.sum(case((watched, 0), else_=1)),
        )
        .where(m.Episode.show_id.in_(show_ids), m.Episode.season_number > 0, present_file_exists())
        .group_by(m.Episode.show_id)
    ).all()
    return {
        sid: ShowCounts(seasons, eps, int(unwatched or 0)) for sid, seasons, eps, unwatched in rows
    }


def show_summaries(session: Session, profile_id: int, shows: Sequence[m.Show]) -> list[ShowSummary]:
    counts = show_counts(session, profile_id, [s.id for s in shows])
    out = []
    for s in shows:
        c = counts.get(s.id, ShowCounts())
        out.append(
            ShowSummary(
                id=s.id,
                title=s.title,
                year=s.year,
                poster=image("poster", s.poster_path),  # type: ignore[arg-type]
                backdrop=image("backdrop", s.backdrop_path),  # type: ignore[arg-type]
                overview=s.overview,
                match_status=s.match_status,
                added_at=s.added_at,
                season_count=c.season_count,
                episode_count=c.episode_count,
                unwatched_episode_count=c.unwatched_episode_count,
            )
        )
    return out


def _episode_durations(session: Session, episode_ids: Sequence[int]) -> dict[int, float | None]:
    """Duration of each episode's first version (Rev 8 order)."""
    result: dict[int, float | None] = {}
    if not episode_ids:
        return result
    # ``missing_since`` is filtered in Python: with ``missing_since IS NULL`` in the WHERE
    # clause SQLite drives the query from ix_media_file_missing_since (every present file)
    # instead of ``episode_id IN (...)`` (perf test: 25-45 ms -> < 1 ms).
    rows = session.execute(
        select(
            m.MediaFileEpisode.episode_id, m.MediaFile.duration_seconds, m.MediaFile.missing_since
        )
        .join(m.MediaFile, m.MediaFile.id == m.MediaFileEpisode.media_file_id)
        .where(m.MediaFileEpisode.episode_id.in_(episode_ids))
        .order_by(*_version_order(include_part=False))
    )
    for episode_id, duration, missing_since in rows:
        if missing_since is None:
            result.setdefault(episode_id, duration)
    return result


def episode_summaries(
    session: Session, profile_id: int, episodes: Sequence[m.Episode]
) -> list[EpisodeSummary]:
    ids = [e.id for e in episodes]
    show_ids = list({e.show_id for e in episodes})
    shows = (
        {s.id: s for s in session.scalars(select(m.Show).where(m.Show.id.in_(show_ids)))}
        if show_ids
        else {}
    )
    progress = _progress_map(session, profile_id, episode_ids=ids)
    durations = _episode_durations(session, ids)
    out = []
    for e in episodes:
        show = shows[e.show_id]
        out.append(
            EpisodeSummary(
                id=e.id,
                show_id=e.show_id,
                show_title=show.title,
                season_id=e.season_id,
                season_number=e.season_number,
                episode_number=e.episode_number,
                title=e.title,
                overview=e.overview,
                still=image("still", e.still_path),  # type: ignore[arg-type]
                air_date=e.air_date,
                runtime_minutes=e.runtime_minutes,
                duration_seconds=durations.get(e.id),
                show_poster=image("poster", show.poster_path),  # type: ignore[arg-type]
                show_backdrop=image("backdrop", show.backdrop_path),  # type: ignore[arg-type]
                progress=progress.get(e.id),
            )
        )
    return out


def home_episodes(
    session: Session, profile_id: int, episodes: Sequence[m.Episode]
) -> list[HomeEpisode]:
    files = episode_media_files(session, [e.id for e in episodes])
    return [
        HomeEpisode(**summary.model_dump(), media_files=files.get(summary.id, []))
        for summary in episode_summaries(session, profile_id, episodes)
    ]


# --- Lists ---------------------------------------------------------------------------------------


@dataclass(frozen=True)
class ListParams:
    q: str | None = None
    sort: SortKey = "title"
    order: Order = "asc"
    year_from: int | None = None
    year_to: int | None = None
    genre: int | None = None
    page: int = 1
    page_size: int = 50


def _filtered(model: Any, genre_model: Any, genre_fk: Any, params: ListParams) -> list[Any]:
    conds: list[Any] = [film_has_present_file() if model is m.Film else _show_visible()]
    if params.q is not None:
        conds.append(model.search_text.like(f"%{normalize_title(params.q)}%"))
    if params.year_from is not None or params.year_to is not None:
        conds.append(model.year.is_not(None))
    if params.year_from is not None:
        conds.append(model.year >= params.year_from)
    if params.year_to is not None:
        conds.append(model.year <= params.year_to)
    if params.genre is not None:
        conds.append(exists().where(genre_fk == model.id, genre_model.genre_id == params.genre))
    return conds


def _ordering(model: Any, params: ListParams) -> list[Any]:
    desc = params.order == "desc"
    if params.sort == "year":
        column = model.year
        return [column.is_(None), column.desc() if desc else column.asc(), model.id]
    if params.sort == "added":
        column = model.added_at if model is m.Film else model.content_updated_at
    else:
        column = model.sort_title
    return [column.desc() if desc else column.asc(), model.id]


def _page(session: Session, model: Any, conds: list[Any], params: ListParams) -> tuple[list, int]:
    total = session.scalar(select(func.count(model.id)).where(*conds)) or 0
    rows = session.scalars(
        select(model)
        .where(*conds)
        .order_by(*_ordering(model, params))
        .offset((params.page - 1) * params.page_size)
        .limit(params.page_size)
    ).all()
    return list(rows), total


def list_films(session: Session, profile_id: int, params: ListParams) -> Page[FilmSummary]:
    conds = _filtered(m.Film, m.FilmGenre, m.FilmGenre.film_id, params)
    films, total = _page(session, m.Film, conds, params)
    return Page[FilmSummary](
        items=film_summaries(session, profile_id, films),
        page=params.page,
        page_size=params.page_size,
        total=total,
    )


def list_shows(session: Session, profile_id: int, params: ListParams) -> Page[ShowSummary]:
    conds = _filtered(m.Show, m.ShowGenre, m.ShowGenre.show_id, params)
    shows, total = _page(session, m.Show, conds, params)
    return Page[ShowSummary](
        items=show_summaries(session, profile_id, shows),
        page=params.page,
        page_size=params.page_size,
        total=total,
    )


# --- Details -------------------------------------------------------------------------------------


def _genres(session: Session, link: Any, fk: Any, item_id: int) -> list[GenreRef]:
    rows = session.execute(
        select(m.Genre.id, m.Genre.name)
        .join(link, link.genre_id == m.Genre.id)
        .where(fk == item_id)
        .order_by(m.Genre.name, m.Genre.id)
    )
    return [GenreRef(id=gid, name=name) for gid, name in rows]


def _cast(session: Session, fk: Any, item_id: int, limit: int) -> list[CastMember]:
    rows = session.execute(
        select(m.CastCredit.character, m.Person)
        .join(m.Person, m.Person.id == m.CastCredit.person_id)
        .where(fk == item_id)
        .order_by(m.CastCredit.order, m.CastCredit.id)
        .limit(limit)
    )
    return [
        CastMember(
            person_id=person.id,
            name=person.name,
            character=character,
            profile_image=image("profile", person.profile_path),  # type: ignore[arg-type]
        )
        for character, person in rows
    ]


def _not_found(kind: str, item_id: Any) -> ApiError:
    return ApiError(404, "not_found", f"{kind} {item_id} not found")


def film_detail(session: Session, profile_id: int, film_id: int, cast_limit: int) -> FilmDetail:
    film = session.scalar(select(m.Film).where(m.Film.id == film_id, film_has_present_file()))
    if film is None:
        raise _not_found("Film", film_id)
    progress = _progress_map(session, profile_id, film_ids=[film.id])
    return FilmDetail(
        id=film.id,
        title=film.title,
        original_title=film.original_title,
        year=film.year,
        release_date=film.release_date,
        overview=film.overview,
        tagline=film.tagline,
        runtime_minutes=film.runtime_minutes,
        vote_average=film.vote_average,
        genres=_genres(session, m.FilmGenre, m.FilmGenre.film_id, film.id),
        poster=image("poster", film.poster_path),  # type: ignore[arg-type]
        backdrop=image("backdrop", film.backdrop_path),  # type: ignore[arg-type]
        match_status=film.match_status,
        tmdb_id=film.tmdb_id,
        cast=_cast(session, m.CastCredit.film_id, film.id, cast_limit),
        media_files=film_media_files(session, film.id),
        progress=progress.get(film.id),
        added_at=film.added_at,
    )


def _visible_episodes(session: Session, *conds: Any) -> list[m.Episode]:
    return list(
        session.scalars(
            select(m.Episode)
            .where(present_file_exists(), *conds)
            .order_by(m.Episode.season_number, m.Episode.episode_number, m.Episode.id)
        )
    )


def _episode_states(
    rows: dict[int, m.PlaybackProgress],
) -> dict[int, EpisodeState]:
    return {k: EpisodeState(r.watched, r.position_seconds) for k, r in rows.items()}


def _keys(episodes: Iterable[m.Episode]) -> list[EpisodeKey]:
    return [EpisodeKey(e.id, e.season_number, e.episode_number) for e in episodes]


def show_next_episode_id(
    episodes: Sequence[m.Episode], rows: dict[int, m.PlaybackProgress]
) -> int | None:
    """In-progress episode (latest updated), else the Next Up rule, else first unwatched."""
    in_progress = [r for r in rows.values() if is_in_progress(r.watched, r.position_seconds)]
    if in_progress:
        return max(in_progress, key=lambda r: (r.updated_at, r.id)).episode_id
    keys, states = _keys(episodes), _episode_states(rows)
    return next_up(keys, states) or first_unwatched(keys, states)


def show_detail(session: Session, profile_id: int, show_id: int, cast_limit: int) -> ShowDetail:
    show = session.scalar(select(m.Show).where(m.Show.id == show_id, _show_visible()))
    if show is None:
        raise _not_found("Show", show_id)
    episodes = _visible_episodes(session, m.Episode.show_id == show.id)
    rows = _progress_rows(session, profile_id, episode_ids=[e.id for e in episodes])
    watched = {k for k, r in rows.items() if r.watched}
    per_season: dict[int, list[m.Episode]] = defaultdict(list)
    for e in episodes:
        per_season[e.season_id].append(e)
    seasons_rows = session.scalars(select(m.Season).where(m.Season.id.in_(list(per_season)))).all()
    seasons_rows = sorted(seasons_rows, key=lambda s: (s.season_number == 0, s.season_number))
    seasons = [
        ShowSeason(
            id=s.id,
            season_number=s.season_number,
            name=s.name,
            poster=image("poster", s.poster_path),  # type: ignore[arg-type]
            air_date=s.air_date,
            episode_count=len(per_season[s.id]),
            unwatched_episode_count=sum(1 for e in per_season[s.id] if e.id not in watched),
        )
        for s in seasons_rows
    ]
    next_id = show_next_episode_id(episodes, rows)
    next_episode = None
    if next_id is not None:
        [next_episode] = episode_summaries(
            session, profile_id, [e for e in episodes if e.id == next_id]
        )
    return ShowDetail(
        id=show.id,
        title=show.title,
        original_title=show.original_title,
        year=show.year,
        first_air_date=show.first_air_date,
        status=show.status,
        overview=show.overview,
        vote_average=show.vote_average,
        genres=_genres(session, m.ShowGenre, m.ShowGenre.show_id, show.id),
        poster=image("poster", show.poster_path),  # type: ignore[arg-type]
        backdrop=image("backdrop", show.backdrop_path),  # type: ignore[arg-type]
        match_status=show.match_status,
        tmdb_id=show.tmdb_id,
        cast=_cast(session, m.CastCredit.show_id, show.id, cast_limit),
        seasons=seasons,
        next_episode=next_episode,
        unwatched_episode_count=sum(
            1 for e in episodes if e.season_number > 0 and e.id not in watched
        ),
        added_at=show.added_at,
    )


def season_detail(
    session: Session, profile_id: int, show_id: int, season_number: int
) -> SeasonDetail:
    row = session.execute(
        select(m.Season, m.Show)
        .join(m.Show, m.Show.id == m.Season.show_id)
        .where(
            m.Season.show_id == show_id,
            m.Season.season_number == season_number,
            _season_visible(),
        )
    ).first()
    if row is None:
        raise _not_found("Season", f"{show_id}/{season_number}")
    season, show = row
    episodes = _visible_episodes(session, m.Episode.season_id == season.id)
    return SeasonDetail(
        id=season.id,
        show_id=show.id,
        show_title=show.title,
        season_number=season.season_number,
        name=season.name,
        overview=season.overview,
        poster=image("poster", season.poster_path),  # type: ignore[arg-type]
        air_date=season.air_date,
        episodes=episode_summaries(session, profile_id, episodes),
    )


def episode_detail(session: Session, profile_id: int, episode_id: int) -> EpisodeDetail:
    episode = session.scalar(
        select(m.Episode).where(m.Episode.id == episode_id, present_file_exists())
    )
    if episode is None:
        raise _not_found("Episode", episode_id)
    conds = [m.Episode.show_id == episode.show_id]
    if episode.season_number > 0:
        conds.append(m.Episode.season_number > 0)
    order = [e.id for e in _visible_episodes(session, *conds)]
    index = order.index(episode.id)
    [summary] = episode_summaries(session, profile_id, [episode])
    return EpisodeDetail(
        **summary.model_dump(),
        media_files=episode_media_files(session, [episode.id]).get(episode.id, []),
        previous_episode_id=order[index - 1] if index > 0 else None,
        next_episode_id=order[index + 1] if index + 1 < len(order) else None,
    )


# --- Search / genres -----------------------------------------------------------------------------


def search(session: Session, profile_id: int, q: str, limit: int) -> tuple[list, list]:
    pattern = f"%{normalize_title(q)}%"
    films = session.scalars(
        select(m.Film)
        .where(film_has_present_file(), m.Film.search_text.like(pattern))
        .order_by(m.Film.sort_title, m.Film.id)
        .limit(limit)
    ).all()
    shows = session.scalars(
        select(m.Show)
        .where(_show_visible(), m.Show.search_text.like(pattern))
        .order_by(m.Show.sort_title, m.Show.id)
        .limit(limit)
    ).all()
    return film_summaries(session, profile_id, films), show_summaries(session, profile_id, shows)


def genres(session: Session, library: Literal["films", "shows"]) -> list[GenreCount]:
    if library == "films":
        link, fk, model, visible = m.FilmGenre, m.FilmGenre.film_id, m.Film, film_has_present_file()
    else:
        link, fk, model, visible = m.ShowGenre, m.ShowGenre.show_id, m.Show, _show_visible()
    rows = session.execute(
        select(m.Genre.id, m.Genre.name, func.count(func.distinct(model.id)))
        .join(link, link.genre_id == m.Genre.id)
        .join(model, model.id == fk)
        .where(visible)
        .group_by(m.Genre.id, m.Genre.name)
        .order_by(m.Genre.name, m.Genre.id)
    )
    return [GenreCount(id=gid, name=name, count=count) for gid, name, count in rows]


# --- Home ----------------------------------------------------------------------------------------


def _continue_watching(session: Session, profile_id: int, limit: int) -> list[Any]:
    film_visible = exists().where(
        m.MediaFile.film_id == m.PlaybackProgress.film_id, m.MediaFile.missing_since.is_(None)
    )
    episode_visible = exists().where(
        m.MediaFileEpisode.episode_id == m.PlaybackProgress.episode_id,
        m.MediaFile.id == m.MediaFileEpisode.media_file_id,
        m.MediaFile.missing_since.is_(None),
    )
    rows = session.execute(
        select(m.PlaybackProgress.film_id, m.PlaybackProgress.episode_id)
        .where(
            m.PlaybackProgress.profile_id == profile_id,
            m.PlaybackProgress.watched.is_(False),
            m.PlaybackProgress.position_seconds >= 30,
            or_(
                and_(m.PlaybackProgress.film_id.is_not(None), film_visible),
                and_(m.PlaybackProgress.episode_id.is_not(None), episode_visible),
            ),
        )
        .order_by(m.PlaybackProgress.updated_at.desc(), m.PlaybackProgress.id.desc())
        .limit(limit)
    ).all()
    film_ids = [f for f, _ in rows if f is not None]
    episode_ids = [e for _, e in rows if e is not None]
    films = {f.id: f for f in session.scalars(select(m.Film).where(m.Film.id.in_(film_ids)))}
    episodes = list(session.scalars(select(m.Episode).where(m.Episode.id.in_(episode_ids))))
    film_out = {s.id: s for s in film_summaries(session, profile_id, list(films.values()))}
    episode_out = {s.id: s for s in home_episodes(session, profile_id, episodes)}
    return [film_out[f] if f is not None else episode_out[e] for f, e in rows]


def _next_up(session: Session, profile_id: int, limit: int) -> list[HomeEpisode]:
    latest = session.execute(
        select(m.Episode.show_id, func.max(m.PlaybackProgress.watched_at))
        .join(m.PlaybackProgress, m.PlaybackProgress.episode_id == m.Episode.id)
        .where(
            m.PlaybackProgress.profile_id == profile_id,
            m.PlaybackProgress.watched.is_(True),
            m.Episode.season_number > 0,
            present_file_exists(),
        )
        .group_by(m.Episode.show_id)
    ).all()
    if not latest:
        return []
    shows = sorted(latest, key=lambda r: (r[1] or datetime.min, r[0]), reverse=True)
    show_ids = [sid for sid, _ in shows]
    episodes = _visible_episodes(
        session, m.Episode.show_id.in_(show_ids), m.Episode.season_number > 0
    )
    by_show: dict[int, list[m.Episode]] = defaultdict(list)
    for e in episodes:
        by_show[e.show_id].append(e)
    states = _episode_states(
        _progress_rows(session, profile_id, episode_ids=[e.id for e in episodes])
    )
    by_id = {e.id: e for e in episodes}
    chosen: list[m.Episode] = []
    for show_id in show_ids:
        next_id = next_up(_keys(by_show[show_id]), states)
        if next_id is not None:
            chosen.append(by_id[next_id])
            if len(chosen) >= limit:
                break
    return home_episodes(session, profile_id, chosen)


def _recently_added(session: Session, profile_id: int, limit: int) -> list[Any]:
    films = session.scalars(
        select(m.Film)
        .where(film_has_present_file())
        .order_by(m.Film.added_at.desc(), m.Film.id.desc())
        .limit(limit)
    ).all()
    shows = session.scalars(
        select(m.Show)
        .where(_show_visible())
        .order_by(m.Show.content_updated_at.desc(), m.Show.id.desc())
        .limit(limit)
    ).all()
    merged: list[tuple[datetime, int, Any]] = [(f.added_at, 0, f) for f in films]
    merged += [(s.content_updated_at, 1, s) for s in shows]
    merged.sort(key=lambda x: (x[0], x[1], x[2].id), reverse=True)
    picked = merged[:limit]
    film_out = {
        s.id: s for s in film_summaries(session, profile_id, [x for _, k, x in picked if k == 0])
    }
    show_out = {
        s.id: s for s in show_summaries(session, profile_id, [x for _, k, x in picked if k == 1])
    }
    return [film_out[x.id] if k == 0 else show_out[x.id] for _, k, x in picked]


def library_counts(session: Session) -> LibraryCounts:
    films = session.scalar(select(func.count(m.Film.id)).where(film_has_present_file())) or 0
    shows = session.scalar(select(func.count(m.Show.id)).where(_show_visible())) or 0
    return LibraryCounts(films=films, shows=shows, empty=films + shows == 0)


def home(session: Session, profile_id: int, limit: int) -> HomeResponse:
    return HomeResponse(
        continue_watching=_continue_watching(session, profile_id, limit),
        next_up=_next_up(session, profile_id, limit),
        recently_added=_recently_added(session, profile_id, limit),
        library=library_counts(session),
    )
