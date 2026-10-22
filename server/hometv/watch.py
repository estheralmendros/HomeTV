"""Watch state (PRD §9).

Pure rules (table-tested in ``tests/unit/test_watch_rules.py``): ``apply_progress_rule`` (90 %),
``is_last_part`` (multi-part films), ``is_in_progress`` (unwatched and >= 30 s), ``next_up`` and
``first_unwatched`` over in-memory episode lists.

DB operations (one short write transaction each): ``report_progress`` (``PUT /api/progress``)
and ``mark_watched`` (``POST /api/watched``). "Library episodes" are episodes with at least one
present media file (``missing_since IS NULL``), the same visibility rule as the catalog.
"""

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from sqlalchemy import and_, exists, select
from sqlalchemy.orm import Session

from hometv.api.errors import ApiError
from hometv.api.schemas import Progress, ProgressRequest
from hometv.db import models as m
from hometv.db.engine import Database

WATCHED_RATIO = 0.9
IN_PROGRESS_MIN_SECONDS = 30.0

# --- Pure rules ----------------------------------------------------------------------------------


@dataclass(frozen=True)
class ProgressOutcome:
    reaches_watched: bool
    position_seconds: float


def apply_progress_rule(position: float, duration: float, *, rule_applies: bool) -> ProgressOutcome:
    """``position >= 0.9 x duration`` marks watched and resets the position (only when the rule
    applies: not on a non-final part). Otherwise the position is stored as reported."""
    if rule_applies and position >= WATCHED_RATIO * duration:
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


def progress_out(row: m.PlaybackProgress | None) -> Progress | None:
    if row is None:
        return None
    return Progress(
        position_seconds=row.position_seconds,
        duration_seconds=row.duration_seconds,
        watched=row.watched,
        watched_at=row.watched_at,
        media_file_id=row.media_file_id,
        updated_at=row.updated_at,
    )


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


def report_progress(db: Database, profile_id: int, req: ProgressRequest) -> Progress:
    with db.write_session() as session:
        media_file = session.get(m.MediaFile, req.media_file_id)
        if req.item_type == "film":
            if session.get(m.Film, req.item_id) is None:
                raise ApiError(404, "not_found", f"Film {req.item_id} not found")
            linked = media_file is not None and media_file.film_id == req.item_id
        else:
            if session.get(m.Episode, req.item_id) is None:
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
        outcome = apply_progress_rule(req.position_seconds, duration, rule_applies=rule_applies)

        now = m.utcnow()
        key = {"film_id": req.item_id} if req.item_type == "film" else {"episode_id": req.item_id}
        row = _row_for(session, profile_id, **key)
        row.media_file_id = media_file.id
        row.duration_seconds = float(duration)
        row.updated_at = now
        if outcome.reaches_watched:
            _set_watched(row, now)
            if req.item_type == "episode":
                _mark_siblings_watched(session, profile_id, media_file.id, req.item_id, now)
        else:
            row.position_seconds = outcome.position_seconds
        session.flush()
        result = progress_out(row)
        assert result is not None
        return result


def _mark_siblings_watched(
    session: Session, profile_id: int, media_file_id: int, episode_id: int, now: datetime
) -> None:
    """Multi-episode file reaching watched: every episode linked to the file is watched."""
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


# --- POST /api/watched ---------------------------------------------------------------------------


def _target_episode_ids(session: Session, target: str, item_id: int) -> Sequence[int]:
    if target == "episode":
        if session.get(m.Episode, item_id) is None:
            raise ApiError(404, "not_found", f"Episode {item_id} not found")
        return [item_id]
    column = m.Episode.season_id if target == "season" else m.Episode.show_id
    model = m.Season if target == "season" else m.Show
    if session.get(model, item_id) is None:
        raise ApiError(404, "not_found", f"{target.capitalize()} {item_id} not found")
    return session.scalars(
        select(m.Episode.id).where(and_(column == item_id, present_file_exists()))
    ).all()


def mark_watched(db: Database, profile_id: int, target: str, item_id: int, watched: bool) -> int:
    """Manual mark; returns the number of film/episode rows changed. ``media_file_id`` is left
    unchanged. Unwatching an item without a progress row changes nothing."""
    with db.write_session() as session:
        now = m.utcnow()
        if target == "film":
            if session.get(m.Film, item_id) is None:
                raise ApiError(404, "not_found", f"Film {item_id} not found")
            keys: list[dict[str, int]] = [{"film_id": item_id}]
        else:
            keys = [{"episode_id": e} for e in _target_episode_ids(session, target, item_id)]
        updated = 0
        for key in keys:
            if watched:
                _set_watched(_row_for(session, profile_id, **key), now)
                updated += 1
                continue
            row = _get_row(session, profile_id, **key)
            if row is None or (not row.watched and row.position_seconds == 0):
                continue
            row.watched = False
            row.watched_at = None
            row.position_seconds = 0.0
            row.updated_at = now
            updated += 1
        return updated
