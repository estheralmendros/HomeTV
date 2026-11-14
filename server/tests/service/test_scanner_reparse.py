"""Scanner parse upgrade: rows written by an older parser are corrected by the next scan.

Rows are seeded by scanning real files and then rewriting them as parser version 1 left them
(``parser_version`` NULL, the wrong title, ``_v1``).
"""

import logging
import re
from pathlib import Path
from typing import Any

import pytest
from sqlalchemy import delete, func, select

from hometv.changes.scope import ScanScope
from hometv.db import models as m
from hometv.metadata.enrich import merge_items
from hometv.parser import PARSER_VERSION
from hometv.scanner import Scanner, find_film
from hometv.text import normalize_title, search_text, sort_title
from tests.factories import add_film, add_profile, add_progress, add_rating, add_track_choice
from tests.stubs import capture_selects, capture_writes

from .conftest import MediaTree, StubProbe
from .test_scanner import _chmod_tree, _tree_checksum

V1_PARSED = {"kind": "film", "year": 2020, "part": None}
UPGRADE_LINE = re.compile(
    r"parse upgrade: stamped=(\d+), aliased=(\d+), reparsed=(\d+), renamed=(\d+), merged=(\d+), "
    r"split=(\d+), deferred=(\d+), conflicts=(\d+)"
)
LINE_KEYS = (
    "stamped",
    "aliased",
    "reparsed",
    "renamed",
    "merged",
    "split",
    "deferred",
    "conflicts",
)


async def _scan(mdb, settings, stub_probe, **kw):
    return await Scanner(mdb, settings, probe_fn=stub_probe, **kw).run()


def _file(mdb, path: Path) -> m.MediaFile:
    with mdb.read_session() as s:
        row = s.scalar(select(m.MediaFile).where(m.MediaFile.path == str(path)))
        assert row is not None
        return row


def _film(mdb, film_id: int) -> m.Film | None:
    with mdb.read_session() as s:
        return s.get(m.Film, film_id)


def _films(mdb) -> list[m.Film]:
    with mdb.read_session() as s:
        return list(s.scalars(select(m.Film).order_by(m.Film.id)))


def _aliases(mdb) -> list[tuple[str, int | None, int | None]]:
    with mdb.read_session() as s:
        return [
            (a.parsed_title_norm, a.parsed_year, a.film_id) for a in s.scalars(select(m.ItemAlias))
        ]


def _v1(
    mdb,
    *paths: Path,
    title: str,
    year: int | None = 2020,
    status: str = "pending",
    tmdb_id: int | None = None,
    entry: bool = False,
    manual: tuple[Path, ...] = (),
) -> int:
    """One film with the wrong v1 title for ``paths``; files get v1 ``parsed``, NULL stamp."""
    with mdb.write_session() as s:
        film = m.Film(
            library_id=s.scalars(select(m.Library.id).where(m.Library.type == "films")).one(),
            match_status=status,
            tmdb_id=tmdb_id,
            parsed_title=title,
            parsed_title_norm=normalize_title(title),
            parsed_year=year,
            title=title,
            year=year,
            sort_title=sort_title(title),
            search_text=search_text(title, None),
        )
        s.add(film)
        s.flush()
        for path in paths:
            row = s.scalar(select(m.MediaFile).where(m.MediaFile.path == str(path)))
            assert row is not None
            row.film_id = film.id
            row.parsed = V1_PARSED | {"title": title, "year": year}
            row.parser_version = None
            if path in manual:
                row.manual_match = {"tmdb_id": tmdb_id}
        s.flush()
        s.execute(
            delete(m.Film).where(
                m.Film.id.not_in(
                    select(m.MediaFile.film_id).where(m.MediaFile.film_id.is_not(None))
                )
            )
        )
        if entry:
            s.add(m.UnmatchedEntry(film_id=film.id, reason="no_tmdb_results"))
        return film.id


