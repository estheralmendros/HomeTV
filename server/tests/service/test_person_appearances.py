"""``catalog.person_detail`` (actor screen FR-9/FR-10): visibility, TMDB ∪ local credits,
ordering, per-profile summaries and a bounded query count."""

from datetime import UTC, date, datetime

import pytest

from hometv import catalog
from hometv.api.errors import ApiError
from hometv.api.schemas import FilmSummary, ShowSummary
from hometv.db import models as m
from hometv.db.engine import Database
from tests.factories import (
    add_cast_credit,
    add_file,
    add_film,
    add_person,
    add_person_credit,
    add_progress,
    add_show,
)
from tests.stubs import capture_selects


@pytest.fixture
def profiles(mdb: Database) -> tuple[int, int]:
    with mdb.write_session() as s:
        a = m.Profile(name="A", avatar_id="avatar-01", is_admin=False)
        b = m.Profile(name="B", avatar_id="avatar-02", is_admin=False)
        s.add_all([a, b])
        s.flush()
        return a.id, b.id


def detail(db: Database, person_id: int, profile_id: int):
    with db.read_session() as s:
        return catalog.person_detail(s, profile_id, person_id)


def keys(result) -> list[tuple[str, str]]:
    return [(a.kind, a.item.title) for a in result.appearances]


def test_unknown_person_is_not_found(mdb, profiles):
    with pytest.raises(ApiError) as exc:
        detail(mdb, 999, profiles[0])
    assert (exc.value.status_code, exc.value.code) == (404, "not_found")
    assert exc.value.message == "Person 999 not found"


def test_person_fields_and_credits_complete(mdb, profiles):
    fetched_at = datetime(2026, 10, 1, tzinfo=UTC)
    with mdb.write_session() as s:
        never = add_person(s, "Never", profile_path="n.jpg").id
        done = add_person(
            s,
            "Done",
            biography="Bio",
            biography_language="en-US",
            birthday=date(1932, 10, 8),
            deathday=date(2010, 5, 3),
            place_of_birth="Madrid",
            details_fetched_at=fetched_at,
        ).id
    first = detail(mdb, never, profiles[0])
    assert first.person_id == never
    assert first.name == "Never"
    assert first.credits_complete is False
    assert first.profile_image is not None
    assert first.profile_image.small == "/api/artwork/w185/n.jpg"
    assert first.appearances == []
    second = detail(mdb, done, profiles[0])
    assert second.credits_complete is True
    assert second.profile_image is None
    assert (second.biography, second.biography_language) == ("Bio", "en-US")
    assert (second.birthday, second.deathday) == (date(1932, 10, 8), date(2010, 5, 3))
    assert second.place_of_birth == "Madrid"


def test_visibility_rules(mdb, profiles):
    with mdb.write_session() as s:
        p = add_person(s)
        ok = add_film(s, "Visible", tmdb_id=1)
        missing = add_film(s, "Missing", tmdb_id=2, files=0)
        add_file(s, film_id=missing.id, missing=True)
        unmatched = add_film(s, "Unmatched", tmdb_id=3, match_status="unmatched")
        pending = add_film(s, "Pending", tmdb_id=None, match_status="pending")
        no_tmdb = add_film(s, "MatchedNoTmdb", tmdb_id=None)
        show_ok, _ = add_show(s, "ShowVisible", tmdb_id=10)
        show_hidden, _ = add_show(s, "ShowHidden", tmdb_id=11, with_files=False)
        show_unmatched, _ = add_show(s, "ShowUnmatched", tmdb_id=12, match_status="unmatched")
        for film in (ok, missing, unmatched, pending, no_tmdb):
            add_cast_credit(s, p, film=film)
        for show in (show_ok, show_hidden, show_unmatched):
            add_cast_credit(s, p, show=show)
        for tmdb_id in (1, 2, 3):
            add_person_credit(s, p, "movie", tmdb_id)
        for tmdb_id in (10, 11, 12):
            add_person_credit(s, p, "tv", tmdb_id)
        add_person_credit(s, p, "movie", 777)  # not in the catalog
        pid = p.id
    assert sorted(keys(detail(mdb, pid, profiles[0]))) == [
        ("film", "Visible"),
        ("show", "ShowVisible"),
    ]


