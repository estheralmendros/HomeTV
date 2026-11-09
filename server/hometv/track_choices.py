"""Per-profile remembered audio/subtitle choice and subtitle style (write side).

- ``set_track_choice``: upserts the whole choice of a visible film or show (404 ``not_found``
  otherwise, same message as detail) for the token's profile: both parts replaced (a ``null``
  part clears it), ``updated_at`` bumped. One short write transaction; a profile deleted between
  auth and the INSERT answers 401 (``fk_to_unauthorized``), any other ``IntegrityError`` is
  re-raised. Unmatched titles can be remembered (visibility only, as ratings).
- ``set_subtitle_style``: one UPDATE of the five ``profile.subtitle_*`` columns; 0 rows -> 401.

The server never matches a choice against tracks (the clients do). The read side (``choice_out``,
``track_choice``, the batched ``show_track_choices``) lives in ``catalog``, which never imports
this module.
"""

from typing import Any, cast

from sqlalchemy import CursorResult, text, update
from sqlalchemy.dialects import sqlite
from sqlalchemy.orm import InstrumentedAttribute

from hometv import catalog
from hometv.api.errors import fk_to_unauthorized, unauthorized
from hometv.api.schemas import SubtitleStyle, TrackChoice
from hometv.catalog import ItemKind
from hometv.db import models as m
from hometv.db.engine import Database
from hometv.subtitle_style import STYLE_FIELDS


def _target(kind: ItemKind) -> tuple[InstrumentedAttribute[int | None], str]:
    """The ``track_choice`` target column for ``kind`` and its partial-index predicate."""
    if kind == "film":
        return m.TrackChoice.film_id, "film_id IS NOT NULL"
    return m.TrackChoice.show_id, "show_id IS NOT NULL"


def choice_columns(choice: TrackChoice) -> dict[str, Any]:
    """Pure: the API choice -> every part column (``m.TRACK_CHOICE_PART_COLUMNS``, in order). A
    ``null`` part writes NULLs (and ``False`` for the NOT NULL subtitle flags); ``off`` writes
    mode ``off`` with no track trait."""
    audio, subs = choice.audio, choice.subtitles
    track = subs if subs is not None and subs.mode == "track" else None
    return {
        "audio_language": audio.language if audio else None,
        "audio_title": audio.title if audio else None,
        "audio_channels": audio.channels if audio else None,
        "subtitle_mode": subs.mode if subs else None,
        "subtitle_language": track.language if track else None,
        "subtitle_title": track.title if track else None,
        "subtitle_forced": track.forced if track else False,
        "subtitle_hearing_impaired": track.hearing_impaired if track else False,
        "subtitle_source": track.source if track else None,
        "subtitle_kind": track.kind if track else None,
    }


def set_track_choice(
    db: Database, profile_id: int, kind: ItemKind, item_id: int, choice: TrackChoice
) -> None:
    """Upsert the whole choice (both parts; a null part clears it), bump ``updated_at``."""
    column, where = _target(kind)
    columns = choice_columns(choice)
    with fk_to_unauthorized(), db.write_session() as session:
        catalog.require_visible(session, kind, item_id)
        now = m.utcnow()
        session.execute(
            sqlite.insert(m.TrackChoice)
            .values({"profile_id": profile_id, column.key: item_id, **columns, "updated_at": now})
            .on_conflict_do_update(
                index_elements=[column.key, "profile_id"],
                index_where=text(where),
                set_={**columns, "updated_at": now},
            )
        )


def set_subtitle_style(db: Database, profile_id: int, style: SubtitleStyle) -> None:
    """``UPDATE profile SET subtitle_* WHERE id = :profile_id``; 0 rows -> ``unauthorized()``."""
    values = {f"subtitle_{field}": getattr(style, field) for field in STYLE_FIELDS}
    with db.write_session() as session:
        result = cast(
            CursorResult[Any],
            session.execute(update(m.Profile).where(m.Profile.id == profile_id).values(values)),
        )
        if result.rowcount == 0:
            raise unauthorized()