def _attach(mdb, film_id: int) -> None:
    """Progress, rating, "Mi lista" and a track choice of profile P1 on the film."""
    with mdb.write_session() as s:
        profile = s.scalar(select(m.Profile).where(m.Profile.name == "P1")) or add_profile(s, "P1")
        film = s.get(m.Film, film_id)
        assert film is not None
        add_progress(s, profile.id, film_id=film_id, position=100.0)
        add_rating(s, profile.id, film=film)
        s.add(m.WatchlistEntry(profile_id=profile.id, film_id=film_id))
        add_track_choice(s, profile.id, film=film)


def _owned(mdb, film_id: int) -> tuple[int, int, int, int]:
    with mdb.read_session() as s:
        return tuple(  # type: ignore[return-value]
            s.scalar(select(func.count()).select_from(model).where(model.film_id == film_id))
            for model in (m.PlaybackProgress, m.Rating, m.WatchlistEntry, m.TrackChoice)
        )


def _upgrade_line(caplog) -> dict[str, int]:
    lines = [
        match
        for record in caplog.records
        if (match := UPGRADE_LINE.search(record.getMessage())) is not None
    ]
    assert lines, [r.getMessage() for r in caplog.records]
    return dict(zip(LINE_KEYS, map(int, lines[-1].groups()), strict=True))  # last run's line


@pytest.fixture
def settings(media_settings):
    return media_settings()


@pytest.fixture(autouse=True)
def _info_logs(caplog):
    caplog.set_level(logging.INFO, logger="hometv.scanner")


# --- stamping and find_film -----------------------------------------------------------------


async def test_new_files_are_stamped_and_existing_show_rows_stay_null(
    mdb, media: MediaTree, settings, stub_probe: StubProbe
):
    media.add("Dune (2021).mkv")
    media.show("Lost/Season 1/Lost.S01E01.mkv")
    await _scan(mdb, settings, stub_probe)
    assert {r.parser_version for r in _all_files(mdb)} == {PARSER_VERSION}

    with mdb.write_session() as s:
        s.execute(
            m.MediaFile.__table__.update()
            .where(m.MediaFile.root_path == str(media.shows))
            .values(parser_version=None)
        )
    await _scan(mdb, settings, stub_probe)
    by_root = {r.root_path: r.parser_version for r in _all_files(mdb)}
    assert by_root[str(media.shows)] is None
    assert by_root[str(media.films)] == PARSER_VERSION


def _all_files(mdb) -> list[m.MediaFile]:
    with mdb.read_session() as s:
        return list(s.scalars(select(m.MediaFile)))


def test_find_film_looks_up_alias_then_film_and_never_creates(mdb):
    with mdb.write_session() as s:
        film = add_film(s, "Dune", year=2021, files=0)
        other = add_film(s, "Otra", year=1999, files=0)
        s.add(
            m.ItemAlias(
                library_id=film.library_id,
                parsed_title_norm="arrakis",
                parsed_year=None,
                film_id=other.id,
            )
        )
        library_id = film.library_id
        film_id, other_id = film.id, other.id
    with mdb.write_session() as s:
        assert find_film(s, library_id, "DUNE", 2021) == film_id
        assert find_film(s, library_id, "Arrakis", None) == other_id
        assert find_film(s, library_id, "Dune", 2020) is None
        assert s.scalar(select(func.count()).select_from(m.Film)) == 2


# --- rename in place ------------------------------------------------------------------------


@pytest.mark.parametrize("status", ["pending", "unmatched"])
async def test_pending_and_unmatched_films_are_renamed_in_place(
    mdb, media: MediaTree, settings, stub_probe: StubProbe, caplog, status
):
    path = media.add("Te quiero (2020).mkv")
    await _scan(mdb, settings, stub_probe)
    film_id = _v1(mdb, path, title="quiero", status=status, entry=True)
    _attach(mdb, film_id)
    stub_probe.calls.clear()

    await _scan(mdb, settings, stub_probe)

    film = _film(mdb, film_id)
    assert film is not None
    assert (film.parsed_title, film.title, film.parsed_title_norm, film.parsed_year) == (
        "Te quiero",
        "Te quiero",
        "te quiero",
        2020,
    )
    assert film.match_status == "pending"
    assert film.sort_title == sort_title("Te quiero")
    assert film.search_text == search_text("Te quiero", None)
    with mdb.read_session() as s:
        assert s.scalar(select(func.count()).select_from(m.UnmatchedEntry)) == 0
    row = _file(mdb, path)
    assert (row.film_id, row.parser_version) == (film_id, PARSER_VERSION)
    assert row.parsed is not None and row.parsed["title"] == "Te quiero"
    assert _owned(mdb, film_id) == (1, 1, 1, 1)
    assert stub_probe.calls == []
    assert _upgrade_line(caplog)["renamed"] == 1
    assert len(_films(mdb)) == 1


