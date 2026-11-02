"""``hometv.ratings``: idempotent rating writes and dismissals on visible titles,
the deleted-profile 401, the batched activity list, the detail block (``item_ratings``) and the
viewer's ``my_rating`` on summaries."""

from datetime import timedelta

import pytest
from sqlalchemy import select, text, update

from hometv import catalog, ratings
from hometv.api.errors import ApiError
from hometv.api.schemas import FilmSummary, ShowSummary
from hometv.db import models as m
from hometv.db.engine import Database
from tests.factories import (
    T0,
    add_file,
    add_film,
    add_profile,
    add_progress,
    add_rating,
    add_rating_dismissal,
    add_show,
)
from tests.stubs import capture_selects, capture_writes


@pytest.fixture
def profiles(mdb: Database) -> tuple[int, int, int]:
    with mdb.write_session() as s:
        return (
            add_profile(s, "Ana").id,
            add_profile(s, "Luis", avatar_id="avatar-07").id,
            add_profile(s, "Esther", avatar_id="avatar-02").id,
        )


def at(minutes: int):
    return T0 + timedelta(minutes=minutes)


def rating_rows(db: Database) -> list[tuple[int, int | None, int | None, int]]:
    with db.read_session() as s:
        return [
            (r.profile_id, r.film_id, r.show_id, r.stars_x2)
            for r in s.scalars(select(m.Rating).order_by(m.Rating.id))
        ]


def dismissals(db: Database) -> list[m.RatingDismissal]:
    with db.read_session() as s:
        return list(s.scalars(select(m.RatingDismissal).order_by(m.RatingDismissal.id)))


def activity(db: Database, profile_id: int, page: int = 1, page_size: int = 50):
    with db.read_session() as s:
        return ratings.list_activity(s, profile_id, page, page_size)


# --- set / remove / dismiss ------------------------------------------------------------------


def test_set_rating_upserts_replacing_stars_and_bumping_rated_at(mdb, profiles):
    a, b, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        show = add_show(s, "Lost")[0]
        add_rating(s, a, film=film, stars_x2=4, rated_at=at(1))
        add_rating(s, a, show=show, stars_x2=4, rated_at=at(1))
        film_id, show_id = film.id, show.id
    ratings.set_rating(mdb, a, "film", film_id, 9)
    ratings.set_rating(mdb, a, "show", show_id, 4)  # same value: still a fresh "rated now"
    ratings.set_rating(mdb, b, "film", film_id, 2)
    assert rating_rows(mdb) == [(a, film_id, None, 9), (a, None, show_id, 4), (b, film_id, None, 2)]
    with mdb.read_session() as s:
        stamps = [r.rated_at for r in s.scalars(select(m.Rating).order_by(m.Rating.id))]
    assert all(stamp > at(1) for stamp in stamps)


@pytest.mark.parametrize("op", ["set", "remove", "dismiss"])
def test_unknown_and_hidden_titles_are_not_found(mdb, profiles, op):
    a, _, _ = profiles
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
    calls = {
        "set": lambda kind, item_id: ratings.set_rating(mdb, a, kind, item_id, 8),
        "remove": lambda kind, item_id: ratings.remove_rating(mdb, a, kind, item_id),
        "dismiss": lambda kind, item_id: ratings.dismiss_prompt(mdb, a, kind, item_id),
    }
    for kind, item_id, message in cases:
        with pytest.raises(ApiError) as exc:
            calls[op](kind, item_id)
        assert (exc.value.status_code, exc.value.code, exc.value.message) == (
            404,
            "not_found",
            message,
        )
    assert rating_rows(mdb) == [] and dismissals(mdb) == []


