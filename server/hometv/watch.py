"""Watch state (PRD §9).

Pure rules (table-tested in ``tests/unit/test_watch_rules.py``): ``apply_progress_rule`` with
``watched_ratio`` (films 95 %, episodes 90 %), ``is_last_part`` (multi-part films),
``is_in_progress`` (unwatched and >= 30 s), ``next_up`` and ``first_unwatched`` over in-memory
episode lists.

DB operations (one short write transaction each): ``report_progress`` (``PUT /api/progress``)
and ``mark_watched`` (``POST /api/watched``). "Library episodes" are episodes with at least one
present media file (``missing_since IS NULL``), the same visibility rule as the catalog. Both
answer ``rating_prompt`` (ratings FR-8): the film or show whose finishing item (the film, or the
show's last present episode) the request just watched, while the profile has neither rated nor
dismissed it; computed in the same transaction for at most 3 extra indexed statements, none
below the rule. A profile deleted between auth and the write answers 401 (``fk_to_unauthorized``).
"""

from collections.abc import Collection, Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from sqlalchemy import and_, exists, not_, select
from sqlalchemy.orm import Session

from hometv.api.errors import ApiError, fk_to_unauthorized
from hometv.api.schemas import (
    ItemKind,
    Progress,
    ProgressReport,
    ProgressRequest,
    RatingPrompt,
    WatchedResponse,
)
from hometv.db import models as m
from hometv.db.engine import Database
from hometv.metadata.artwork import image

FILM_WATCHED_RATIO = 0.95
EPISODE_WATCHED_RATIO = 0.9
IN_PROGRESS_MIN_SECONDS = 30.0

# --- Pure rules ----------------------------------------------------------------------------------


@dataclass(frozen=True)
class ProgressOutcome:
    reaches_watched: bool
    position_seconds: float


def watched_ratio(item_type: str) -> float:
    """Share of the duration that marks an item watched: films 95 %, episodes 90 %."""
    return FILM_WATCHED_RATIO if item_type == "film" else EPISODE_WATCHED_RATIO


def apply_progress_rule(
    position: float, duration: float, *, ratio: float, rule_applies: bool
) -> ProgressOutcome:
    """``position >= ratio x duration`` marks watched and resets the position (only when the rule
    applies: not on a non-final part). Otherwise the position is stored as reported."""
    if rule_applies and position >= ratio * duration:
        return ProgressOutcome(True, 0.0)
    return ProgressOutcome(False, position)


def is_last_part(part_number: int | None, present_parts: Iterable[int]) -> bool:
    """A version without ``part_number`` follows the normal rule; a part is "last" when it is
    the highest present ``part_number`` of the film."""
    if part_number is None:
        return True
    return part_number >= max(present_parts, default=part_number)


def is_in_progress(watched: bool, position_seconds: float) -> bool:
    return not watched and position_seconds >= IN_PROGRESS_MIN_SECONDS


@dataclass(frozen=True)
class EpisodeKey:
    id: int
    season_number: int
    episode_number: int


@dataclass(frozen=True)
class EpisodeState:
    watched: bool
    position_seconds: float


def _ordered(episodes: Iterable[EpisodeKey]) -> list[EpisodeKey]:
    return sorted(
        (e for e in episodes if e.season_number > 0),
        key=lambda e: (e.season_number, e.episode_number),
    )


def next_up(episodes: Iterable[EpisodeKey], state: Mapping[int, EpisodeState]) -> int | None:
    """Next Up (§9): after the highest watched non-special episode L, the first library episode
    with (s, e) > L, s > 0, not watched; ``None`` if there is none or it is in progress."""
    ordered = _ordered(episodes)
    watched_idx = [i for i, e in enumerate(ordered) if (st := state.get(e.id)) and st.watched]
    if not watched_idx:
        return None
    for episode in ordered[watched_idx[-1] + 1 :]:
        st = state.get(episode.id)
        if st is not None and st.watched:
            continue
        if st is not None and is_in_progress(st.watched, st.position_seconds):
            return None
        return episode.id
    return None


def first_unwatched(
    episodes: Iterable[EpisodeKey], state: Mapping[int, EpisodeState]
) -> int | None:
    for episode in _ordered(episodes):
        st = state.get(episode.id)
        if st is None or not st.watched:
            return episode.id
    return None