async def test_punctuation_retitle_updates_titles_and_keeps_the_status(
    mdb, media: MediaTree, settings, stub_probe: StubProbe
):
    path = media.add("Dune - Parte 2 (2024).mkv")
    await _scan(mdb, settings, stub_probe)
    film_id = _v1(mdb, path, title="Dune: Parte 2", year=2024)
    with mdb.write_session() as s:
        film = s.get(m.Film, film_id)
        assert film is not None
        film.parsed_title_norm = normalize_title("Dune - Parte 2")
        film.match_status = "unmatched"

    await _scan(mdb, settings, stub_probe)

    film = _film(mdb, film_id)
    assert film is not None
    assert (film.parsed_title, film.title, film.match_status) == (
        "Dune - Parte 2",
        "Dune - Parte 2",
        "unmatched",
    )


# --- merge ----------------------------------------------------------------------------------


async def test_new_key_owned_by_another_film_merges_into_it(
    mdb, media: MediaTree, settings, stub_probe: StubProbe, caplog
):
    first = media.add("Te quiero (2020).mkv")
    second = media.add("otra copia/Te quiero (2020).mkv")
    await _scan(mdb, settings, stub_probe)
    target_id = _file(mdb, second).film_id
    film_id = _v1(mdb, first, title="quiero")
    _attach(mdb, film_id)

    await _scan(mdb, settings, stub_probe)

    assert _film(mdb, film_id) is None
    assert _file(mdb, first).film_id == target_id == _file(mdb, second).film_id
    assert _file(mdb, first).parser_version == PARSER_VERSION
    assert _owned(mdb, target_id) == (1, 1, 1, 1)
    assert ("quiero", 2020, target_id) in _aliases(mdb)
    assert _upgrade_line(caplog)["merged"] == 1


async def test_new_key_owned_through_an_alias_merges_into_its_film(
    mdb, media: MediaTree, settings, stub_probe: StubProbe
):
    path = media.add("Te quiero (2020).mkv")
    await _scan(mdb, settings, stub_probe)
    film_id = _v1(mdb, path, title="quiero")
    _attach(mdb, film_id)
    with mdb.write_session() as s:
        target = add_film(s, "Te quiero (TMDB)", year=2020, files=0, tmdb_id=7)
        s.add(
            m.ItemAlias(
                library_id=target.library_id,
                parsed_title_norm="te quiero",
                parsed_year=2020,
                film_id=target.id,
            )
        )
        target_id = target.id

    await _scan(mdb, settings, stub_probe)

    assert _film(mdb, film_id) is None
    assert _file(mdb, path).film_id == target_id
    target = _film(mdb, target_id)
    assert target is not None and target.match_status == "matched"
    assert _owned(mdb, target_id) == (1, 1, 1, 1)


# --- split ----------------------------------------------------------------------------------


async def test_split_keeps_the_matching_file_and_relinks_the_other(
    mdb, media: MediaTree, settings, stub_probe: StubProbe
):
    keep = media.add("Otra (2019).mkv")
    move = media.add("Te quiero (2020).mkv")
    await _scan(mdb, settings, stub_probe)
    film_id = _v1(mdb, keep, move, title="Otra", year=2019)
    _attach(mdb, film_id)

    await _scan(mdb, settings, stub_probe)

    assert _file(mdb, keep).film_id == film_id
    moved_to = _file(mdb, move).film_id
    assert moved_to not in (None, film_id)
    new = _film(mdb, moved_to)
    assert new is not None
    assert (new.parsed_title, new.match_status) == ("Te quiero", "pending")
    assert _owned(mdb, film_id) == (1, 1, 1, 1)
    assert {_file(mdb, p).parser_version for p in (keep, move)} == {PARSER_VERSION}