def test_unmatched_and_pending_titles_can_be_rated_without_watching(mdb, profiles):
    a, _, _ = profiles
    with mdb.write_session() as s:
        unmatched = add_film(s, "Home Video", match_status="unmatched").id
        pending = add_show(s, "New Show", match_status="pending")[0].id
    ratings.set_rating(mdb, a, "film", unmatched, 1)
    ratings.set_rating(mdb, a, "show", pending, 10)
    assert rating_rows(mdb) == [(a, unmatched, None, 1), (a, None, pending, 10)]


def test_remove_deletes_the_own_rating_and_records_a_dismissal(mdb, profiles):
    a, b, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        show = add_show(s, "Lost")[0]
        add_rating(s, a, film=film)
        add_rating(s, b, film=film, stars_x2=3)
        film_id, show_id = film.id, show.id
    ratings.remove_rating(mdb, a, "film", film_id)
    ratings.remove_rating(mdb, a, "film", film_id)  # idempotent
    ratings.remove_rating(mdb, a, "show", show_id)  # not rated: still a dismissal
    assert rating_rows(mdb) == [(b, film_id, None, 3)]
    assert [(d.profile_id, d.film_id, d.show_id) for d in dismissals(mdb)] == [
        (a, film_id, None),
        (a, None, show_id),
    ]


def test_dismiss_is_idempotent_keeps_the_first_dismissed_at_and_leaves_ratings(mdb, profiles):
    a, _, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        add_rating(s, a, film=film, stars_x2=7)
        add_rating_dismissal(s, a, film=film, dismissed_at=at(3))
        show_id = add_show(s, "Lost")[0].id
        film_id = film.id
    ratings.dismiss_prompt(mdb, a, "film", film_id)
    ratings.dismiss_prompt(mdb, a, "show", show_id)
    ratings.dismiss_prompt(mdb, a, "show", show_id)
    rows = dismissals(mdb)
    assert [(d.film_id, d.show_id) for d in rows] == [(film_id, None), (None, show_id)]
    assert rows[0].dismissed_at == at(3)
    assert rating_rows(mdb) == [(a, film_id, None, 7)]


@pytest.mark.parametrize("op", ["set", "remove", "dismiss"])
@pytest.mark.parametrize("kind", ["film", "show"])
def test_writes_for_a_deleted_profile_are_unauthorized(mdb, profiles, op, kind):
    a, _, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        show = add_show(s, "Lost")[0]
        item_id = film.id if kind == "film" else show.id
        s.execute(m.Profile.__table__.delete().where(m.Profile.id == a))
    call = {
        "set": lambda: ratings.set_rating(mdb, a, kind, item_id, 8),
        "remove": lambda: ratings.remove_rating(mdb, a, kind, item_id),
        "dismiss": lambda: ratings.dismiss_prompt(mdb, a, kind, item_id),
    }[op]
    with pytest.raises(ApiError) as exc:
        call()
    assert (exc.value.status_code, exc.value.code) == (401, "unauthorized")
    assert rating_rows(mdb) == [] and dismissals(mdb) == []


def test_remove_for_a_deleted_profile_rolls_back_the_delete(mdb, profiles):
    """The DELETE and the dismissal INSERT share one transaction: the 401 undoes both."""
    a, _, _ = profiles
    with mdb.write_session() as s:
        film_id = add_film(s, "Dune").id
    with mdb.write_session() as s:
        # The profile vanishes between the DELETE and the dismissal INSERT.
        s.execute(
            text(
                "CREATE TRIGGER drop_profile AFTER DELETE ON rating "
                "BEGIN DELETE FROM profile WHERE id = OLD.profile_id; END"
            )
        )
        add_rating(s, a, film=s.get(m.Film, film_id))
    with pytest.raises(ApiError) as exc:
        ratings.remove_rating(mdb, a, "film", film_id)
    assert exc.value.status_code == 401
    assert rating_rows(mdb) == [(a, film_id, None, 8)]
    assert dismissals(mdb) == []


