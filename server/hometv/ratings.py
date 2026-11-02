"""Per-profile ratings and household activity.

- ``set_rating`` / ``remove_rating`` / ``dismiss_prompt``: idempotent, one short write
  transaction each, only on a visible title (404 ``not_found`` otherwise, same message as detail),
  always for the token's profile. ``set_rating`` upserts (re-rating replaces the stars and bumps
  ``rated_at``, also with the same value); ``remove_rating`` deletes the row and records a
  dismissal ("ask once" covers a withdrawn rating); ``dismiss_prompt`` keeps the first
  ``dismissed_at``. A profile deleted between auth and the INSERT answers 401 ``unauthorized``
  (``fk_to_unauthorized``), never a 500, and rolls the whole transaction back.
- ``list_activity``: every visible rating of the household, newest first (ties by id DESC); rows
  of hidden titles are kept, never returned or counted. ``item`` is the requester's summary.
  At most 8 queries, independent of the page size (2 for an empty page).

Rating does not require having watched. The detail block and ``my_rating`` on summaries live in
``catalog`` (``item_ratings``, ``_my_ratings``), which never imports this module.
"""

from sqlalchemy import and_, delete, func, or_, select, text
from sqlalchemy.dialects import sqlite
from sqlalchemy.orm import InstrumentedAttribute, Session

from hometv import catalog
from hometv.api.errors import fk_to_unauthorized
from hometv.api.schemas import ActivityEntry, FilmSummary, Page, ProfileRef, ShowSummary
from hometv.catalog import ItemKind, stars_from_x2
from hometv.db import models as m
from hometv.db.engine import Database
from hometv.watch import film_has_present_file

RatingKind = ItemKind


def _target(
    model: type[m.Rating] | type[m.RatingDismissal], kind: RatingKind
) -> tuple[InstrumentedAttribute[int | None], str]:
    """The target column of ``model`` for ``kind`` and the partial-index predicate."""
    if kind == "film":
        return model.film_id, "film_id IS NOT NULL"
    return model.show_id, "show_id IS NOT NULL"


def _insert_dismissal(session: Session, profile_id: int, kind: RatingKind, item_id: int) -> None:
    column, where = _target(m.RatingDismissal, kind)
    session.execute(
        sqlite.insert(m.RatingDismissal)
        .values({"profile_id": profile_id, column.key: item_id, "dismissed_at": m.utcnow()})
        .on_conflict_do_nothing(index_elements=[column.key, "profile_id"], index_where=text(where))
    )


def set_rating(
    db: Database, profile_id: int, kind: RatingKind, item_id: int, stars_x2: int
) -> None:
    column, where = _target(m.Rating, kind)
    with fk_to_unauthorized(), db.write_session() as session:
        catalog.require_visible(session, kind, item_id)
        now = m.utcnow()
        session.execute(
            sqlite.insert(m.Rating)
            .values(
                {
                    "profile_id": profile_id,
                    column.key: item_id,
                    "stars_x2": stars_x2,
                    "rated_at": now,
                }
            )
            .on_conflict_do_update(
                index_elements=[column.key, "profile_id"],
                index_where=text(where),
                set_={"stars_x2": stars_x2, "rated_at": now},
            )
        )


def remove_rating(db: Database, profile_id: int, kind: RatingKind, item_id: int) -> None:
    column, _ = _target(m.Rating, kind)
    with fk_to_unauthorized(), db.write_session() as session:
        catalog.require_visible(session, kind, item_id)
        session.execute(
            delete(m.Rating).where(m.Rating.profile_id == profile_id, column == item_id)
        )
        _insert_dismissal(session, profile_id, kind, item_id)


def dismiss_prompt(db: Database, profile_id: int, kind: RatingKind, item_id: int) -> None:
    with fk_to_unauthorized(), db.write_session() as session:
        catalog.require_visible(session, kind, item_id)
        _insert_dismissal(session, profile_id, kind, item_id)


def list_activity(
    session: Session, profile_id: int, page: int, page_size: int
) -> Page[ActivityEntry]:
    r, f, s, p = m.Rating, m.Film, m.Show, m.Profile
    visible = or_(
        and_(r.film_id.is_not(None), film_has_present_file()),
        and_(r.show_id.is_not(None), catalog.show_visible()),
    )
    base = (
        select(
            r.id,
            r.film_id,
            r.show_id,
            r.stars_x2,
            r.rated_at,
            p.id.label("profile_id"),
            p.name.label("profile_name"),
            p.avatar_id.label("profile_avatar_id"),
        )
        .select_from(r)
        .join(p, p.id == r.profile_id)
        .outerjoin(f, f.id == r.film_id)
        .outerjoin(s, s.id == r.show_id)
        .where(visible)
    )
    total = session.scalar(select(func.count()).select_from(base.subquery())) or 0
    rows = session.execute(
        base.order_by(r.rated_at.desc(), r.id.desc())
        .offset((page - 1) * page_size)
        .limit(page_size)
    ).all()
    film_ids = list({row.film_id for row in rows if row.film_id is not None})
    show_ids = list({row.show_id for row in rows if row.show_id is not None})
    films: dict[int, FilmSummary] = {}
    shows: dict[int, ShowSummary] = {}
    if film_ids:
        film_rows = list(session.scalars(select(f).where(f.id.in_(film_ids))))
        films = {x.id: x for x in catalog.film_summaries(session, profile_id, film_rows)}
    if show_ids:
        show_rows = list(session.scalars(select(s).where(s.id.in_(show_ids))))
        shows = {x.id: x for x in catalog.show_summaries(session, profile_id, show_rows)}
    items = []
    for row in rows:
        profile = ProfileRef(
            id=row.profile_id, name=row.profile_name, avatar_id=row.profile_avatar_id
        )
        item: FilmSummary | ShowSummary = (
            films[row.film_id] if row.film_id is not None else shows[row.show_id]
        )
        items.append(
            ActivityEntry(
                profile=profile,
                kind=item.type,
                item=item,
                stars=stars_from_x2(row.stars_x2),
                rated_at=row.rated_at,
            )
        )
    return Page[ActivityEntry](items=items, page=page, page_size=page_size, total=total)