async def test_split_leaving_no_file_merges_into_the_majority_film(
    mdb, media: MediaTree, settings, stub_probe: StubProbe, caplog
):
    one = media.add("Uno (2020).mkv")
    two_a = media.add("Dos (2020).mkv")
    two_b = media.add("copia/Dos (2020).mkv")
    await _scan(mdb, settings, stub_probe)
    film_id = _v1(mdb, one, two_a, two_b, title="zzz")
    _attach(mdb, film_id)

    await _scan(mdb, settings, stub_probe)

    assert _film(mdb, film_id) is None
    majority = _file(mdb, two_a).film_id
    assert majority == _file(mdb, two_b).film_id
    other = _file(mdb, one).film_id
    assert other not in (None, majority)
    assert _owned(mdb, majority) == (1, 1, 1, 1)
    assert _owned(mdb, other) == (0, 0, 0, 0)
    assert _upgrade_line(caplog)["split"] == 1


async def test_a_file_that_no_longer_parses_as_a_film_leaves_with_an_entry(
    mdb, media: MediaTree, settings, stub_probe: StubProbe
):
    film_file = media.add("Te quiero (2020).mkv")
    part_file = media.add("Saga/Saga.2020.cd1.avi")
    await _scan(mdb, settings, stub_probe)
    film_id = _v1(mdb, film_file, part_file, title="quiero")
    _attach(mdb, film_id)

    await _scan(mdb, settings, stub_probe)

    part = _file(mdb, part_file)
    assert part.film_id is None and part.part_number == 1
    assert (part.parser_version, (part.parsed or {}).get("part")) == (PARSER_VERSION, 1)
    with mdb.read_session() as s:
        reasons = list(s.scalars(select(m.UnmatchedEntry.reason)))
    assert reasons == ["multi_part"]
    survivor = _file(mdb, film_file).film_id
    assert survivor is not None
    assert _owned(mdb, survivor) == (1, 1, 1, 1)  # the emptied film merged into the one left
    assert len(_films(mdb)) == 1


# --- protected films ------------------------------------------------------------------------


async def test_protected_film_keeps_its_identity_and_gains_an_alias(
    mdb, media: MediaTree, settings, stub_probe: StubProbe, caplog
):
    path = media.add("Te quiero (2020).mkv")
    await _scan(mdb, settings, stub_probe)
    film_id = _v1(mdb, path, title="quiero", status="matched", tmdb_id=5)
    _attach(mdb, film_id)
    before_film, before_file = _film(mdb, film_id), _file(mdb, path)

    await _scan(mdb, settings, stub_probe)

    film, row = _film(mdb, film_id), _file(mdb, path)
    assert film is not None and before_film is not None
    assert (film.parsed_title, film.parsed_title_norm, film.title, film.match_status) == (
        before_film.parsed_title,
        before_film.parsed_title_norm,
        before_film.title,
        before_film.match_status,
    )
    assert (row.film_id, row.parsed) == (film_id, before_file.parsed)
    assert row.parser_version == PARSER_VERSION
    assert _aliases(mdb) == [("te quiero", 2020, film_id)]
    assert len(_films(mdb)) == 1
    assert _owned(mdb, film_id) == (1, 1, 1, 1)
    line = _upgrade_line(caplog)
    assert (line["aliased"], line["stamped"]) == (1, 1)


async def test_film_with_a_manual_match_file_is_protected_and_the_manual_file_only_stamped(
    mdb, media: MediaTree, settings, stub_probe: StubProbe, caplog
):
    manual = media.add("Te quiero (2020).mkv")
    plain = media.add("copia/Te quiero (2020).mkv")
    await _scan(mdb, settings, stub_probe)
    film_id = _v1(mdb, manual, plain, title="quiero", tmdb_id=5, manual=(manual,))
    _attach(mdb, film_id)
    before = _file(mdb, manual)

    await _scan(mdb, settings, stub_probe)

    film = _film(mdb, film_id)
    assert film is not None
    assert (film.parsed_title, film.match_status) == ("quiero", "pending")
    for path in (manual, plain):
        row = _file(mdb, path)
        assert (row.film_id, row.parser_version, row.parsed) == (
            film_id,
            PARSER_VERSION,
            before.parsed,
        )
    assert _file(mdb, manual).manual_match == before.manual_match
    assert _aliases(mdb) == [("te quiero", 2020, film_id)]
    line = _upgrade_line(caplog)
    assert (line["aliased"], line["stamped"]) == (1, 2)


