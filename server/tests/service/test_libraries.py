"""LibraryFolders service: seeding, plan (removed / covered / moved / relayout), save,
folder status and counts."""

import logging
from pathlib import Path

import pytest
from sqlalchemy import event, select

from hometv.api.errors import ApiError
from hometv.config import load_settings
from hometv.db import models as m
from hometv.libraries import (
    LibraryFolders,
    LibraryFolderSet,
    PlanCovered,
    PlanMoved,
    PlanRelayout,
    PlanRemoved,
)
from hometv.scanner import ensure_libraries
from tests.factories import add_file, add_film, add_show


def _store(lf: LibraryFolders, films=(), shows=()) -> None:
    """Write stored folders directly (no validation) and reload the cache."""
    with lf.db.write_session() as s:
        libs = ensure_libraries(s)
        for row in s.scalars(select(m.LibraryFolder)):
            s.delete(row)
        s.flush()
        for path in films:
            s.add(m.LibraryFolder(library_id=libs["films"], path=str(path)))
        for path in shows:
            s.add(m.LibraryFolder(library_id=libs["shows"], path=str(path)))
        for lib in s.scalars(select(m.Library)):
            lib.folders_seeded_at = m.utcnow()
    lf.load()


def _markers(mdb) -> dict[str, bool]:
    with mdb.read_session() as s:
        return {lib.type: lib.folders_seeded_at is not None for lib in s.scalars(select(m.Library))}


@pytest.fixture
def M(tmp_path: Path) -> Path:
    base = tmp_path / "media"
    base.mkdir()
    return base


def _dirs(*paths: Path) -> None:
    for p in paths:
        p.mkdir(parents=True, exist_ok=True)


def _films(mdb, root: Path | str, count: int = 1, *, sub: str = "", missing: bool = False):
    """``count`` films, one file each under ``root/sub`` (root_path ``root``)."""
    ids = []
    with mdb.write_session() as s:
        for i in range(count):
            film = add_film(s, f"F {root} {sub} {i}", files=0)
            rel = f"{sub}/f{film.id}.mkv" if sub else f"f{film.id}.mkv"
            add_file(s, film_id=film.id, root=str(root), rel=rel, missing=missing)
            ids.append(film.id)
    return ids


def _show(mdb, root: Path | str, sub: str, title: str, *, missing: bool = False, library=None):
    with mdb.write_session() as s:
        show, eps = add_show(s, title, episodes={1: 2}, with_files=False)
        for (season, number), ep in eps.items():
            add_file(
                s,
                episode_ids=[ep.id],
                root=str(root),
                rel=f"{sub}/{title}.S{season:02d}E{number:02d}.mkv",
                missing=missing,
                library=library,
            )
        return show.id


# --- Seeding ------------------------------------------------------------------------------------


def test_seed_from_env_then_restart_ignores_env(mdb, tmp_path, caplog):
    env = {"HOMETV_DATA_DIR": str(tmp_path / "data"), "HOMETV_FILMS_PATHS": "/m/a,/m/b"}
    lf = LibraryFolders(mdb, load_settings(env))
    lf.seed_once()
    assert lf.current() == LibraryFolderSet(films=("/m/a", "/m/b"), shows=())
    assert _markers(mdb) == {"films": True, "shows": True}
    assert lf.ignored_env() == ["HOMETV_FILMS_PATHS"]

    env["HOMETV_FILMS_PATHS"] = "/m/c"
    again = LibraryFolders(mdb, load_settings(env))
    with caplog.at_level(logging.WARNING, logger="hometv"):
        again.seed_once()
    assert again.current() == LibraryFolderSet(films=("/m/a", "/m/b"), shows=())
    assert any(
        r.getMessage().startswith("library_config_ignored: HOMETV_FILMS_PATHS (")
        for r in caplog.records
    )


def test_seed_from_present_media_file_roots_only(mdb, make_settings):
    _films(mdb, "/media/Films", 2)
    _films(mdb, "/media/Old", 1, missing=True)
    _show(mdb, "/media/TV", "Lost", "Lost")
    lf = LibraryFolders(mdb, make_settings())
    lf.seed_once()
    assert lf.current() == LibraryFolderSet(films=("/media/Films",), shows=("/media/TV",))
    assert lf.ignored_env() == []