@pytest.mark.parametrize("op", ["set", "remove", "dismiss"])
def test_writes_reraise_integrity_errors_other_than_the_profile_fk(mdb, profiles, op):
    from sqlalchemy.exc import IntegrityError

    a, _, _ = profiles
    with mdb.write_session() as s:
        film_id = add_film(s, "Dune").id
        for table in ("rating", "rating_dismissal"):
            s.execute(
                text(
                    f"CREATE TRIGGER reject_{table} BEFORE INSERT ON {table} "
                    "BEGIN SELECT RAISE(ABORT, 'rejected by trigger'); END"
                )
            )
    call = {
        "set": lambda: ratings.set_rating(mdb, a, "film", film_id, 8),
        "remove": lambda: ratings.remove_rating(mdb, a, "film", film_id),
        "dismiss": lambda: ratings.dismiss_prompt(mdb, a, "film", film_id),
    }[op]
    with pytest.raises(IntegrityError, match="rejected by trigger"):
        call()


# --- item_ratings / detail -------------------------------------------------------------------


def test_item_ratings_splits_mine_others_newest_first_and_average(mdb, profiles):
    a, b, c = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        lonely = add_film(s, "Lonely")
        show = add_show(s, "Lost")[0]
        add_rating(s, a, film=film, stars_x2=8, rated_at=at(1))
        add_rating(s, b, film=film, stars_x2=4, rated_at=at(5))
        add_rating(s, c, film=film, stars_x2=9, rated_at=at(5))  # tie on rated_at: id DESC
        add_rating(s, b, film=lonely, stars_x2=7)
        add_rating(s, a, show=show, stars_x2=10)
        add_rating(s, b, show=show, stars_x2=5)
        ids = film.id, lonely.id, show.id
    with mdb.read_session() as s:
        mine = catalog.item_ratings(s, a, "film", ids[0])
        assert mine.my_rating == 4.0
        assert [(o.profile.name, o.stars) for o in mine.others] == [("Esther", 4.5), ("Luis", 2.0)]
        assert mine.others[0].profile.avatar_id == "avatar-02"
        assert mine.others[0].rated_at == at(5)
        assert mine.average is not None
        assert (mine.average.stars, mine.average.count) == (3.5, 3)
        lonely_for_a = catalog.item_ratings(s, a, "film", ids[1])
        assert lonely_for_a.my_rating is None
        assert [o.profile.name for o in lonely_for_a.others] == ["Luis"]
        assert lonely_for_a.average is None  # one rating: no average
        only_mine = catalog.item_ratings(s, b, "film", ids[1])
        assert (only_mine.my_rating, only_mine.others, only_mine.average) == (3.5, [], None)
        show_for_c = catalog.item_ratings(s, c, "show", ids[2])
        assert show_for_c.my_rating is None
        assert show_for_c.average is not None
        assert (show_for_c.average.stars, show_for_c.average.count) == (3.8, 2)  # 3.75 half up


def test_details_carry_the_rating_block(mdb, profiles):
    a, b, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        show = add_show(s, "Lost")[0]
        add_rating(s, a, film=film, stars_x2=8)
        add_rating(s, b, show=show, stars_x2=3)
        film_id, show_id = film.id, show.id
    with mdb.read_session() as s:
        fd = catalog.film_detail(s, b, film_id, 10)
        assert fd.my_rating is None
        assert [(o.profile.id, o.stars) for o in fd.ratings] == [(a, 4.0)]
        assert fd.household_average is None
        sd = catalog.show_detail(s, b, show_id, 10)
        assert (sd.my_rating, sd.ratings, sd.household_average) == (1.5, [], None)


def test_details_spend_one_query_on_ratings(mdb, profiles):
    a, _, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        show = add_show(s, "Lost")[0]
        film_id, show_id = film.id, show.id
    with mdb.read_session() as s, capture_selects(mdb) as statements:
        catalog.film_detail(s, a, film_id, 10)
        catalog.show_detail(s, a, show_id, 10)
    rating_reads = [st for st, _ in statements if "FROM rating" in st]
    assert len(rating_reads) == 2