async def test_protected_film_absorbs_a_pending_film_owning_the_new_key(
    mdb, media: MediaTree, settings, stub_probe: StubProbe
):
    path = media.add("Te quiero (2020).mkv")
    duplicate = media.add("copia/Te quiero (2020).mkv")
    await _scan(mdb, settings, stub_probe)
    pending_id = _file(mdb, duplicate).film_id
    _attach(mdb, pending_id)
    film_id = _v1(mdb, path, title="quiero", status="matched", tmdb_id=5)

    await _scan(mdb, settings, stub_probe)

    assert _film(mdb, pending_id) is None
    assert _file(mdb, duplicate).film_id == film_id
    film = _film(mdb, film_id)
    assert film is not None and film.match_status == "matched"
    assert _owned(mdb, film_id) == (1, 1, 1, 1)


async def test_two_protected_films_for_one_key_are_a_conflict(
    mdb, media: MediaTree, settings, stub_probe: StubProbe, caplog
):
    path = media.add("Te quiero (2020).mkv")
    other = media.add("copia/Te quiero (2020).mkv")
    await _scan(mdb, settings, stub_probe)
    other_id = _file(mdb, other).film_id
    with mdb.write_session() as s:
        film = s.get(m.Film, other_id)
        assert film is not None
        film.match_status, film.tmdb_id = "matched", 9
    film_id = _v1(mdb, path, title="quiero", status="matched", tmdb_id=5)

    await _scan(mdb, settings, stub_probe)

    assert _file(mdb, path).film_id == film_id
    assert _file(mdb, path).parser_version == PARSER_VERSION
    assert _aliases(mdb) == []
    assert {f.id for f in _films(mdb)} == {film_id, other_id}
    line = _upgrade_line(caplog)
    assert (line["conflicts"], line["aliased"], line["merged"]) == (1, 0, 0)


async def test_changing_a_protected_file_after_the_upgrade_keeps_it_on_its_film(
    mdb, media: MediaTree, settings, stub_probe: StubProbe
):
    path = media.add("Te quiero (2020).mkv")
    await _scan(mdb, settings, stub_probe)
    film_id = _v1(mdb, path, title="quiero", status="matched", tmdb_id=5)
    _attach(mdb, film_id)
    await _scan(mdb, settings, stub_probe)

    media.touch(path)
    await _scan(mdb, settings, stub_probe)

    assert _file(mdb, path).film_id == film_id
    assert [f.id for f in _films(mdb)] == [film_id]
    assert _owned(mdb, film_id) == (1, 1, 1, 1)


async def test_changed_protected_file_in_the_upgrade_run_stays_on_its_film(
    mdb, media: MediaTree, settings, stub_probe: StubProbe
):
    path = media.add("Te quiero (2020).mkv")
    await _scan(mdb, settings, stub_probe)
    film_id = _v1(mdb, path, title="quiero", status="matched", tmdb_id=5)
    _attach(mdb, film_id)
    media.touch(path)

    await _scan(mdb, settings, stub_probe)

    assert _file(mdb, path).film_id == film_id
    assert _file(mdb, path).parser_version == PARSER_VERSION
    assert [f.id for f in _films(mdb)] == [film_id]
    assert ("te quiero", 2020, film_id) in _aliases(mdb)
    assert _owned(mdb, film_id) == (1, 1, 1, 1)


# --- changed during the upgrade, one work per row -------------------------------------------