def last_episode(episodes: Iterable[EpisodeKey]) -> int | None:
    """The show's "last episode in the catalog": highest (season, episode) among the given
    (present) episodes with season > 0; when there is none, the highest special (season 0).

    Season 0 sorts below every regular season, so both cases are the maximum
    ``(season_number, episode_number)``: ``_show_last_episode_id`` is the SQL twin. This is the
    executable spec of that rule, kept as the oracle the twin is tested against (production
    answers with the one ``LIMIT 1`` query instead of loading every episode)."""
    keys = list(episodes)
    pool = [e for e in keys if e.season_number > 0] or [e for e in keys if e.season_number == 0]
    if not pool:
        return None
    return max(pool, key=lambda e: (e.season_number, e.episode_number, e.id)).id


def is_title_finished(
    kind: ItemKind,
    *,
    film_watched: bool = False,
    last_episode_id: int | None = None,
    watched_episode_ids: Collection[int] = (),
) -> bool:
    """ "Finished" (ratings FR-7): a film is finished when watched for the profile (playback past
    the rule on its last part, or marked); a show when its last present episode is watched."""
    if kind == "film":
        return film_watched
    return last_episode_id is not None and last_episode_id in watched_episode_ids


# --- Shared query helpers ------------------------------------------------------------------------


def present_file_exists() -> Any:
    """EXISTS(present media file linked to the correlated ``m.Episode``)."""
    return exists().where(
        m.MediaFileEpisode.episode_id == m.Episode.id,
        m.MediaFile.id == m.MediaFileEpisode.media_file_id,
        m.MediaFile.missing_since.is_(None),
    )


def film_has_present_file() -> Any:
    """EXISTS(present media file of the correlated ``m.Film``)."""
    return exists().where(m.MediaFile.film_id == m.Film.id, m.MediaFile.missing_since.is_(None))


def _progress_fields(row: m.PlaybackProgress) -> dict[str, Any]:
    """The ``Progress`` fields of a row (shared by ``Progress`` and ``ProgressReport``)."""
    return {
        "position_seconds": row.position_seconds,
        "duration_seconds": row.duration_seconds,
        "watched": row.watched,
        "watched_at": row.watched_at,
        "media_file_id": row.media_file_id,
        "updated_at": row.updated_at,
    }


def progress_out(row: m.PlaybackProgress | None) -> Progress | None:
    if row is None:
        return None
    return Progress(**_progress_fields(row))


def _get_row(
    session: Session, profile_id: int, *, film_id: int | None = None, episode_id: int | None = None
) -> m.PlaybackProgress | None:
    column = m.PlaybackProgress.film_id if film_id is not None else m.PlaybackProgress.episode_id
    return session.scalar(
        select(m.PlaybackProgress).where(
            m.PlaybackProgress.profile_id == profile_id,
            column == (film_id if film_id is not None else episode_id),
        )
    )


def _row_for(
    session: Session, profile_id: int, *, film_id: int | None = None, episode_id: int | None = None
) -> m.PlaybackProgress:
    row = _get_row(session, profile_id, film_id=film_id, episode_id=episode_id)
    if row is None:
        row = m.PlaybackProgress(profile_id=profile_id, film_id=film_id, episode_id=episode_id)
        session.add(row)
    return row


def _set_watched(row: m.PlaybackProgress, now: datetime) -> None:
    row.watched = True
    row.watched_at = now
    row.position_seconds = 0.0
    row.updated_at = now


# --- PUT /api/progress ---------------------------------------------------------------------------