def test_seed_empty_then_env_is_ignored_and_listed(mdb, tmp_path):
    data = str(tmp_path / "data")
    lf = LibraryFolders(mdb, load_settings({"HOMETV_DATA_DIR": data}))
    lf.seed_once()
    assert lf.current() == LibraryFolderSet()
    assert _markers(mdb) == {"films": True, "shows": True}

    later = LibraryFolders(
        mdb, load_settings({"HOMETV_DATA_DIR": data, "HOMETV_FILMS_PATHS": "/m/a"})
    )
    later.seed_once()
    assert later.current() == LibraryFolderSet()
    assert later.ignored_env() == ["HOMETV_FILMS_PATHS"]


def test_saving_empty_lists_never_reseeds(mdb, make_settings, M):
    settings = make_settings(libraries={"films": [str(M)]})
    lf = LibraryFolders(mdb, settings)
    lf.seed_once()
    assert lf.current().films == (str(M),)
    lf.save([], [], confirm_relinks=False)
    restarted = LibraryFolders(mdb, settings)
    restarted.seed_once()
    assert restarted.current() == LibraryFolderSet()


def test_seed_drops_duplicates_with_warnings(mdb, tmp_path, caplog):
    env = {
        "HOMETV_DATA_DIR": str(tmp_path / "data"),
        "HOMETV_FILMS_PATHS": "/m/a,/m/a/",
        "HOMETV_SHOWS_PATHS": "/m/a,/m/tv",
    }
    lf = LibraryFolders(mdb, load_settings(env))
    with caplog.at_level(logging.WARNING, logger="hometv"):
        lf.seed_once()
    assert lf.current() == LibraryFolderSet(films=("/m/a",), shows=("/m/tv",))
    dropped = [r.getMessage() for r in caplog.records if "duplicate_dropped" in r.getMessage()]
    assert dropped == [
        "library_seed_duplicate_dropped: films /m/a",
        "library_seed_duplicate_dropped: shows /m/a",
    ]


def test_ignored_env_names_env_and_yaml_keys_but_not_empty_ones(mdb, tmp_path):
    data = tmp_path / "data"
    data.mkdir()
    (data / "config.yaml").write_text("libraries:\n  films: [/m/f]\n", encoding="utf-8")
    settings = load_settings({"HOMETV_DATA_DIR": str(data), "HOMETV_SHOWS_PATHS": "/m/tv"})
    assert LibraryFolders(mdb, settings).ignored_env() == ["libraries.films", "HOMETV_SHOWS_PATHS"]
    settings = load_settings({"HOMETV_DATA_DIR": str(tmp_path), "HOMETV_SHOWS_PATHS": ""})
    assert LibraryFolders(mdb, settings).ignored_env() == []


def test_seed_skips_a_path_already_stored_in_the_other_library(mdb, make_settings):
    settings = make_settings(libraries={"films": ["/m/x"], "shows": ["/m/tv"]})
    with mdb.write_session() as s:
        libs = ensure_libraries(s)
        s.add(m.LibraryFolder(library_id=libs["shows"], path="/m/x"))
        shows = s.scalar(select(m.Library).where(m.Library.type == "shows"))
        shows.folders_seeded_at = m.utcnow()
    lf = LibraryFolders(mdb, settings)
    lf.seed_once()
    assert lf.current() == LibraryFolderSet(films=(), shows=("/m/x",))


# --- Plan: moves ----------------------------------------------------------------------------------


@pytest.fixture
def lf(mdb, make_settings) -> LibraryFolders:
    service = LibraryFolders(mdb, make_settings())
    service.seed_once()
    return service


def test_plan_exact_move_films_to_shows(lf, mdb, M):
    _dirs(M / "Anime")
    _store(lf, films=[M / "Anime"])
    _films(mdb, M / "Anime", 2)
    _films(mdb, M / "Anime", 1, missing=True)
    plan = lf.plan([], [str(M / "Anime")])
    assert plan.moved == (PlanMoved("films", f"{M}/Anime", "shows", f"{M}/Anime", 3),)
    assert plan.removed == () and plan.covered == () and plan.relayout == ()
    assert plan.has_relinks