async def test_changed_file_of_a_pending_film_is_probed_once_and_renamed_by_its_group(
    mdb, media: MediaTree, settings, stub_probe: StubProbe, monkeypatch
):
    path = media.add("Te quiero (2020).mkv")
    await _scan(mdb, settings, stub_probe)
    film_id = _v1(mdb, path, title="quiero")
    _attach(mdb, film_id)
    file_id = _file(mdb, path).id
    cache = settings.subtitles_dir / str(file_id)
    cache.mkdir(parents=True)
    (cache / "sub.vtt").write_text("x")
    media.touch(path)
    stub_probe.calls.clear()
    returned: list[set[int]] = []
    original = Scanner._apply_chunk

    def spy(self, chunk, outcomes):
        result = original(self, chunk, outcomes)
        returned.append(result)
        return result

    monkeypatch.setattr(Scanner, "_apply_chunk", spy)

    await _scan(mdb, settings, stub_probe)

    assert stub_probe.calls == ["Te quiero (2020).mkv"]
    film = _film(mdb, film_id)
    assert film is not None and film.parsed_title == "Te quiero"
    assert [f.id for f in _films(mdb)] == [film_id]
    assert _file(mdb, path).film_id == film_id
    assert _owned(mdb, film_id) == (1, 1, 1, 1)
    assert not cache.exists()
    assert returned == [{file_id}]


@pytest.mark.parametrize("linked", [False, True], ids=["unlinked-no_title", "no_title-reparsed"])
async def test_unlinked_no_title_row_is_reparsed_into_a_film(
    mdb, media: MediaTree, settings, stub_probe: StubProbe, monkeypatch, linked
):
    path = media.add("Te quiero (2020).mkv")
    await _scan(mdb, settings, stub_probe)
    with mdb.write_session() as s:
        row = s.scalar(select(m.MediaFile))
        assert row is not None
        film_id = row.film_id
        row.film_id = None
        row.parsed = None
        row.parser_version = None
        if linked:  # also failed to probe: the single work probes and reparses
            row.probe, row.probe_error = None, "boom"
        s.flush()
        s.execute(delete(m.Film).where(m.Film.id == film_id))
        s.add(m.UnmatchedEntry(media_file_id=row.id, reason="no_title", detail="x"))
        if linked:
            s.add(m.UnmatchedEntry(media_file_id=row.id, reason="probe_failed", detail="boom"))
    stub_probe.calls.clear()
    chunks: list[list[Any]] = []
    original = Scanner._apply_chunk

    def spy(self, chunk, outcomes):
        chunks.append(list(chunk))
        return original(self, chunk, outcomes)

    monkeypatch.setattr(Scanner, "_apply_chunk", spy)

    await _scan(mdb, settings, stub_probe)

    row = _file(mdb, path)
    assert row.film_id is not None
    assert (row.parser_version, row.probe_error) == (PARSER_VERSION, None)
    with mdb.read_session() as s:
        assert s.scalar(select(func.count()).select_from(m.UnmatchedEntry)) == 0
    assert stub_probe.calls == (["Te quiero (2020).mkv"] if linked else [])
    assert [len(chunk) for chunk in chunks] == [1]


async def test_unchanged_file_with_a_probe_error_keeps_its_single_work_and_joins_the_group(
    mdb, media: MediaTree, settings, stub_probe: StubProbe
):
    path = media.add("Te quiero (2020).mkv")
    await _scan(mdb, settings, stub_probe)
    film_id = _v1(mdb, path, title="quiero")
    with mdb.write_session() as s:
        row = s.scalar(select(m.MediaFile))
        assert row is not None
        row.probe, row.probe_error = None, "boom"
    stub_probe.calls.clear()

    await _scan(mdb, settings, stub_probe)

    assert stub_probe.calls == ["Te quiero (2020).mkv"]
    film = _film(mdb, film_id)
    assert film is not None and film.parsed_title == "Te quiero"
    assert _file(mdb, path).probe_error is None


# --- concurrency and interruption -----------------------------------------------------------