# --- my_rating on summaries ------------------------------------------------------------------


def test_summaries_carry_the_requesters_rating(mdb, profiles):
    a, b, _ = profiles
    with mdb.write_session() as s:
        film, other = add_film(s, "Dune"), add_film(s, "Other")
        show = add_show(s, "Lost")[0]
        add_rating(s, a, film=film, stars_x2=9)
        add_rating(s, b, film=other, stars_x2=2)
        add_rating(s, b, show=show, stars_x2=6)
        film_ids = [film.id, other.id]
        show_id = show.id
    with mdb.read_session() as s:
        films = s.scalars(select(m.Film).where(m.Film.id.in_(film_ids)).order_by(m.Film.id)).all()
        shows = s.scalars(select(m.Show).where(m.Show.id == show_id)).all()
        assert [x.my_rating for x in catalog.film_summaries(s, a, films)] == [4.5, None]
        assert [x.my_rating for x in catalog.film_summaries(s, b, films)] == [None, 1.0]
        assert [x.my_rating for x in catalog.show_summaries(s, a, shows)] == [None]
        assert [x.my_rating for x in catalog.show_summaries(s, b, shows)] == [3.0]
        with capture_selects(mdb) as statements:
            assert catalog.film_summaries(s, a, []) == []
            assert catalog.show_summaries(s, a, []) == []
        assert statements == []


# --- list_activity ---------------------------------------------------------------------------


def test_activity_is_newest_first_with_ties_by_id_and_includes_own_rows(mdb, profiles):
    a, b, c = profiles
    with mdb.write_session() as s:
        dune, alien = add_film(s, "Dune"), add_film(s, "Alien")
        lost = add_show(s, "Lost")[0]
        add_rating(s, a, film=dune, stars_x2=9, rated_at=at(1))
        add_rating(s, b, film=dune, stars_x2=4, rated_at=at(3))
        add_rating(s, c, show=lost, stars_x2=10, rated_at=at(3))  # same time, later id
        add_rating(s, a, film=alien, stars_x2=1, rated_at=at(2))
        add_rating_dismissal(s, b, film=alien)  # never shown
    page = activity(mdb, a)
    assert page.total == 4
    assert [(e.profile.name, e.kind, e.item.title, e.stars) for e in page.items] == [
        ("Esther", "show", "Lost", 5.0),
        ("Luis", "film", "Dune", 2.0),
        ("Ana", "film", "Alien", 0.5),
        ("Ana", "film", "Dune", 4.5),
    ]
    assert all(e.kind == e.item.type for e in page.items)
    assert page.items[0].rated_at == at(3)
    assert page.items[1].profile.avatar_id == "avatar-07"


def test_activity_item_is_the_viewers_summary(mdb, profiles):
    a, b, _ = profiles
    with mdb.write_session() as s:
        dune = add_film(s, "Dune")
        lost, eps = add_show(s, "Lost", episodes={1: 2})
        add_rating(s, b, film=dune, stars_x2=4, rated_at=at(2))
        add_rating(s, b, show=lost, stars_x2=6, rated_at=at(1))
        add_rating(s, a, film=dune, stars_x2=9, rated_at=at(0))
        add_progress(s, a, film_id=dune.id, watched=True, position=0)
        add_progress(s, a, episode_id=eps[(1, 1)].id, watched=True)
    for_a = activity(mdb, a).items
    luis_dune, luis_lost = for_a[0], for_a[1]
    assert isinstance(luis_dune.item, FilmSummary) and isinstance(luis_lost.item, ShowSummary)
    assert luis_dune.stars == 2.0 and luis_dune.item.my_rating == 4.5
    assert luis_dune.item.progress is not None and luis_dune.item.progress.watched
    assert luis_lost.item.my_rating is None and luis_lost.item.unwatched_episode_count == 1
    for_b = activity(mdb, b).items
    assert for_b[0].item.my_rating == 2.0 and for_b[0].item.progress is None