def report_progress(db: Database, profile_id: int, req: ProgressRequest) -> ProgressReport:
    with fk_to_unauthorized(), db.write_session() as session:
        media_file = session.get(m.MediaFile, req.media_file_id)
        film: m.Film | None = None
        episode: m.Episode | None = None
        if req.item_type == "film":
            film = session.get(m.Film, req.item_id)
            if film is None:
                raise ApiError(404, "not_found", f"Film {req.item_id} not found")
            linked = media_file is not None and media_file.film_id == req.item_id
        else:
            episode = session.get(m.Episode, req.item_id)
            if episode is None:
                raise ApiError(404, "not_found", f"Episode {req.item_id} not found")
            linked = (
                media_file is not None
                and session.get(m.MediaFileEpisode, (media_file.id, req.item_id)) is not None
            )
        if media_file is None:
            raise ApiError(404, "not_found", f"Media file {req.media_file_id} not found")
        if not linked:
            raise ApiError(
                422, "media_file_not_linked", "The media file is not a version of this item"
            )
        duration = media_file.duration_seconds or req.duration_seconds
        if not duration:
            raise ApiError(
                422,
                "validation_error",
                "duration_seconds is required when the file duration is unknown",
                {"errors": [{"loc": ["body", "duration_seconds"], "msg": "required"}]},
            )

        rule_applies = True
        if req.item_type == "film" and media_file.part_number is not None:
            parts = session.scalars(
                select(m.MediaFile.part_number).where(
                    m.MediaFile.film_id == req.item_id,
                    m.MediaFile.missing_since.is_(None),
                    m.MediaFile.part_number.is_not(None),
                )
            ).all()
            rule_applies = is_last_part(media_file.part_number, [p for p in parts if p])
        outcome = apply_progress_rule(
            req.position_seconds,
            duration,
            ratio=watched_ratio(req.item_type),
            rule_applies=rule_applies,
        )

        now = m.utcnow()
        key = {"film_id": req.item_id} if req.item_type == "film" else {"episode_id": req.item_id}
        row = _row_for(session, profile_id, **key)
        row.media_file_id = media_file.id
        row.duration_seconds = float(duration)
        row.updated_at = now
        prompt = None
        if outcome.reaches_watched:
            _set_watched(row, now)
            if film is not None:
                prompt = _rating_prompt(session, profile_id, "film", req.item_id, film)
            elif episode is not None:
                marked = [
                    req.item_id,
                    *_mark_siblings_watched(session, profile_id, media_file.id, req.item_id, now),
                ]
                prompt = _show_prompt_if_finished(session, profile_id, episode.show_id, marked)
        else:
            row.position_seconds = outcome.position_seconds
        session.flush()
        return ProgressReport(**_progress_fields(row), rating_prompt=prompt)


def _mark_siblings_watched(
    session: Session, profile_id: int, media_file_id: int, episode_id: int, now: datetime
) -> list[int]:
    """Multi-episode file reaching watched: every episode linked to the file is watched.
    Returns the sibling episode ids it marked."""
    siblings = session.scalars(
        select(m.MediaFileEpisode.episode_id).where(
            m.MediaFileEpisode.media_file_id == media_file_id,
            m.MediaFileEpisode.episode_id != episode_id,
        )
    ).all()
    for sibling in siblings:
        row = _row_for(session, profile_id, episode_id=sibling)
        if row.media_file_id is None:
            row.media_file_id = media_file_id
        _set_watched(row, now)
    return list(siblings)


# --- Rating prompt (ratings FR-8) ----------------------------------------------------------------


def _show_last_episode_id(session: Session, show_id: int) -> int | None:
    """SQL twin of ``last_episode`` over the show's present episodes: one ``LIMIT 1`` on
    ``ix_episode_show_id_season_number_episode_number`` (specials sort below regular seasons)."""
    return session.scalar(
        select(m.Episode.id)
        .where(m.Episode.show_id == show_id, present_file_exists())
        .order_by(m.Episode.season_number.desc(), m.Episode.episode_number.desc())
        .limit(1)
    )


def _not_rated_nor_dismissed(profile_id: int, kind: ItemKind, item_id: int) -> Any:
    conds = []
    for model in (m.Rating, m.RatingDismissal):
        column = model.film_id if kind == "film" else model.show_id
        conds.append(not_(exists().where(column == item_id, model.profile_id == profile_id)))
    return and_(*conds)


def _rating_prompt(
    session: Session,
    profile_id: int,
    kind: ItemKind,
    item_id: int,
    item: m.Film | m.Show | None = None,
) -> RatingPrompt | None:
    """Steps 2-4 of FR-8: the prompt of the film/show unless hidden (films; a show with a last
    present episode is visible), rated or dismissed by the profile. One combined SELECT; the row
    is the caller's already-loaded ``item`` (the identity map only holds weak references), else
    one load by id."""
    open_prompt = _not_rated_nor_dismissed(profile_id, kind, item_id)
    if kind == "film":
        # Not ``film_has_present_file()``: that EXISTS correlates to ``m.Film`` and would pull a
        # ``film`` FROM into this row-less SELECT; the id is known, so compare it directly.
        open_prompt = and_(
            exists().where(m.MediaFile.film_id == item_id, m.MediaFile.missing_since.is_(None)),
            open_prompt,
        )
    if not session.scalar(select(open_prompt)):
        return None
    if item is None:
        item = session.get(m.Film, item_id) if kind == "film" else session.get(m.Show, item_id)
    if item is None:
        return None
    return RatingPrompt(
        kind=kind,
        id=item.id,
        title=item.title,
        poster=image("poster", item.poster_path),
    )