def test_tmdb_only_local_only_and_both(mdb, profiles):
    with mdb.write_session() as s:
        p = add_person(s)
        tmdb_only = add_film(s, "TmdbOnly", year=2003, tmdb_id=1)
        local_only = add_film(s, "LocalOnly", year=2002, tmdb_id=2)
        both = add_film(s, "Both", year=2001, tmdb_id=3)
        both_null = add_film(s, "BothNullTmdbChar", year=2000, tmdb_id=4)
        show_tmdb, _ = add_show(s, "OldSeasonShow", year=1999, tmdb_id=1)  # same id as a film
        show_local, _ = add_show(s, "LocalShow", year=1998, tmdb_id=20)
        add_person_credit(s, p, "movie", 1, character="T1")
        add_cast_credit(s, p, film=local_only, character="L2")
        add_person_credit(s, p, "movie", 3, character="T3")
        add_cast_credit(s, p, film=both, character="L3")
        add_person_credit(s, p, "movie", 4, character=None)
        add_cast_credit(s, p, film=both_null, character="L4")
        add_person_credit(s, p, "tv", 1, character="Finn")
        add_cast_credit(s, p, show=show_local, character=None)
        add_cast_credit(s, p, film=tmdb_only, character=None)  # local credit without character
        pid = p.id
        ids = {f.title: f.id for f in (tmdb_only, local_only, both, both_null)}
        show_ids = {show_tmdb.title: show_tmdb.id, show_local.title: show_local.id}
    result = detail(mdb, pid, profiles[0])
    got = [(a.kind, a.item.title, a.character, a.item.id) for a in result.appearances]
    assert got == [
        ("film", "TmdbOnly", "T1", ids["TmdbOnly"]),
        ("film", "LocalOnly", "L2", ids["LocalOnly"]),
        ("film", "Both", "T3", ids["Both"]),
        ("film", "BothNullTmdbChar", "L4", ids["BothNullTmdbChar"]),
        ("show", "OldSeasonShow", "Finn", show_ids["OldSeasonShow"]),
        ("show", "LocalShow", None, show_ids["LocalShow"]),
    ]
    for a in result.appearances:
        assert a.kind == a.item.type
        assert isinstance(a.item, FilmSummary if a.kind == "film" else ShowSummary)


def test_ordering(mdb, profiles):
    with mdb.write_session() as s:
        p = add_person(s)
        made = [
            ("film", add_film(s, "Beta film", year=2020, tmdb_id=1)),
            ("film", add_film(s, "Alpha film", year=2020, tmdb_id=2)),
            ("film", add_film(s, "Old", year=1990, tmdb_id=3)),
            ("film", add_film(s, "Zeta no year", year=None, tmdb_id=4)),
            ("film", add_film(s, "Alpha no year", year=None, tmdb_id=5)),
            ("show", add_show(s, "Same", year=2021, tmdb_id=6)[0]),
            ("film", add_film(s, "Same", year=2021, tmdb_id=7)),
            ("film", add_film(s, "Twin", year=2010, tmdb_id=8)),
            ("film", add_film(s, "Twin", year=2010, tmdb_id=9)),
            ("show", add_show(s, "New show", year=2024, tmdb_id=10)[0]),
        ]
        for _kind, item in made:
            add_cast_credit(s, p, **{"film" if isinstance(item, m.Film) else "show": item})
        pid = p.id
        twin_ids = sorted(item.id for _k, item in made if item.title == "Twin")
    result = detail(mdb, pid, profiles[0])
    assert keys(result) == [
        ("show", "New show"),
        ("film", "Same"),
        ("show", "Same"),
        ("film", "Alpha film"),
        ("film", "Beta film"),
        ("film", "Twin"),
        ("film", "Twin"),
        ("film", "Old"),
        ("film", "Alpha no year"),
        ("film", "Zeta no year"),
    ]
    assert [a.item.id for a in result.appearances if a.item.title == "Twin"] == twin_ids