async def test_admin_match_before_the_group_transaction_is_not_overwritten(
    mdb, media: MediaTree, settings, stub_probe: StubProbe, monkeypatch, caplog
):
    path = media.add("Te quiero (2020).mkv")
    await _scan(mdb, settings, stub_probe)
    film_id = _v1(mdb, path, title="quiero")
    original = Scanner._apply_film_group

    def admin_matches_first(self, *args, **kwargs):
        with mdb.write_session() as s:
            film = s.get(m.Film, film_id)
            assert film is not None
            film.match_status, film.tmdb_id, film.title = "matched", 5, "Te quiero (TMDB)"
        return original(self, *args, **kwargs)

    monkeypatch.setattr(Scanner, "_apply_film_group", admin_matches_first)

    await _scan(mdb, settings, stub_probe)

    film = _film(mdb, film_id)
    assert film is not None
    assert (film.parsed_title, film.title, film.match_status) == (
        "quiero",
        "Te quiero (TMDB)",
        "matched",
    )
    row = _file(mdb, path)
    assert (row.parser_version, row.film_id) == (None, film_id)
    assert _upgrade_line(caplog)["deferred"] == 1


async def test_film_merged_away_before_its_group_is_skipped(
    mdb, media: MediaTree, settings, stub_probe: StubProbe, monkeypatch
):
    path = media.add("Te quiero (2020).mkv")
    await _scan(mdb, settings, stub_probe)
    film_id = _v1(mdb, path, title="quiero")
    with mdb.write_session() as s:
        survivor_id = add_film(s, "Otra", files=0).id
    original = Scanner._apply_film_group

    def admin_merges_first(self, *args, **kwargs):
        with mdb.write_session() as s:
            loser, survivor = s.get(m.Film, film_id), s.get(m.Film, survivor_id)
            assert loser is not None and survivor is not None
            merge_items(s, "film", loser, survivor)
        return original(self, *args, **kwargs)

    monkeypatch.setattr(Scanner, "_apply_film_group", admin_merges_first)

    await _scan(mdb, settings, stub_probe)

    assert _file(mdb, path).film_id == survivor_id
    assert _file(mdb, path).parser_version is None
    assert [f.id for f in _films(mdb)] == [survivor_id]


async def test_interrupted_upgrade_completes_on_the_next_run(
    mdb, media: MediaTree, settings, stub_probe: StubProbe, monkeypatch
):
    first = media.add("Te quiero (2020).mkv")
    second = media.add("Es lo que hay (2019).mkv")
    await _scan(mdb, settings, stub_probe)
    ids = (
        _v1(mdb, first, title="quiero"),
        _v1(mdb, second, title="lo que hay", year=2019),
    )
    for film_id in ids:
        _attach(mdb, film_id)
    monkeypatch.setattr("hometv.scanner.BATCH_SIZE", 1)  # one group per transaction
    original = Scanner._apply_film_group
    calls: list[int] = []

    def killed_after_first(self, *args, **kwargs):
        result = original(self, *args, **kwargs)
        calls.append(1)
        if len(calls) == 1:
            raise RuntimeError("killed")
        return result

    monkeypatch.setattr(Scanner, "_apply_film_group", killed_after_first)
    with pytest.raises(RuntimeError, match="killed"):
        await _scan(mdb, settings, stub_probe)
    assert [f.parsed_title for f in _films(mdb)] == ["Te quiero", "lo que hay"]
    assert [_file(mdb, p).parser_version for p in (first, second)] == [PARSER_VERSION, None]

    monkeypatch.setattr(Scanner, "_apply_film_group", original)
    await _scan(mdb, settings, stub_probe)

    assert [(f.id, f.parsed_title, f.match_status) for f in _films(mdb)] == [
        (ids[0], "Te quiero", "pending"),
        (ids[1], "Es lo que hay", "pending"),
    ]
    assert [_file(mdb, p).parser_version for p in (first, second)] == [PARSER_VERSION] * 2
    assert [_owned(mdb, i) for i in ids] == [(1, 1, 1, 1)] * 2


async def test_second_scan_writes_nothing_for_upgraded_rows(
    mdb, media: MediaTree, settings, stub_probe: StubProbe
):
    first = media.add("Te quiero (2020).mkv")
    matched = media.add("Otra (2019).mkv")
    plain = media.add("Dune (2021).mkv")
    await _scan(mdb, settings, stub_probe)
    _v1(mdb, first, title="quiero")
    _v1(mdb, matched, title="otra cosa", year=2019, status="matched", tmdb_id=3)
    with mdb.write_session() as s:
        s.execute(
            m.MediaFile.__table__.update()
            .where(m.MediaFile.path == str(plain))
            .values(parser_version=None)
        )
    await _scan(mdb, settings, stub_probe)

    with capture_writes(mdb) as statements:
        await _scan(mdb, settings, stub_probe)

    touching = [
        s
        for s in statements
        if re.match(r"\s*(UPDATE|INSERT)", s, re.I)
        and re.search(r"\b(media_file|film|item_alias)\b", s)
    ]
    assert touching == []


