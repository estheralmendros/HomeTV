"""``hometv.watchlist`` ("Mi lista"): idempotent add/remove on visible titles, the
batched list query (visibility, type filter, three fixed sorts, paging, per-profile summaries)
and its bounded query count."""

import subprocess
import sys
from datetime import timedelta

import pytest
from sqlalchemy import func, select, text, update
from sqlalchemy.exc import IntegrityError

from hometv import catalog, watchlist
from hometv.api.errors import ApiError
from hometv.api.schemas import FilmSummary, ProgressRequest, ShowSummary
from hometv.db import models as m
from hometv.db.engine import Database
from hometv.watch import mark_watched, report_progress
from hometv.watchlist import WatchlistParams
from tests.factories import (
    T0,
    add_file,
    add_film,
    add_profile,
    add_progress,
    add_show,
    add_watchlist_entry,
)
from tests.stubs import capture_selects, capture_writes


@pytest.fixture
def profiles(mdb: Database) -> tuple[int, int]:
    with mdb.write_session() as s:
        return add_profile(s, "A").id, add_profile(s, "B", avatar_id="avatar-02").id


def rows(db: Database, profile_id: int | None = None) -> list[m.WatchlistEntry]:
    with db.read_session() as s:
        q = select(m.WatchlistEntry).order_by(m.WatchlistEntry.id)
        if profile_id is not None:
            q = q.where(m.WatchlistEntry.profile_id == profile_id)
        return list(s.scalars(q))


def listing(db: Database, profile_id: int, **kw):
    with db.read_session() as s:
        return watchlist.list_entries(s, profile_id, WatchlistParams(**kw))


def titles(page) -> list[str]:
    return [e.item.title for e in page.items]


def at(minutes: int):
    return T0 + timedelta(minutes=minutes)


# --- add / remove ----------------------------------------------------------------------------