def test_activity_hides_rows_of_hidden_titles_and_restores_them(mdb, profiles):
    a, b, _ = profiles
    with mdb.write_session() as s:
        dune, alien = add_film(s, "Dune"), add_film(s, "Alien")
        lost = add_show(s, "Lost")[0]
        add_rating(s, a, film=dune, rated_at=at(3))
        add_rating(s, b, film=alien, rated_at=at(2))
        add_rating(s, a, show=lost, rated_at=at(1))
        dune_id, lost_id = dune.id, lost.id
    with mdb.write_session() as s:
        s.execute(update(m.MediaFile).values(missing_since=T0))
        s.execute(
            update(m.MediaFile).where(m.MediaFile.film_id == alien.id).values(missing_since=None)
        )
    page = activity(mdb, a)
    assert (page.total, [e.item.title for e in page.items]) == (1, ["Alien"])
    with mdb.write_session() as s:
        s.execute(update(m.MediaFile).values(missing_since=None))
    page = activity(mdb, a)
    assert page.total == 3
    assert [(e.kind, e.item.id) for e in page.items] == [
        ("film", dune_id),
        ("film", alien.id),
        ("show", lost_id),
    ]


def test_activity_paging(mdb, profiles):
    a, b, c = profiles
    with mdb.write_session() as s:
        films = [add_film(s, f"F{i}") for i in range(3)]
        add_rating(s, a, film=films[0], rated_at=at(5))
        add_rating(s, b, film=films[1], rated_at=at(4))
        add_rating(s, c, film=films[2], rated_at=at(3))
        add_rating(s, a, film=films[1], rated_at=at(2))
        add_rating(s, b, film=films[2], rated_at=at(1))
    page = activity(mdb, a, page=2, page_size=2)
    assert (page.page, page.page_size, page.total) == (2, 2, 5)
    assert [(e.profile.name, e.item.title) for e in page.items] == [
        ("Esther", "F2"),
        ("Ana", "F1"),
    ]
    assert activity(mdb, a, page=4, page_size=2).items == []


def _count_activity_queries(db: Database, profile_id: int, page_size: int = 50) -> int:
    with capture_selects(db) as statements:
        page = activity(db, profile_id, page_size=page_size)
    assert page.total >= len(page.items)
    return len(statements)


def test_activity_query_count_is_constant(mdb, profiles):
    a, b, c = profiles
    assert _count_activity_queries(mdb, a) == 2  # empty: count + page only
    with mdb.write_session() as s:
        add_rating(s, a, film=add_film(s, "F"), rated_at=at(1))
        add_rating(s, b, show=add_show(s, "S")[0], rated_at=at(2))
    small = _count_activity_queries(mdb, a)
    assert small <= 8
    with mdb.write_session() as s:
        for i in range(30):
            for pid in (a, c):
                add_rating(s, pid, film=add_film(s, f"MF{i}{pid}"), rated_at=at(10 + 2 * i))
                add_rating(s, pid, show=add_show(s, f"MS{i}{pid}")[0], rated_at=at(11 + 2 * i))
    assert _count_activity_queries(mdb, a, page_size=200) == small
    assert _count_activity_queries(mdb, a, page_size=20) == small  # mixed page


def test_activity_never_touches_the_write_engine(mdb, profiles):
    a, _, _ = profiles
    with mdb.write_session() as s:
        add_rating(s, a, film=add_film(s, "F"))
    with capture_writes(mdb) as statements:
        assert activity(mdb, a).total == 1
    assert statements == []


def test_service_does_not_import_the_dependency_layer():
    import subprocess
    import sys

    code = "import sys, hometv.ratings; print('hometv.api.deps' in sys.modules)"
    out = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, check=True)
    assert out.stdout.strip() == "False"