def test_plan_added_folder_contains_an_old_root_of_the_other_library(lf, mdb, M):
    _dirs(M / "TV")
    _store(lf, shows=[M / "TV"])
    _show(mdb, M / "TV", "Lost", "Lost")
    plan = lf.plan([str(M)], [])
    assert plan.moved == (PlanMoved("shows", f"{M}/TV", "films", str(M), 1),)
    assert plan.removed == ()


def test_plan_added_folder_inside_an_old_root(lf, mdb, M):
    _dirs(M / "all" / "TV")
    _store(lf, films=[M / "all"])
    _films(mdb, M / "all", 2, sub="TV")
    _films(mdb, M / "all", 3, sub="x")
    plan = lf.plan([], [str(M / "all" / "TV")])
    assert plan.moved == (PlanMoved("films", f"{M}/all", "shows", f"{M}/all/TV", 2),)
    assert plan.removed == (PlanRemoved("films", f"{M}/all", 3),)


def test_plan_readding_a_removed_folder_to_the_other_library(lf, mdb, M):
    _dirs(M / "X")
    _films(mdb, M / "X", 2, missing=True)  # root_unconfigured after an earlier save
    plan = lf.plan([], [str(M / "X")])
    assert plan.moved == (PlanMoved("films", f"{M}/X", "shows", f"{M}/X", 2),)
    assert plan.removed == ()


def test_plan_range_query_has_no_like_wildcards(lf, mdb, M):
    _dirs(M / "100%_a")
    _store(lf, films=[M / "100%_a", M / "100xya"])
    _films(mdb, M / "100%_a", 1)
    _films(mdb, M / "100xya", 4)
    plan = lf.plan([str(M / "100xya")], [str(M / "100%_a")])
    assert plan.moved == (PlanMoved("films", f"{M}/100%_a", "shows", f"{M}/100%_a", 1),)


def test_plan_additions_without_other_library_files_are_empty(lf, mdb, M):
    _dirs(M / "F", M / "Docs", M / "TV")
    _store(lf, films=[M / "F"])
    _films(mdb, M / "F", 2)
    plan = lf.plan([str(M / "F"), str(M / "Docs")], [str(M / "TV")])
    assert plan == type(plan)()
    assert not plan.has_relinks


def test_plan_legacy_nested_roots(lf, mdb, M):
    pel = M / "películas"
    _dirs(pel)
    _store(lf, films=[pel], shows=[M])
    _films(mdb, pel, 3)
    plan = lf.plan([], [str(M)])
    assert plan.moved == (PlanMoved("films", str(pel), "shows", str(M), 3),)
    assert plan.removed == ()


def test_plan_stale_library_id_shows_up_in_an_unrelated_save(lf, mdb, M):
    _dirs(M / "F", M / "S", M / "Docs")
    _store(lf, films=[M / "F"], shows=[M / "S"])
    _films(mdb, M / "S", 2)  # films-library rows left under the Series folder
    plan = lf.plan([str(M / "F"), str(M / "Docs")], [str(M / "S")])
    assert plan.moved == (PlanMoved("films", f"{M}/S", "shows", f"{M}/S", 2),)


# --- Plan: relayouts ------------------------------------------------------------------------------


def test_plan_series_root_replaced_by_subfolder_is_a_relayout(lf, mdb, M):
    _dirs(M / "series")
    _store(lf, shows=[M])
    _show(mdb, M, "series/Naruto", "Naruto")
    _show(mdb, M, "series/Lost", "Lost")
    _show(mdb, M, "other/Bluey", "Bluey")
    plan = lf.plan([], [str(M / "series")])
    assert plan.relayout == (PlanRelayout(str(M), f"{M}/series", 2),)
    assert plan.removed == (PlanRemoved("shows", str(M), 1),)
    assert plan.moved == () and plan.covered == ()


def test_plan_series_root_replaced_by_its_show_folder(lf, mdb, M):
    series = M / "series"
    _dirs(series / "Naruto")
    _store(lf, shows=[series])
    _show(mdb, series, "Naruto", "Naruto")
    _show(mdb, series, "Lost", "Lost")
    plan = lf.plan([], [str(series / "Naruto")])
    assert plan.relayout == (PlanRelayout(str(series), f"{series}/Naruto", 1),)
    assert plan.removed == (PlanRemoved("shows", str(series), 1),)