def test_add_is_idempotent_and_keeps_the_first_added_at(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune").id
        show = add_show(s, "Lost")[0].id
    watchlist.add(mdb, a, "film", film)
    watchlist.add(mdb, a, "show", show)
    [first_film, first_show] = rows(mdb)
    watchlist.add(mdb, a, "film", film)
    watchlist.add(mdb, a, "show", show)
    again = rows(mdb)
    assert [(r.id, r.film_id, r.show_id, r.added_at) for r in again] == [
        (first_film.id, film, None, first_film.added_at),
        (first_show.id, None, show, first_show.added_at),
    ]


def test_remove_deletes_only_that_entry_and_is_idempotent(mdb, profiles):
    a, b = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune").id
        show = add_show(s, "Lost")[0].id
    for pid in (a, b):
        watchlist.add(mdb, pid, "film", film)
        watchlist.add(mdb, pid, "show", show)
    watchlist.remove(mdb, a, "film", film)
    watchlist.remove(mdb, a, "film", film)
    assert [(r.film_id, r.show_id) for r in rows(mdb, a)] == [(None, show)]
    assert len(rows(mdb, b)) == 2
    watchlist.remove(mdb, a, "show", show)
    watchlist.remove(mdb, a, "show", show)
    assert rows(mdb, a) == []


@pytest.mark.parametrize("op", [watchlist.add, watchlist.remove])
def test_unknown_and_hidden_titles_are_not_found(mdb, profiles, op):
    a, _ = profiles
    with mdb.write_session() as s:
        hidden_film = add_film(s, "Gone", files=0)
        add_file(s, film_id=hidden_film.id, missing=True)
        no_file_film = add_film(s, "Nothing", files=0).id
        hidden_show = add_show(s, "Ghost", with_files=False)[0].id
        missing_show, eps = add_show(s, "Unplugged", episodes={1: 1}, with_files=False)
        add_file(s, episode_ids=[eps[(1, 1)].id], missing=True)
        cases = [
            ("film", 999, "Film 999 not found"),
            ("film", hidden_film.id, f"Film {hidden_film.id} not found"),
            ("film", no_file_film, f"Film {no_file_film} not found"),
            ("show", 999, "Show 999 not found"),
            ("show", hidden_show, f"Show {hidden_show} not found"),
            ("show", missing_show.id, f"Show {missing_show.id} not found"),
        ]
    for kind, item_id, message in cases:
        with pytest.raises(ApiError) as exc:
            op(mdb, a, kind, item_id)
        assert (exc.value.status_code, exc.value.code, exc.value.message) == (
            404,
            "not_found",
            message,
        )
    assert rows(mdb) == []


def test_unmatched_and_pending_titles_can_be_added(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        unmatched = add_film(s, "Home Video", match_status="unmatched").id
        pending = add_show(s, "New Show", match_status="pending")[0].id
    watchlist.add(mdb, a, "film", unmatched)
    watchlist.add(mdb, a, "show", pending)
    page = listing(mdb, a)
    assert sorted((e.kind, e.item.match_status) for e in page.items) == [
        ("film", "unmatched"),
        ("show", "pending"),
    ]


def test_add_for_a_deleted_profile_is_unauthorized(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune").id
        show = add_show(s, "Lost")[0].id
        s.execute(m.Profile.__table__.delete().where(m.Profile.id == a))
    for kind, item_id in (("film", film), ("show", show)):
        with pytest.raises(ApiError) as exc:
            watchlist.add(mdb, a, kind, item_id)
        assert (exc.value.status_code, exc.value.code) == (401, "unauthorized")
    watchlist.remove(mdb, a, "film", film)  # nothing to delete: no error
    assert rows(mdb) == []


def test_add_reraises_integrity_errors_other_than_the_profile_fk(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune").id
        s.execute(
            text(
                "CREATE TRIGGER reject_entry BEFORE INSERT ON watchlist_entry "
                "BEGIN SELECT RAISE(ABORT, 'rejected by trigger'); END"
            )
        )
    with pytest.raises(IntegrityError, match="rejected by trigger"):
        watchlist.add(mdb, a, "film", film)
    assert rows(mdb) == []


def test_service_does_not_import_the_dependency_layer():
    code = "import sys, hometv.watchlist; print('hometv.api.deps' in sys.modules)"
    out = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, check=True)
    assert out.stdout.strip() == "False"


def test_progress_and_watched_marks_leave_entries_alone(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        show = add_show(s, "Lost", episodes={1: 1})[0]
        film_file = s.scalar(select(m.MediaFile.id).where(m.MediaFile.film_id == film.id))
        add_watchlist_entry(s, a, film=film, added_at=at(1))
        add_watchlist_entry(s, a, show=show, added_at=at(2))
    before = [(r.id, r.film_id, r.show_id, r.added_at) for r in rows(mdb)]
    assert film_file is not None
    report_progress(
        mdb,
        a,
        ProgressRequest(
            item_type="film", item_id=film.id, media_file_id=film_file, position_seconds=5300
        ),
    )
    mark_watched(mdb, a, "show", show.id, True)
    mark_watched(mdb, a, "film", film.id, False)
    assert [(r.id, r.film_id, r.show_id, r.added_at) for r in rows(mdb)] == before


# --- list_entries ----------------------------------------------------------------------------


def test_added_sort_is_newest_first_with_ties_by_id_desc(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        old = add_film(s, "Old")
        tie1 = add_film(s, "Tie One")
        tie2 = add_show(s, "Tie Two")[0]
        new = add_show(s, "New")[0]
        add_watchlist_entry(s, a, film=old, added_at=at(1))
        add_watchlist_entry(s, a, film=tie1, added_at=at(5))
        add_watchlist_entry(s, a, show=tie2, added_at=at(5))
        add_watchlist_entry(s, a, show=new, added_at=at(9))
    page = listing(mdb, a)
    assert titles(page) == ["New", "Tie Two", "Tie One", "Old"]
    assert [e.added_to_list_at for e in page.items] == [at(9), at(5), at(5), at(1)]
    assert (page.page, page.page_size, page.total) == (1, 50, 4)
    assert all(e.kind == e.item.type for e in page.items)


def test_title_sort_mixes_kinds_ignores_articles_and_ties_by_id(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        zodiac = add_film(s, "Zodiac")
        office = add_show(s, "The Office")[0]
        alien = add_film(s, "Alien")
        dune_b = add_film(s, "Dune", year=1984)
        breaking = add_show(s, "Breaking Bad")[0]
        dune_a = add_film(s, "Dune", year=2021)
        add_watchlist_entry(s, a, film=zodiac, added_at=at(1))
        add_watchlist_entry(s, a, show=office, added_at=at(2))
        add_watchlist_entry(s, a, film=dune_a, added_at=at(3))  # lower entry id
        add_watchlist_entry(s, a, film=alien, added_at=at(4))
        add_watchlist_entry(s, a, film=dune_b, added_at=at(5))
        add_watchlist_entry(s, a, show=breaking, added_at=at(6))
    page = listing(mdb, a, sort="title")
    assert [(e.item.title, e.item.year) for e in page.items] == [
        ("Alien", 2020),
        ("Breaking Bad", 2005),
        ("Dune", 2021),
        ("Dune", 1984),
        ("The Office", 2005),
        ("Zodiac", 2020),
    ]


def test_year_sort_is_newest_first_nulls_last_then_title_then_id(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        items = [
            ("film", add_film(s, "Undated Z", year=None)),
            ("show", add_show(s, "Undated A", year=None)[0]),
            ("film", add_film(s, "Old", year=1990)),
            ("film", add_film(s, "Zeta", year=2020)),
            ("show", add_show(s, "Alpha", year=2020)[0]),
            ("film", add_film(s, "Newest", year=2024)),
            ("film", add_film(s, "Alpha", year=2020)),
        ]
        for i, (kind, item) in enumerate(items):
            add_watchlist_entry(s, a, **{kind: item}, added_at=at(i))
    page = listing(mdb, a, sort="year")
    assert [(e.kind, e.item.title) for e in page.items] == [
        ("film", "Newest"),
        ("show", "Alpha"),  # same sort_title as the film: lower entry id first
        ("film", "Alpha"),
        ("film", "Zeta"),
        ("film", "Old"),
        ("show", "Undated A"),
        ("film", "Undated Z"),
    ]


def test_type_filter_paging_and_total(mdb, profiles):
    a, b = profiles
    with mdb.write_session() as s:
        f1, f2, f3 = (add_film(s, t) for t in ("F1", "F2", "F3"))
        s1, s2 = (add_show(s, t)[0] for t in ("S1", "S2"))
        hidden = add_film(s, "Hidden", files=0)
        add_file(s, film_id=hidden.id, missing=True)
        for i, (kind, item) in enumerate(
            [("film", f1), ("show", s1), ("film", f2), ("show", s2), ("film", f3)]
        ):
            add_watchlist_entry(s, a, **{kind: item}, added_at=at(i))
        add_watchlist_entry(s, a, film=hidden, added_at=at(99))
        add_watchlist_entry(s, b, film=f1, added_at=at(0))
    everything = listing(mdb, a)
    assert titles(everything) == ["F3", "S2", "F2", "S1", "F1"]
    assert everything.total == 5
    films = listing(mdb, a, type="films")
    assert (titles(films), films.total) == (["F3", "F2", "F1"], 3)
    shows = listing(mdb, a, type="shows")
    assert (titles(shows), shows.total) == (["S2", "S1"], 2)
    assert all(isinstance(e.item, FilmSummary) for e in films.items)
    assert all(isinstance(e.item, ShowSummary) for e in shows.items)
    page2 = listing(mdb, a, type="films", page=2, page_size=2)
    assert (titles(page2), page2.page, page2.page_size, page2.total) == (["F1"], 2, 2, 3)
    beyond = listing(mdb, a, page=9, page_size=2)
    assert (beyond.items, beyond.total) == ([], 5)
    assert listing(mdb, b).total == 1


def test_watched_filter_follows_the_library_rule_per_kind(mdb, profiles):
    """Films: the profile's progress is watched. Shows: watched = a watched non-special episode
    and no unwatched one; unwatched = an unwatched non-special episode (specials-only = neither).
    Same rule as GET /api/films|shows ``watched``; combines with ``type``; ``total`` follows."""
    a, b = profiles
    with mdb.write_session() as s:
        seen = add_film(s, "Seen film")
        started = add_film(s, "Started film")
        fresh = add_film(s, "Fresh film")
        done, done_eps = add_show(s, "Done show", episodes={0: 1, 1: 2})
        partial, partial_eps = add_show(s, "Partial show", episodes={1: 2})
        untouched = add_show(s, "Untouched show", episodes={1: 1})[0]
        specials, specials_eps = add_show(s, "Specials show", episodes={0: 1})
        add_progress(s, a, film_id=seen.id, watched=True)
        add_progress(s, a, film_id=started.id, position=600)
        add_progress(s, b, film_id=fresh.id, watched=True)  # another profile's mark
        for key in ((1, 1), (1, 2)):
            add_progress(s, a, episode_id=done_eps[key].id, watched=True)
        add_progress(s, a, episode_id=partial_eps[(1, 1)].id, watched=True)
        add_progress(s, a, episode_id=specials_eps[(0, 1)].id, watched=True)
        items = [seen, started, fresh, done, partial, untouched, specials]
        for i, item in enumerate(items):
            kind = "film" if isinstance(item, m.Film) else "show"
            add_watchlist_entry(s, a, **{kind: item}, added_at=at(len(items) - i))
    assert titles(listing(mdb, a)) == [
        "Seen film",
        "Started film",
        "Fresh film",
        "Done show",
        "Partial show",
        "Untouched show",
        "Specials show",
    ]
    watched = listing(mdb, a, watched=True)
    assert (titles(watched), watched.total) == (["Seen film", "Done show"], 2)
    unwatched = listing(mdb, a, watched=False)
    assert (titles(unwatched), unwatched.total) == (
        ["Started film", "Fresh film", "Partial show", "Untouched show"],
        4,
    )
    films = listing(mdb, a, type="films", watched=False)
    assert (titles(films), films.total) == (["Started film", "Fresh film"], 2)
    shows = listing(mdb, a, type="shows", watched=True, sort="title")
    assert (titles(shows), shows.total) == (["Done show"], 1)
    page2 = listing(mdb, a, watched=False, page=2, page_size=3)
    assert (titles(page2), page2.total) == (["Untouched show"], 4)


def test_hidden_entry_reappears_unchanged(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        show = add_show(s, "Lost", episodes={1: 2})[0]
        other = add_film(s, "Other")
        add_watchlist_entry(s, a, film=film, added_at=at(3))
        add_watchlist_entry(s, a, show=show, added_at=at(2))
        add_watchlist_entry(s, a, film=other, added_at=at(1))
    before = listing(mdb, a)
    assert titles(before) == ["Dune", "Lost", "Other"]
    with mdb.write_session() as s:
        s.execute(
            update(m.MediaFile)
            .where(m.MediaFile.film_id.is_distinct_from(other.id))
            .values(missing_since=T0)
        )
    hidden = listing(mdb, a)
    assert (titles(hidden), hidden.total) == (["Other"], 1)
    assert len(rows(mdb, a)) == 3  # kept, only hidden
    with mdb.write_session() as s:
        s.execute(update(m.MediaFile).values(missing_since=None))
    after = listing(mdb, a)
    assert titles(after) == ["Dune", "Lost", "Other"]
    assert [e.added_to_list_at for e in after.items] == [at(3), at(2), at(1)]


def test_summaries_carry_the_profile_progress_and_unwatched_counts(mdb, profiles):
    a, b = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        show, eps = add_show(s, "Lost", episodes={1: 3})
        add_progress(s, a, film_id=film.id, watched=True, position=0)
        add_progress(s, a, episode_id=eps[(1, 1)].id, watched=True)
        for pid in (a, b):
            add_watchlist_entry(s, pid, film=film, added_at=at(2))
            add_watchlist_entry(s, pid, show=show, added_at=at(1))
    for_a = {e.kind: e.item for e in listing(mdb, a).items}
    for_b = {e.kind: e.item for e in listing(mdb, b).items}
    assert isinstance(for_a["film"], FilmSummary) and isinstance(for_b["film"], FilmSummary)
    assert for_a["film"].progress is not None and for_a["film"].progress.watched
    assert for_b["film"].progress is None
    assert isinstance(for_a["show"], ShowSummary) and isinstance(for_b["show"], ShowSummary)
    assert (for_a["show"].episode_count, for_a["show"].unwatched_episode_count) == (3, 2)
    assert for_b["show"].unwatched_episode_count == 3


def _seed_list(db: Database, profile_id: int, films: int, shows: int) -> None:
    with db.write_session() as s:
        for i in range(films):
            add_watchlist_entry(s, profile_id, film=add_film(s, f"F{i}"), added_at=at(i))
        for i in range(shows):
            show = add_show(s, f"S{i}", episodes={1: 1})[0]
            add_watchlist_entry(s, profile_id, show=show, added_at=at(100 + i))


def _count_list_queries(db: Database, profile_id: int, **kw) -> int:
    with capture_selects(db) as statements:
        page = listing(db, profile_id, **kw)
    assert page.total >= len(page.items)
    return len(statements)


@pytest.mark.parametrize(
    ("films", "shows", "limit"), [(0, 0, 2), (1, 0, 5), (0, 1, 5), (1, 1, 8), (30, 30, 8)]
)
def test_query_count_is_bounded(mdb, profiles, films, shows, limit):
    a, _ = profiles
    _seed_list(mdb, a, films, shows)
    for sort in ("added", "title", "year"):
        assert _count_list_queries(mdb, a, sort=sort, page_size=200) <= limit


@pytest.mark.parametrize("watched", [True, False])
def test_watched_filter_adds_no_query(mdb, profiles, watched):
    a, _ = profiles
    _seed_list(mdb, a, 3, 3)
    with mdb.write_session() as s:  # one watched film and show: both filtered pages are mixed
        film = s.scalar(select(m.Film.id).where(m.Film.title == "F0"))
        episode = s.scalar(
            select(m.Episode.id)
            .join(m.Show, m.Show.id == m.Episode.show_id)
            .where(m.Show.title == "S0")
        )
        add_progress(s, a, film_id=film, watched=True)
        add_progress(s, a, episode_id=episode, watched=True)
    plain = _count_list_queries(mdb, a, page_size=200)
    page = listing(mdb, a, watched=watched, page_size=200)
    assert {e.kind for e in page.items} == {"film", "show"}
    assert _count_list_queries(mdb, a, watched=watched, page_size=200) == plain


def test_query_count_does_not_depend_on_list_or_page_size(mdb, profiles):
    a, b = profiles
    _seed_list(mdb, a, 1, 1)
    small = _count_list_queries(mdb, a)
    with mdb.write_session() as s:
        for i in range(30):
            add_watchlist_entry(s, b, film=add_film(s, f"BF{i}"), added_at=at(i))
            add_watchlist_entry(s, b, show=add_show(s, f"BS{i}")[0], added_at=at(50 + i))
    assert _count_list_queries(mdb, b, page_size=200) == small
    assert _count_list_queries(mdb, b, page_size=40) == small  # mixed page


def test_list_entries_never_touches_the_write_engine(mdb, profiles):
    a, _ = profiles
    _seed_list(mdb, a, 2, 2)
    with capture_writes(mdb) as statements:
        assert listing(mdb, a).total == 4
    assert statements == []
    with mdb.read_session() as s:
        assert s.scalar(select(func.count()).select_from(m.WatchlistEntry)) == 4


# --- in_watchlist on detail ------------------------------------------------------------------


def test_detail_in_watchlist_is_per_profile(mdb, profiles):
    a, b = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        show = add_show(s, "Lost")[0]
        add_watchlist_entry(s, a, film=film)
        add_watchlist_entry(s, a, show=show)
        other = add_film(s, "Other")
        add_show(s, "Second show")
        add_watchlist_entry(s, b, film=other)
    with mdb.read_session() as s:
        assert catalog.in_watchlist(s, a, "film", film.id)
        assert catalog.in_watchlist(s, a, "show", show.id)
        assert not catalog.in_watchlist(s, b, "film", film.id)
        assert catalog.in_watchlist(s, b, "film", other.id)
        assert not catalog.in_watchlist(s, b, "show", other.id)  # film 2 is not show 2
        assert not catalog.in_watchlist(s, b, "show", show.id)  # b has films only
        assert catalog.film_detail(s, a, film.id, 10).in_watchlist is True
        assert catalog.film_detail(s, b, film.id, 10).in_watchlist is False
        assert catalog.show_detail(s, a, show.id, 10).in_watchlist is True
        assert catalog.show_detail(s, b, show.id, 10).in_watchlist is False