def test_summaries_are_per_profile(mdb, profiles):
    a, b = profiles
    with mdb.write_session() as s:
        p = add_person(s)
        film = add_film(s, "Dune", tmdb_id=438631)
        _show, episodes = add_show(s, "Homeland", tmdb_id=1407, episodes={1: 3})
        add_person_credit(s, p, "movie", 438631)
        add_person_credit(s, p, "tv", 1407)
        add_progress(s, a, film_id=film.id, position=600, duration=5400)
        add_progress(s, a, episode_id=episodes[(1, 1)].id, watched=True, duration=1320)
        pid = p.id
    for_a = {x.kind: x.item for x in detail(mdb, pid, a).appearances}
    for_b = {x.kind: x.item for x in detail(mdb, pid, b).appearances}
    assert isinstance(for_a["film"], FilmSummary) and isinstance(for_b["film"], FilmSummary)
    assert for_a["film"].progress is not None
    assert for_a["film"].progress.position_seconds == 600
    assert for_b["film"].progress is None
    assert isinstance(for_a["show"], ShowSummary) and isinstance(for_b["show"], ShowSummary)
    assert (for_a["show"].episode_count, for_a["show"].unwatched_episode_count) == (3, 2)
    assert for_b["show"].unwatched_episode_count == 3


def _seed_appearances(db: Database, films: int, shows: int) -> int:
    with db.write_session() as s:
        p = add_person(s)
        for i in range(films):
            film = add_film(s, f"F{i}", year=2000 + i % 7, tmdb_id=1000 + i)
            if i % 2:
                add_person_credit(s, p, "movie", 1000 + i)
            else:
                add_cast_credit(s, p, film=film)
        for i in range(shows):
            show, _ = add_show(s, f"S{i}", year=2000 + i % 5, tmdb_id=5000 + i, episodes={1: 2})
            if i % 2:
                add_person_credit(s, p, "tv", 5000 + i)
            else:
                add_cast_credit(s, p, show=show)
        return p.id


@pytest.mark.parametrize(
    ("films", "shows", "expected"),
    [(0, 0, 5), (1, 0, 7), (0, 1, 7), (1, 1, 9), (15, 15, 9)],
)
def test_query_count_is_bounded(mdb, profiles, films, shows, expected):
    pid = _seed_appearances(mdb, films, shows)
    with capture_selects(mdb) as statements:
        result = detail(mdb, pid, profiles[0])
    assert len(result.appearances) == films + shows
    assert len(statements) == expected


def test_tmdb_credit_queries_seek_the_library_tmdb_index(mdb, profiles):
    pid = _seed_appearances(mdb, 3, 3)
    with capture_selects(mdb) as statements:
        detail(mdb, pid, profiles[0])
    plans = []
    with mdb.read_engine.connect() as conn:
        for sql, params in statements:
            if "person_credit" in sql:
                rows = conn.exec_driver_sql("EXPLAIN QUERY PLAN " + sql, params).all()
                plans.append(" | ".join(str(r[-1]) for r in rows))
    assert len(plans) == 2
    for plan in plans:
        assert "SCAN film" not in plan and "SCAN show" not in plan, plan
        assert "SEARCH person_credit" in plan, plan
        assert (
            "uq_film_library_id_tmdb_id (library_id=? AND tmdb_id=?)" in plan
            or "uq_show_library_id_tmdb_id (library_id=? AND tmdb_id=?)" in plan
        ), plan