def test_plan_readding_a_removed_series_root_as_is_is_no_relayout(lf, mdb, M):
    _dirs(M / "TV")
    _show(mdb, M / "TV", "Lost", "Lost", missing=True)
    plan = lf.plan([], [str(M / "TV")])
    assert plan.relayout == () and plan.moved == ()


def test_plan_films_root_replaced_by_parent_is_covered_never_relayout(lf, mdb, M):
    _dirs(M / "MKV")
    _store(lf, films=[M / "MKV"])
    _films(mdb, M / "MKV", 4)
    _films(mdb, M / "MKV", 1, missing=True)
    plan = lf.plan([str(M)], [])
    assert plan.covered == (PlanCovered("films", f"{M}/MKV", str(M), 4),)
    assert plan.removed == () and plan.relayout == () and plan.moved == ()
    assert not plan.has_relinks


# --- Plan: removals / validation ------------------------------------------------------------------


def test_plan_plain_removal_counts_present_titles_only(lf, mdb, M):
    _dirs(M / "Old", M / "F")
    _store(lf, films=[M / "F", M / "Old"])
    _films(mdb, M / "Old", 3)
    _films(mdb, M / "Old", 2, missing=True)
    plan = lf.plan([str(M / "F")], [])
    assert plan.removed == (PlanRemoved("films", f"{M}/Old", 3),)


def test_plan_invalid_body_is_422_with_every_failing_entry(lf, M):
    _dirs(M / "F")
    with pytest.raises(ApiError) as exc:
        lf.plan([str(M / "F"), "rel", str(M / "missing")], [str(M / "F")])
    err = exc.value
    assert (err.status_code, err.code) == (422, "invalid_library_folders")
    assert err.details == {
        "folders": [
            {"library": "films", "path": "rel", "code": "not_absolute", "other": None},
            {"library": "films", "path": f"{M}/missing", "code": "not_found", "other": None},
            {"library": "shows", "path": f"{M}/F", "code": "duplicate", "other": f"{M}/F"},
        ]
    }


def test_plan_writes_nothing(lf, mdb, M):
    _dirs(M / "F")
    lf.plan([str(M / "F")], [])
    with mdb.read_session() as s:
        assert s.scalars(select(m.LibraryFolder)).all() == []
    assert lf.current() == LibraryFolderSet()


# --- Save -----------------------------------------------------------------------------------------


def _stored_rows(mdb) -> list[tuple[int, str, str]]:
    with mdb.read_session() as s:
        return [
            (row.id, t, row.path)
            for row, t in s.execute(
                select(m.LibraryFolder, m.Library.type).join(m.Library).order_by(m.LibraryFolder.id)
            )
        ]


def test_save_replaces_rows_and_updates_current(lf, mdb, M):
    _dirs(M / "A", M / "B", M / "C", M / "TV")
    lf.save([str(M / "A"), str(M / "B")], [str(M / "TV")], confirm_relinks=False)
    assert lf.current() == LibraryFolderSet(films=(f"{M}/A", f"{M}/B"), shows=(f"{M}/TV",))
    snapshot = lf.current()
    result = lf.save([str(M / "C"), f"{M}/B/"], [str(M / "TV")], confirm_relinks=False)
    assert (
        result == lf.current() == LibraryFolderSet(films=(f"{M}/B", f"{M}/C"), shows=(f"{M}/TV",))
    )  # stored order = id order: B kept its row
    assert snapshot.films == (f"{M}/A", f"{M}/B")  # an earlier snapshot is immutable


def test_save_invalid_writes_nothing(lf, mdb, M):
    _dirs(M / "A")
    lf.save([str(M / "A")], [], confirm_relinks=False)
    before = _stored_rows(mdb)
    with pytest.raises(ApiError) as exc:
        lf.save([str(M / "nope")], [], confirm_relinks=True)
    assert exc.value.code == "invalid_library_folders"
    assert _stored_rows(mdb) == before