async def test_scoped_run_seeing_one_of_two_files_defers_the_film(
    mdb, media: MediaTree, settings, stub_probe: StubProbe, caplog
):
    a = media.add("A/Te quiero (2020).mkv")
    b = media.add("B/Te quiero (2020).mkv")
    await _scan(mdb, settings, stub_probe)
    film_id = _v1(mdb, a, b, title="quiero")

    await _scan(mdb, settings, stub_probe, scope=[ScanScope(str(media.films), "A", True)])

    film = _film(mdb, film_id)
    assert film is not None and film.parsed_title == "quiero"
    assert [_file(mdb, p).parser_version for p in (a, b)] == [None, None]
    assert _upgrade_line(caplog)["deferred"] == 1

    await _scan(mdb, settings, stub_probe)
    film = _film(mdb, film_id)
    assert film is not None and film.parsed_title == "Te quiero"
    assert [_file(mdb, p).parser_version for p in (a, b)] == [PARSER_VERSION] * 2


async def test_upgrade_leaves_the_read_only_media_tree_untouched(
    mdb, media: MediaTree, settings: Any, stub_probe: StubProbe
):
    path = media.add("Te quiero (2020).mkv", b"video")
    media.add("Dune (2021).mkv", b"video")
    await _scan(mdb, settings, stub_probe)
    _v1(mdb, path, title="quiero")
    _chmod_tree(media.base, writable=False)
    try:
        before = _tree_checksum(media.base)
        await _scan(mdb, settings, stub_probe)
        after = _tree_checksum(media.base)
    finally:
        _chmod_tree(media.base, writable=True)
    assert before == after
    assert _file(mdb, path).parser_version == PARSER_VERSION


async def test_eligibility_is_read_in_one_query_per_chunk_of_candidates(
    mdb, media: MediaTree, settings, stub_probe: StubProbe
):
    paths = [media.add(f"Peli {n} (2020).mkv") for n in range(6)]
    await _scan(mdb, settings, stub_probe)
    for n, path in enumerate(paths):
        _v1(mdb, path, title=f"peli {n} x")

    with capture_selects(mdb) as selects:
        await _scan(mdb, settings, stub_probe)

    assert len([sql for sql, _ in selects if "json_type" in sql]) == 1


async def test_film_whose_only_file_stops_parsing_as_a_film_is_kept_untouched(
    mdb, media: MediaTree, settings, stub_probe: StubProbe, caplog
):
    part = media.add("Saga/Saga.2020.cd1.avi")
    await _scan(mdb, settings, stub_probe)
    film_id = _v1(mdb, part, title="saga cd1")
    _attach(mdb, film_id)
    with mdb.write_session() as s:  # v1 linked the file to the film: no file-level entry
        s.execute(delete(m.UnmatchedEntry))
    before = _file(mdb, part)

    await _scan(mdb, settings, stub_probe)

    film = _film(mdb, film_id)
    assert film is not None and film.parsed_title == "saga cd1"
    row = _file(mdb, part)
    assert (row.film_id, row.parsed, row.part_number) == (
        film_id,
        before.parsed,
        before.part_number,
    )
    assert row.parser_version == PARSER_VERSION
    assert _owned(mdb, film_id) == (1, 1, 1, 1)
    with mdb.read_session() as s:
        assert s.scalar(select(func.count()).select_from(m.UnmatchedEntry)) == 0
    assert _upgrade_line(caplog)["deferred"] == 1

    with capture_writes(mdb) as statements:
        await _scan(mdb, settings, stub_probe)
    assert [
        s
        for s in statements
        if re.match(r"\s*(UPDATE|INSERT)", s, re.I) and re.search(r"(media_file|film)", s)
    ] == []