def _show_prompt_if_finished(
    session: Session,
    profile_id: int,
    show_id: int,
    marked_ids: Iterable[int],
    show: m.Show | None = None,
) -> RatingPrompt | None:
    """The show's prompt when the request watched its last present episode: the FR-7
    "finished" rule over the episodes this request marked (an earlier watch never prompts)."""
    last = _show_last_episode_id(session, show_id)
    if not is_title_finished("show", last_episode_id=last, watched_episode_ids=set(marked_ids)):
        return None
    return _rating_prompt(session, profile_id, "show", show_id, show)


# --- POST /api/watched ---------------------------------------------------------------------------


@dataclass(frozen=True)
class _MarkTarget:
    show_id: int
    episode_ids: Sequence[int]
    show: m.Show | None = None  # loaded by the 404 check of a show target


def _target_episode_ids(session: Session, target: str, item_id: int) -> _MarkTarget:
    """The show and episode ids of an episode/season/show mark (404 for an unknown target)."""
    if target == "episode":
        episode = session.get(m.Episode, item_id)
        if episode is None:
            raise ApiError(404, "not_found", f"Episode {item_id} not found")
        return _MarkTarget(episode.show_id, [item_id])
    show = None
    if target == "season":
        season = session.get(m.Season, item_id)
        if season is None:
            raise ApiError(404, "not_found", f"Season {item_id} not found")
        show_id, column = season.show_id, m.Episode.season_id
    else:
        show = session.get(m.Show, item_id)
        if show is None:
            raise ApiError(404, "not_found", f"Show {item_id} not found")
        show_id, column = item_id, m.Episode.show_id
    ids = session.scalars(
        select(m.Episode.id).where(and_(column == item_id, present_file_exists()))
    )
    return _MarkTarget(show_id, ids.all(), show)


_IN_CHUNK = 500  # stays under SQLite's bound-parameter limit


def mark_watched(
    db: Database, profile_id: int, target: str, item_id: int, watched: bool
) -> WatchedResponse:
    """Manual mark; ``updated`` = the number of film/episode rows changed. ``media_file_id`` is
    left unchanged. Unwatching an item without a progress row changes nothing. A mark with
    ``watched: true`` that includes the finishing item may carry the (single) rating prompt."""
    with fk_to_unauthorized(), db.write_session() as session:
        now = m.utcnow()
        film: m.Film | None = None
        mark: _MarkTarget | None = None
        if target == "film":
            film = session.get(m.Film, item_id)
            if film is None:
                raise ApiError(404, "not_found", f"Film {item_id} not found")
            column = m.PlaybackProgress.film_id
            ids: Sequence[int] = [item_id]
        else:
            column = m.PlaybackProgress.episode_id
            mark = _target_episode_ids(session, target, item_id)
            ids = mark.episode_ids
        existing: dict[int, m.PlaybackProgress] = {}
        for chunk_start in range(0, len(ids), _IN_CHUNK):
            chunk = ids[chunk_start : chunk_start + _IN_CHUNK]
            for key_id, found in session.execute(
                select(column, m.PlaybackProgress).where(
                    m.PlaybackProgress.profile_id == profile_id, column.in_(chunk)
                )
            ):
                if key_id is not None:
                    existing[key_id] = found
        updated = 0
        for target_id in ids:
            row = existing.get(target_id)
            if watched:
                if row is None:
                    row = m.PlaybackProgress(profile_id=profile_id)
                    if target == "film":
                        row.film_id = target_id
                    else:
                        row.episode_id = target_id
                    session.add(row)
                _set_watched(row, now)
                updated += 1
                continue
            if row is None or (not row.watched and row.position_seconds == 0):
                continue
            row.watched = False
            row.watched_at = None
            row.position_seconds = 0.0
            row.updated_at = now
            updated += 1
        prompt = None
        if watched and film is not None:
            prompt = _rating_prompt(session, profile_id, "film", item_id, film)
        elif watched and mark is not None and ids:
            prompt = _show_prompt_if_finished(session, profile_id, mark.show_id, ids, mark.show)
        return WatchedResponse(updated=updated, rating_prompt=prompt)