def test_save_move_needs_confirmation_then_updates_the_row(lf, mdb, M):
    _dirs(M / "X")
    lf.save([str(M / "X")], [], confirm_relinks=False)
    _films(mdb, M / "X", 2)
    before = _stored_rows(mdb)
    with pytest.raises(ApiError) as exc:
        lf.save([], [str(M / "X")], confirm_relinks=False)
    err = exc.value
    assert (err.status_code, err.code) == (409, "relinks_unconfirmed")
    assert err.details == {
        "moves": [
            {
                "from_library": "films",
                "from_path": f"{M}/X",
                "to_library": "shows",
                "to_path": f"{M}/X",
                "title_count": 2,
            }
        ],
        "relayouts": [],
    }
    assert _stored_rows(mdb) == before
    lf.save([], [str(M / "X")], confirm_relinks=True)
    assert _stored_rows(mdb) == [(before[0][0], "shows", f"{M}/X")]  # same row, UPDATE


def test_save_relayout_needs_confirmation(lf, mdb, M):
    _dirs(M / "series")
    _store(lf, shows=[M])
    _show(mdb, M, "series/Naruto", "Naruto")
    with pytest.raises(ApiError) as exc:
        lf.save([], [str(M / "series")], confirm_relinks=False)
    assert exc.value.details == {
        "moves": [],
        "relayouts": [{"from_path": str(M), "to_path": f"{M}/series", "title_count": 1}],
    }
    lf.save([], [str(M / "series")], confirm_relinks=True)
    assert lf.current() == LibraryFolderSet(shows=(f"{M}/series",))


# --- Status / counts ------------------------------------------------------------------------------


def _count_queries(mdb):
    statements: list[str] = []

    def before(conn, cursor, statement, *args):
        statements.append(statement)

    event.listen(mdb.read_engine, "before_cursor_execute", before)
    return statements, lambda: event.remove(mdb.read_engine, "before_cursor_execute", before)


def test_folder_status_counts_present_titles_with_constant_queries(lf, mdb, M, tmp_path):
    _dirs(M / "F", M / "TV")
    _films(mdb, M / "F", 2)
    _films(mdb, M / "F", 1, missing=True)
    with mdb.write_session() as s:  # a film with versions in two folders counts in both
        film = add_film(s, "Two", files=0)
        add_file(s, film_id=film.id, root=str(M / "F"), rel="two.mkv")
        add_file(s, film_id=film.id, root=str(M / "Gone"), rel="two.mkv")
    _show(mdb, M / "TV", "Lost", "Lost")
    _show(mdb, M / "TV", "Bluey", "Bluey", missing=True)
    _store(lf, films=[M / "F", M / "Gone"], shows=[M / "TV", M / "Empty"])

    statements, stop = _count_queries(mdb)
    try:
        with mdb.read_session() as s:
            films, shows = lf.folder_status(s)
        few = len(statements)
        _store(lf, films=[M / "F", M / "Gone", *(M / f"x{i}" for i in range(8))])
        statements.clear()
        with mdb.read_session() as s:
            lf.folder_status(s)
        assert len(statements) == few
    finally:
        stop()
    assert [(f.path, f.status, f.title_count) for f in films] == [
        (f"{M}/F", "available", 3),
        (f"{M}/Gone", "not_found", 1),
    ]
    assert [(f.path, f.status, f.title_count) for f in shows] == [
        (f"{M}/TV", "available", 1),
        (f"{M}/Empty", "not_found", 0),
    ]


def test_folder_status_not_readable(lf, mdb, M):
    locked = M / "locked"
    locked.mkdir()
    _store(lf, films=[locked])
    locked.chmod(0o000)
    try:
        with mdb.read_session() as s:
            films, _ = lf.folder_status(s)
    finally:
        locked.chmod(0o755)
    assert films[0].status == "not_readable"


def test_describe_and_browse_root(mdb, make_settings, M):
    lf = LibraryFolders(
        mdb, make_settings(libraries={"browse_root": str(M)}, scan={"missing_grace_days": 3})
    )
    lf.seed_once()
    with mdb.read_session() as s:
        body = lf.describe(s)
    assert body.browse_root.model_dump() == {"path": str(M), "available": True}
    assert body.missing_grace_days == 3
    assert body.ignored_env == []
    missing = LibraryFolders(mdb, make_settings(libraries={"browse_root": str(M / "nope")}))
    assert missing.browse_root().available is False
    with pytest.raises(ApiError) as exc:
        missing.browse(None)
    assert (exc.value.status_code, exc.value.code) == (409, "browse_unavailable")
