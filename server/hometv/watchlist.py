"""Per-profile watchlist "Mi lista" (FR-2/FR-3).

- ``add`` / ``remove``: idempotent, one short write transaction each, only on a visible title
  (404 ``not_found`` otherwise, same message as detail). ``add`` keeps the original
  ``added_at`` (``ON CONFLICT DO NOTHING`` on the partial unique indexes); a profile deleted
  between auth and the INSERT answers 401 ``unauthorized`` (FK failure), never a 500.
- ``list_entries``: visible entries only (hidden ones are kept, never returned or counted),
  filtered by type and by the library ``watched`` rule (``catalog.watched_condition`` per
  kind, in the same SQL) and ordered in SQL over ``watchlist_entry`` LEFT JOIN ``film``/``show``;
  summaries built in batch. At most 8 queries, independent of the page size.
"""

from dataclasses import dataclass
from typing import Any, Literal

from sqlalchemy import and_, delete, func, or_, select
from sqlalchemy.dialects import sqlite
from sqlalchemy.orm import InstrumentedAttribute, Session

from hometv import catalog
from hometv.api.errors import fk_to_unauthorized
from hometv.api.schemas import FilmSummary, Page, ShowSummary, WatchlistEntry
from hometv.catalog import WatchlistKind
from hometv.db import models as m
from hometv.db.engine import Database
from hometv.watch import film_has_present_file

WatchlistType = Literal["all", "films", "shows"]
WatchlistSort = Literal["added", "title", "year"]


@dataclass(frozen=True)
class WatchlistParams:
    type: WatchlistType = "all"
    sort: WatchlistSort = "added"
    watched: bool | None = None
    page: int = 1
    page_size: int = 50


def _visible_target(
    session: Session, kind: WatchlistKind, item_id: int
) -> InstrumentedAttribute[int | None]:
    """The entry column of ``kind``; 404 ``not_found`` unless the title is visible."""
    catalog.require_visible(session, kind, item_id)
    return m.WatchlistEntry.film_id if kind == "film" else m.WatchlistEntry.show_id


def add(db: Database, profile_id: int, kind: WatchlistKind, item_id: int) -> None:
    # ON CONFLICT does not cover FKs: a profile deleted after auth is a 401 (the target was
    # checked in this transaction).
    with fk_to_unauthorized(), db.write_session() as session:
        column = _visible_target(session, kind, item_id)
        session.execute(
            sqlite.insert(m.WatchlistEntry)
            .values({"profile_id": profile_id, column.key: item_id})
            .on_conflict_do_nothing()
        )


def remove(db: Database, profile_id: int, kind: WatchlistKind, item_id: int) -> None:
    with fk_to_unauthorized(), db.write_session() as session:
        column = _visible_target(session, kind, item_id)
        session.execute(
            delete(m.WatchlistEntry).where(
                m.WatchlistEntry.profile_id == profile_id, column == item_id
            )
        )


def list_entries(
    session: Session, profile_id: int, params: WatchlistParams
) -> Page[WatchlistEntry]:
    we, f, s = m.WatchlistEntry, m.Film, m.Show
    visible = or_(
        and_(we.film_id.is_not(None), film_has_present_file()),
        and_(we.show_id.is_not(None), catalog.show_visible()),
    )
    conds: list[Any] = [we.profile_id == profile_id, visible]
    if params.type == "films":
        conds.append(we.film_id.is_not(None))
    elif params.type == "shows":
        conds.append(we.show_id.is_not(None))
    if params.watched is not None:
        conds.append(
            or_(
                and_(
                    we.film_id.is_not(None),
                    catalog.watched_condition(f, profile_id, params.watched),
                ),
                and_(
                    we.show_id.is_not(None),
                    catalog.watched_condition(s, profile_id, params.watched),
                ),
            )
        )
    sort_title = func.coalesce(f.sort_title, s.sort_title)
    year = func.coalesce(f.year, s.year)
    order: dict[WatchlistSort, list[Any]] = {
        "added": [we.added_at.desc(), we.id.desc()],
        "title": [sort_title.asc(), we.id.asc()],
        "year": [year.is_(None), year.desc(), sort_title.asc(), we.id.asc()],
    }
    base = (
        select(we.film_id, we.show_id, we.added_at)
        .select_from(we)
        .outerjoin(f, f.id == we.film_id)
        .outerjoin(s, s.id == we.show_id)
        .where(*conds)
    )
    total = session.scalar(select(func.count()).select_from(base.subquery())) or 0
    rows = session.execute(
        base.order_by(*order[params.sort])
        .offset((params.page - 1) * params.page_size)
        .limit(params.page_size)
    ).all()
    film_ids = [r.film_id for r in rows if r.film_id is not None]
    show_ids = [r.show_id for r in rows if r.show_id is not None]
    films: dict[int, FilmSummary] = {}
    shows: dict[int, ShowSummary] = {}
    if film_ids:
        film_rows = list(session.scalars(select(f).where(f.id.in_(film_ids))))
        films = {x.id: x for x in catalog.film_summaries(session, profile_id, film_rows)}
    if show_ids:
        show_rows = list(session.scalars(select(s).where(s.id.in_(show_ids))))
        shows = {x.id: x for x in catalog.show_summaries(session, profile_id, show_rows)}
    items = [
        WatchlistEntry(kind="film", item=films[r.film_id], added_to_list_at=r.added_at)
        if r.film_id is not None
        else WatchlistEntry(kind="show", item=shows[r.show_id], added_to_list_at=r.added_at)
        for r in rows
    ]
    return Page[WatchlistEntry](
        items=items, page=params.page, page_size=params.page_size, total=total
    )
