"""Scanner service tests (PRD §4): tmp media trees, migrated SQLite, stub probe."""

import errno
import hashlib
import os
import stat as stat_mod
from datetime import timedelta
from pathlib import Path

import pytest
from sqlalchemy import func, select

from hometv.db import models as m
from hometv.scan_report import ScanReport
from hometv.scanner import FsOps, Scanner
from tests.factories import add_profile

from .conftest import MediaTree, StubProbe


def _scanner(mdb, settings, stub_probe, **kw) -> Scanner:
    return Scanner(mdb, settings, probe_fn=stub_probe, **kw)


async def _scan(mdb, settings, stub_probe, **kw) -> ScanReport:
    return await _scanner(mdb, settings, stub_probe, **kw).run()


def _all(mdb, model):
    with mdb.read_session() as s:
        return list(s.scalars(select(model)))


def _file(mdb, path: Path) -> m.MediaFile | None:
    with mdb.read_session() as s:
        return s.scalar(select(m.MediaFile).where(m.MediaFile.path == str(path)))


def _count(mdb, model) -> int:
    with mdb.read_session() as s:
        return s.scalar(select(func.count()).select_from(model))


def _entries(mdb) -> list[tuple[int | None, str]]:
    with mdb.read_session() as s:
        return [(e.media_file_id, e.reason) for e in s.scalars(select(m.UnmatchedEntry))]


def _profile_id(s) -> int:
    profile = s.scalar(select(m.Profile).where(m.Profile.name == "P1"))
    if profile is None:
        profile = add_profile(s, "P1")
    return profile.id


def _list_everything(mdb) -> None:
    """Put every film and show in profile P1's "Mi lista"."""
    with mdb.write_session() as s:
        pid = _profile_id(s)
        s.add_all(m.WatchlistEntry(profile_id=pid, film_id=f.id) for f in s.scalars(select(m.Film)))
        s.add_all(m.WatchlistEntry(profile_id=pid, show_id=x.id) for x in s.scalars(select(m.Show)))


def _listed_titles(mdb) -> list[str]:
    """Titles with a "Mi lista" row (hidden or not), sorted."""
    with mdb.read_session() as s:
        films = s.scalars(
            select(m.Film.title).join(m.WatchlistEntry, m.WatchlistEntry.film_id == m.Film.id)
        )
        shows = s.scalars(
            select(m.Show.title).join(m.WatchlistEntry, m.WatchlistEntry.show_id == m.Show.id)
        )
        return sorted([*films, *shows])


# --- First scan / unchanged / changed --------------------------------------------------------


async def test_first_scan_creates_items_versions_and_sidecars(
    mdb, media: MediaTree, media_settings, stub_probe: StubProbe
):
    dune = media.add("Dune (2021)/Dune (2021).mkv")
    media.add("Dune (2021)/Dune (2021) 4K.mp4")
    media.add("Dune (2021)/Dune (2021).es.forced.srt")
    media.add("Dune (2021)/Extras/Making of.mkv")  # ignored
    media.add("Dune (2021)/notes.txt")  # not a candidate
    media.show("The Office (2005)/Season 2/The.Office.S02E03.mkv")
    media.show("The Office (2005)/Season 2/The.Office.S02E04.mkv")
    settings = media_settings()

    report = await _scan(mdb, settings, stub_probe)

    assert report.counts["files_seen"] == 5
    assert report.counts["new"] == 4
    assert report.counts["ignored"] == 1
    assert report.counts["unchanged"] == 0
    assert report.errors == []
    films = _all(mdb, m.Film)
    assert [(f.title, f.year, f.match_status) for f in films] == [("Dune", 2021, "pending")]
    assert films[0].parsed_title_norm == "dune"
    assert films[0].sort_title == "dune"
    files = _all(mdb, m.MediaFile)
    assert len(files) == 4
    assert sum(1 for f in files if f.film_id == films[0].id) == 2  # two versions
    shows = _all(mdb, m.Show)
    assert [(s.title, s.year) for s in shows] == [("The Office", 2005)]
    seasons = _all(mdb, m.Season)
    assert [s.season_number for s in seasons] == [2]
    assert sorted(e.episode_number for e in _all(mdb, m.Episode)) == [3, 4]
    assert _count(mdb, m.MediaFileEpisode) == 2
    subs = _all(mdb, m.SubtitleFile)
    assert len(subs) == 1
    dune_row = _file(mdb, dune)
    assert subs[0].media_file_id == dune_row.id
    assert (subs[0].language, subs[0].forced, subs[0].format, subs[0].kind) == (
        "spa",
        True,
        "srt",
        "text",
    )
    assert dune_row.relative_path == "Dune (2021)/Dune (2021).mkv"
    assert dune_row.root_path == str(media.films)
    assert dune_row.parsed == {"kind": "film", "title": "Dune", "year": 2021, "part": None}
    assert dune_row.container == "mkv"
    assert dune_row.duration_seconds == 5400.0
    assert dune_row.video_codec == "h264"
    assert (dune_row.width, dune_row.height) == (1920, 1080)
    assert dune_row.probe["container"] == "mkv"
    ep_file = _file(mdb, media.shows / "The Office (2005)/Season 2/The.Office.S02E03.mkv")
    assert ep_file.parsed == {
        "kind": "episode",
        "show_title": "The Office",
        "show_year": 2005,
        "season": 2,
        "episodes": [3],
    }
    assert stub_probe.lock_held_during_probe is False
    libs = {lib.type for lib in _all(mdb, m.Library)}
    assert libs == {"films", "shows"}


async def test_second_scan_without_changes_runs_no_probe(
    mdb, media: MediaTree, media_settings, stub_probe
):
    media.add("Dune (2021)/Dune (2021).mkv")
    media.add("Dune (2021)/Extras/Making of.mkv")
    media.show("Lost/Season 1/Lost.S01E01.mkv")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe)
    stub_probe.calls.clear()

    report = await _scan(mdb, settings, stub_probe)

    assert stub_probe.calls == []
    assert report.counts["unchanged"] == report.counts["files_seen"] - report.counts["ignored"]
    assert report.counts["unchanged"] == 2
    assert report.counts["new"] == 0
    assert report.counts["changed"] == 0


async def test_touching_a_file_reprobes_only_that_file(
    mdb, media: MediaTree, media_settings, stub_probe
):
    a = media.add("Dune (2021)/Dune (2021).mkv")
    media.add("Alien (1979)/Alien (1979).mkv")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe)
    before = _file(mdb, a)
    stub_probe.calls.clear()

    media.touch(a)
    report = await _scan(mdb, settings, stub_probe)

    assert stub_probe.calls == ["Dune (2021).mkv"]
    assert report.counts["changed"] == 1
    assert report.counts["unchanged"] == 1
    after = _file(mdb, a)
    assert after.id == before.id
    assert after.film_id == before.film_id
    assert after.size_bytes == len(b"changed!")


async def test_changed_file_drops_keyframe_index_and_subtitle_cache(
    mdb, media: MediaTree, media_settings, stub_probe
):
    a = media.add("Dune (2021)/Dune (2021).mkv")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe)
    row = _file(mdb, a)
    with mdb.write_session() as s:
        s.add(m.KeyframeIndex(media_file_id=row.id, source="mkv_cues", times=b"x"))
    cache = settings.subtitles_dir / str(row.id)
    cache.mkdir(parents=True)
    (cache / "emb-3.vtt").write_text("WEBVTT")

    media.touch(a)
    await _scan(mdb, settings, stub_probe)

    assert _count(mdb, m.KeyframeIndex) == 0
    assert not cache.exists()


# --- Missing / restore / purge ---------------------------------------------------------------


async def test_deleted_file_is_marked_missing_and_restored_with_same_ids(
    mdb, media: MediaTree, media_settings, stub_probe
):
    a = media.add("Dune (2021)/Dune (2021).mkv")
    media.add("Alien (1979)/Alien (1979).mkv")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe)
    row = _file(mdb, a)
    with mdb.write_session() as s:
        profile = m.Profile(name="Esther", avatar_id="avatar-01")
        s.add(profile)
        s.flush()
        s.add(m.PlaybackProgress(profile_id=profile.id, film_id=row.film_id, position_seconds=60))

    a.unlink()
    report = await _scan(mdb, settings, stub_probe)

    assert report.counts["missing"] == 1
    missing = _file(mdb, a)
    assert missing.missing_since is not None
    assert missing.film_id == row.film_id
    assert _count(mdb, m.PlaybackProgress) == 1
    assert _count(mdb, m.Film) == 2

    media.add("Dune (2021)/Dune (2021).mkv")  # same content -> same size, new mtime
    report = await _scan(mdb, settings, stub_probe)

    assert report.counts["restored"] == 1
    restored = _file(mdb, a)
    assert restored.id == row.id
    assert restored.missing_since is None
    assert restored.film_id == row.film_id
    assert _count(mdb, m.PlaybackProgress) == 1


async def test_grace_zero_purges_file_and_orphans(
    mdb, media: MediaTree, media_settings, stub_probe
):
    a = media.add("Dune (2021)/Dune (2021).mkv")
    media.add("Alien (1979)/Alien (1979).mkv")
    e1 = media.show("Lost/Season 1/Lost.S01E01.mkv")
    media.show("Fargo/Season 1/Fargo.S01E01.mkv")  # keeps the shows root non-empty
    settings = media_settings(scan={"missing_grace_days": 0})
    await _scan(mdb, settings, stub_probe)
    _list_everything(mdb)

    a.unlink()
    e1.unlink()
    report = await _scan(mdb, settings, stub_probe)

    assert report.counts["missing"] == 2
    assert report.counts["removed"] == 2
    assert _file(mdb, a) is None
    assert [f.title for f in _all(mdb, m.Film)] == ["Alien"]
    assert [s.title for s in _all(mdb, m.Show)] == ["Fargo"]
    assert _count(mdb, m.Season) == 1
    assert _count(mdb, m.Episode) == 1
    assert _listed_titles(mdb) == ["Alien", "Fargo"]  # purged titles' entries cascade


async def test_missing_file_is_purged_only_after_grace_period(
    mdb, media: MediaTree, media_settings, stub_probe
):
    a = media.add("Dune (2021)/Dune (2021).mkv")
    media.add("Alien (1979)/Alien (1979).mkv")
    settings = media_settings(scan={"missing_grace_days": 7})
    await _scan(mdb, settings, stub_probe)
    _list_everything(mdb)
    a.unlink()
    await _scan(mdb, settings, stub_probe)
    assert _listed_titles(mdb) == ["Alien", "Dune"]  # inside the grace period: kept
    row = _file(mdb, a)
    with mdb.write_session() as s:
        s.get(m.MediaFile, row.id).missing_since = m.utcnow() - timedelta(days=8)

    report = await _scan(mdb, settings, stub_probe)

    assert report.counts["removed"] == 1
    assert _file(mdb, a) is None
    assert [f.title for f in _all(mdb, m.Film)] == ["Alien"]
    assert _listed_titles(mdb) == ["Alien"]


async def test_relinking_every_file_of_a_title_drops_its_watchlist_entries(
    mdb, media: MediaTree, media_settings, stub_probe
):
    """FR-5 (Implementation Decision 11): the old item loses its last file, ``delete_orphans``
    deletes it at the end of the scan and its entries cascade; no carry-over to the new item."""
    a = media.add("Dune (2021)/Dune (2021).mkv")
    media.add("Alien (1979)/Alien (1979).mkv")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe)
    with mdb.write_session() as s:
        dune = s.scalar(select(m.Film).where(m.Film.title == "Dune"))
        alien = s.scalar(select(m.Film).where(m.Film.title == "Alien"))
        pid = _profile_id(s)
        s.add(m.WatchlistEntry(profile_id=pid, film_id=dune.id))
        s.get(m.MediaFile, _file(mdb, a).id).film_id = alien.id  # what every relink ends with
        dune_id = dune.id

    await _scan(mdb, settings, stub_probe)

    with mdb.read_session() as s:
        assert s.get(m.Film, dune_id) is None
    assert _all(mdb, m.WatchlistEntry) == []


# --- Root safety ------------------------------------------------------------------------------


async def test_missing_root_never_marks_files_missing(
    mdb, media: MediaTree, media_settings, stub_probe
):
    a = media.add("Dune (2021)/Dune (2021).mkv")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe)
    a.unlink()
    a.parent.rmdir()
    media.films.rmdir()

    report = await _scan(mdb, settings, stub_probe)

    assert f"root_unavailable: {media.films}" in report.errors
    assert report.counts["missing"] == 0
    assert _file(mdb, a).missing_since is None


async def test_empty_root_with_db_rows_skips_removal(
    mdb, media: MediaTree, media_settings, stub_probe
):
    a = media.add("Dune (2021)/Dune (2021).mkv")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe)
    a.unlink()

    report = await _scan(mdb, settings, stub_probe)

    assert f"root_empty_skipped_removal: {media.films}" in report.errors
    assert report.counts["missing"] == 0
    assert _file(mdb, a).missing_since is None


async def test_root_stat_failing_at_end_of_walk_protects_root(
    mdb, media: MediaTree, media_settings, stub_probe
):
    a = media.add("Dune (2021)/Dune (2021).mkv")
    media.add("Alien (1979)/Alien (1979).mkv")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe)
    a.unlink()

    calls = {"n": 0}

    def flaky_stat(path, *args, **kwargs):
        if os.fspath(path) == str(media.films):
            calls["n"] += 1
            if calls["n"] >= 2:  # the end-of-walk check
                raise OSError(errno.EIO, "I/O error", os.fspath(path))
        return os.stat(path, *args, **kwargs)

    report = await _scan(mdb, settings, stub_probe, fs=FsOps(stat=flaky_stat))

    assert any(e.startswith(f"walk_error: {media.films}: EIO") for e in report.errors)
    assert report.counts["missing"] == 0
    assert _file(mdb, a).missing_since is None


async def test_walk_errors_are_scoped_to_the_failed_subtree(
    mdb, media: MediaTree, media_settings, stub_probe, tmp_path
):
    deleted = media.add("Dune (2021)/Dune (2021).mkv")
    media.add("Alien (1979)/Alien (1979).mkv")
    locked_file = media.add("Locked/Heat (1995).mkv")
    eio_file = media.add("Brazil (1985)/Brazil (1985).mkv")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe)

    deleted.unlink()
    (media.films / "Ghost (1990).mkv").symlink_to(tmp_path / "nowhere.mkv")
    locked_dir = media.films / "Locked"

    real_scandir = os.scandir

    def scandir(path):
        if os.fspath(path) == str(locked_dir):
            raise PermissionError(errno.EACCES, "Permission denied", os.fspath(path))
        return real_scandir(path)

    def stat(path, *args, **kwargs):
        if os.fspath(path) == str(eio_file):
            raise OSError(errno.EIO, "I/O error", os.fspath(path))
        return os.stat(path, *args, **kwargs)

    fs = FsOps(scandir=scandir, stat=stat)
    for run in range(2):
        report = await _scan(mdb, settings, stub_probe, fs=fs)
        assert report.counts["missing"] == (1 if run == 0 else 0)
        assert _file(mdb, deleted).missing_since is not None
        assert _file(mdb, locked_file).missing_since is None
        assert _file(mdb, eio_file).missing_since is None
        assert f"walk_error: {locked_dir}: EACCES" in report.errors
        assert f"walk_error: {eio_file}: EIO" in report.errors
        broken = [e for e in report.errors if e.startswith("broken_symlink:")]
        assert broken == [f"broken_symlink: {media.films / 'Ghost (1990).mkv'}"]
        assert not any("Ghost" in e for e in report.errors if e.startswith("walk_error"))


@pytest.mark.skipif(os.geteuid() == 0, reason="root ignores chmod 000")
async def test_unreadable_directory_chmod_protects_its_files(
    mdb, media: MediaTree, media_settings, stub_probe
):
    deleted = media.add("Dune (2021)/Dune (2021).mkv")
    media.add("Alien (1979)/Alien (1979).mkv")
    locked_file = media.add("Locked/Heat (1995).mkv")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe)
    deleted.unlink()
    locked_dir = media.films / "Locked"
    locked_dir.chmod(0)
    try:
        report = await _scan(mdb, settings, stub_probe)
    finally:
        locked_dir.chmod(0o755)

    assert report.counts["missing"] == 1
    assert _file(mdb, locked_file).missing_since is None
    assert f"walk_error: {locked_dir}: EACCES" in report.errors


async def test_removing_root_from_config_marks_missing_and_readding_restores(
    mdb, media: MediaTree, media_settings, stub_probe
):
    a = media.add("Dune (2021)/Dune (2021).mkv")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe)
    film_id = _file(mdb, a).film_id

    without = media_settings(libraries={"films": [], "shows": [str(media.shows)]})
    report = await _scan(mdb, without, stub_probe)
    assert report.counts["missing"] == 1
    assert f"root_unconfigured: {media.films}" in report.errors
    assert _file(mdb, a).missing_since is not None

    report = await _scan(mdb, settings, stub_probe)
    assert report.counts["restored"] == 1
    row = _file(mdb, a)
    assert row.missing_since is None
    assert row.film_id == film_id


def _tree_checksum(root: Path) -> str:
    h = hashlib.sha256()
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        for name in sorted(filenames):
            p = Path(dirpath) / name
            st = p.stat()
            h.update(str(p.relative_to(root)).encode())
            h.update(p.read_bytes())
            h.update(str(st.st_mtime_ns).encode())
            h.update(str(stat_mod.S_IMODE(st.st_mode)).encode())
        h.update(("|".join(sorted(dirnames))).encode())
    return h.hexdigest()


def _chmod_tree(root: Path, writable: bool) -> None:
    for dirpath, dirnames, filenames in os.walk(root, topdown=not writable):
        for name in [*filenames, *dirnames]:
            p = Path(dirpath) / name
            mode = stat_mod.S_IMODE(p.stat().st_mode)
            p.chmod(mode | 0o200 if writable else mode & ~0o222)
    mode = stat_mod.S_IMODE(root.stat().st_mode)
    root.chmod(mode | 0o200 if writable else mode & ~0o222)


async def test_read_only_media_tree_is_scanned_and_left_byte_identical(
    mdb, media: MediaTree, media_settings, stub_probe
):
    media.add("Dune (2021)/Dune (2021).mkv", b"video")
    media.add("Dune (2021)/Dune (2021).en.srt", b"1\n00:00:01,000 --> 00:00:02,000\nHi\n")
    media.show("Lost/Season 1/Lost.S01E01.mkv", b"ep")
    _chmod_tree(media.base, writable=False)
    try:
        before = _tree_checksum(media.base)
        settings = media_settings()
        report = await _scan(mdb, settings, stub_probe)
        await _scan(mdb, settings, stub_probe)
        after = _tree_checksum(media.base)
    finally:
        _chmod_tree(media.base, writable=True)
    assert report.counts["new"] == 2
    assert report.errors == []
    assert _count(mdb, m.SubtitleFile) == 1
    assert before == after


# --- Unmatched entries, probe failures, multi-part ------------------------------------------


async def test_probe_failure_creates_probe_failed_entry_and_heals(
    mdb, media: MediaTree, media_settings, stub_probe
):
    good = media.add("Dune (2021)/Dune (2021).mkv")
    bad = media.add("Alien (1979)/Alien (1979).mkv")
    both = media.show("Lost/Lost 01.mkv")  # no_season parse reason + probe failure
    stub_probe.fail("Alien (1979).mkv", "Lost 01.mkv")
    settings = media_settings()

    report = await _scan(mdb, settings, stub_probe)

    bad_row, both_row = _file(mdb, bad), _file(mdb, both)
    assert bad_row.probe_error == "Invalid data found when processing input"
    assert bad_row.film_id is not None  # still linked
    entries = set(_entries(mdb))
    assert (bad_row.id, "probe_failed") in entries
    assert (both_row.id, "probe_failed") in entries
    assert (both_row.id, "no_season") in entries
    assert report.counts["unmatched"] == 3
    assert _file(mdb, good).probe_error is None

    stub_probe.heal("Alien (1979).mkv", "Lost 01.mkv")
    stub_probe.calls.clear()
    await _scan(mdb, settings, stub_probe)

    assert sorted(stub_probe.calls) == ["Alien (1979).mkv", "Lost 01.mkv"]
    assert _file(mdb, bad).probe_error is None
    assert set(_entries(mdb)) == {(both_row.id, "no_season")}


async def test_unparseable_files_are_stored_unlinked_with_entry(
    mdb, media: MediaTree, media_settings, stub_probe
):
    f = media.show("Lost/Lost 01.mkv")
    settings = media_settings()
    report = await _scan(mdb, settings, stub_probe)
    row = _file(mdb, f)
    assert row.parsed is None
    assert row.film_id is None
    assert _count(mdb, m.MediaFileEpisode) == 0
    assert _entries(mdb) == [(row.id, "no_season")]
    assert report.counts["unmatched"] == 1
    with mdb.read_session() as s:
        assert s.scalar(select(m.UnmatchedEntry.detail))

    report = await _scan(mdb, settings, stub_probe)
    assert report.counts["unmatched"] == 0
    assert len(_entries(mdb)) == 1


async def test_multi_episode_file_links_all_episodes_and_cd_parts_are_multi_part(
    mdb, media: MediaTree, media_settings, stub_probe
):
    multi = media.show("The Office/Season 1/The.Office.S01E01-E02.mkv")
    cd1 = media.add("Kill Bill Vol 1 (2003)/Kill.Bill.Vol.1.2003.cd1.avi")
    cd2 = media.add("Kill Bill Vol 1 (2003)/Kill.Bill.Vol.1.2003.cd2.avi")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe)

    with mdb.read_session() as s:
        row = s.scalar(select(m.MediaFile).where(m.MediaFile.path == str(multi)))
        eps = s.scalars(
            select(m.Episode.episode_number)
            .join(m.MediaFileEpisode, m.MediaFileEpisode.episode_id == m.Episode.id)
            .where(m.MediaFileEpisode.media_file_id == row.id)
        ).all()
    assert sorted(eps) == [1, 2]
    r1, r2 = _file(mdb, cd1), _file(mdb, cd2)
    assert r1.film_id is None and r2.film_id is None
    assert (r1.part_number, r2.part_number) == (1, 2)
    assert r1.parsed == {"kind": "film", "title": "Kill Bill Vol 1", "year": 2003, "part": 1}
    assert set(_entries(mdb)) == {(r1.id, "multi_part"), (r2.id, "multi_part")}
    assert _count(mdb, m.Film) == 0


async def test_manual_match_overrides_parse_on_changed_file(
    mdb, media: MediaTree, media_settings, stub_probe
):
    f = media.add("Weird/zzz.mkv")
    other = media.add("Dune (2021)/Dune (2021).mkv")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe)
    dune = _file(mdb, other)
    with mdb.write_session() as s:
        film = s.get(m.Film, dune.film_id)
        film.tmdb_id, film.match_status = 438631, "matched"
        s.get(m.MediaFile, _file(mdb, f).id).manual_match = {"tmdb_id": 438631}

    media.touch(f)
    await _scan(mdb, settings, stub_probe)

    row = _file(mdb, f)
    assert row.film_id == dune.film_id
    assert [f.title for f in _all(mdb, m.Film)] == ["Dune"]


async def test_manual_match_for_episode_file(mdb, media: MediaTree, media_settings, stub_probe):
    f = media.show("Lost/Lost 01.mkv")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe)
    with mdb.write_session() as s:
        lib = s.scalar(select(m.Library).where(m.Library.type == "shows"))
        show = m.Show(
            library_id=lib.id,
            parsed_title="Lost",
            parsed_title_norm="lost",
            title="Lost",
            sort_title="lost",
            search_text="lost",
            tmdb_id=4607,
            match_status="matched",
        )
        s.add(show)
        row = s.get(m.MediaFile, _file(mdb, f).id)
        row.manual_match = {"tmdb_id": 4607, "season": 1, "episodes": [1, 2]}
        s.query(m.UnmatchedEntry).delete()

    media.touch(f)
    report = await _scan(mdb, settings, stub_probe)

    assert _entries(mdb) == []
    assert report.counts["unmatched"] == 0
    assert sorted(e.episode_number for e in _all(mdb, m.Episode)) == [1, 2]
    assert _count(mdb, m.MediaFileEpisode) == 2
    assert _count(mdb, m.Show) == 1


async def test_item_alias_links_to_survivor(mdb, media: MediaTree, media_settings, stub_probe):
    media.add("Dune (2021)/Dune (2021).mkv")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe)
    film = _all(mdb, m.Film)[0]
    with mdb.write_session() as s:
        s.add(m.ItemAlias(library_id=film.library_id, parsed_title_norm="dunas", film_id=film.id))
    f = media.add("Dunas.mkv")
    await _scan(mdb, settings, stub_probe)
    assert _file(mdb, f).film_id == film.id
    assert _count(mdb, m.Film) == 1


# --- Walk details -----------------------------------------------------------------------------


async def test_hidden_entries_symlinks_and_nested_roots(
    mdb, media: MediaTree, media_settings, stub_probe, tmp_path
):
    media.add(".hidden/Dune (2021).mkv")
    media.add(".Alien (1979).mkv")
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "Heat (1995).mkv").write_bytes(b"x")
    (media.films / "Heat (1995).mkv").symlink_to(outside / "Heat (1995).mkv")
    target = media.add("Real/Brazil (1985).mkv")
    (media.films / "Brazil link (1985).mkv").symlink_to(target)
    (media.films / "dirlink").symlink_to(outside, target_is_directory=True)
    nested = media.films / "Real"
    settings = media_settings(libraries={"films": [str(media.films), str(nested)], "shows": []})

    report = await _scan(mdb, settings, stub_probe)

    paths = sorted(os.path.relpath(f.path, media.films) for f in _all(mdb, m.MediaFile))
    assert paths == ["Brazil link (1985).mkv", "Real/Brazil (1985).mkv"]
    assert f"symlink_outside_root: {media.films / 'Heat (1995).mkv'}" in report.errors
    assert any(e.startswith("duplicate_path:") for e in report.errors)
    assert report.counts["files_seen"] == 2


async def test_now_ignored_existing_row_is_deleted(
    mdb, media: MediaTree, media_settings, stub_probe
):
    f = media.add("Dune (2021)/Dune (2021).mkv")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe)
    # Simulate a row that a newer parser now ignores: rename on disk into a sample name.
    sample = f.with_name("Dune (2021)-sample.mkv")
    f.rename(sample)
    with mdb.write_session() as s:
        row = s.scalar(select(m.MediaFile).where(m.MediaFile.path == str(f)))
        row.path = str(sample)
        row.relative_path = "Dune (2021)/Dune (2021)-sample.mkv"
        row.mtime_ns = 1
    report = await _scan(mdb, settings, stub_probe)
    assert report.counts["ignored"] == 1
    assert _count(mdb, m.MediaFile) == 0


async def test_sidecars_follow_the_directory_listing(
    mdb, media: MediaTree, media_settings, stub_probe
):
    v = media.add("Dune (2021)/Dune (2021).mkv")
    en = media.add("Dune (2021)/Dune (2021).en.srt")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe)
    assert [s.language for s in _all(mdb, m.SubtitleFile)] == ["eng"]

    en.unlink()
    media.add("Dune (2021)/Dune (2021).spa.sdh.ass")
    await _scan(mdb, settings, stub_probe)
    subs = _all(mdb, m.SubtitleFile)
    assert [(s.language, s.hearing_impaired, s.format) for s in subs] == [("spa", True, "ass")]
    assert subs[0].media_file_id == _file(mdb, v).id


async def test_sidecar_change_or_removal_drops_the_cached_vtt(
    mdb, media: MediaTree, media_settings, stub_probe
):
    media.add("Dune (2021)/Dune (2021).mkv")
    en = media.add("Dune (2021)/Dune (2021).en.srt")
    es = media.add("Dune (2021)/Dune (2021).es.srt")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe)
    subs = {s.language: s for s in _all(mdb, m.SubtitleFile)}

    def cached(sub: m.SubtitleFile) -> Path:
        path = settings.subtitles_dir / str(sub.media_file_id) / f"ext-{sub.id}.vtt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("WEBVTT\n\nstale")
        return path

    en_vtt, es_vtt = cached(subs["eng"]), cached(subs["spa"])
    await _scan(mdb, settings, stub_probe)  # unchanged sidecars keep their conversions
    assert en_vtt.exists() and es_vtt.exists()

    media.touch(en)  # size + mtime change
    es.unlink()
    await _scan(mdb, settings, stub_probe)
    assert not en_vtt.exists()
    assert not es_vtt.exists()


async def test_keyframe_hook_receives_eligible_files(
    mdb, media: MediaTree, media_settings, stub_probe
):
    mkv = media.add("Dune (2021)/Dune (2021).mkv")
    media.add("Alien (1979)/Alien (1979).mp4")  # mp4: never enqueued
    ts = media.add("Heat (1995)/Heat (1995).ts")
    settings = media_settings()
    queued: list[list[int]] = []

    await _scan(mdb, settings, stub_probe, keyframe_hook=queued.append)

    assert len(queued) == 1
    assert sorted(queued[0]) == sorted([_file(mdb, mkv).id, _file(mdb, ts).id])
    with mdb.write_session() as s:
        s.add(m.KeyframeIndex(media_file_id=_file(mdb, mkv).id, source="mkv_cues", times=b""))
    await _scan(mdb, settings, stub_probe, keyframe_hook=queued.append)
    assert queued[1] == [_file(mdb, ts).id]


async def test_scan_run_row_tracks_progress(mdb, media: MediaTree, media_settings, stub_probe):
    media.add("Dune (2021)/Dune (2021).mkv")
    media.add("Alien (1979)/Alien (1979).mkv")
    settings = media_settings()
    with mdb.write_session() as s:
        run = m.ScanRun(trigger="manual", state="running", phase="walking")
        s.add(run)
        s.flush()
        run_id = run.id

    await _scanner(mdb, settings, stub_probe).run(run_id=run_id)

    with mdb.read_session() as s:
        run = s.get(m.ScanRun, run_id)
    assert run.total == 2
    assert run.processed == 2
    assert run.counts["new"] == 2
    assert run.phase == "probing"


# --- Library folders provider, re-roots and relinks (HOMETV-1) -------------------------------


def _provider(films=(), shows=()):
    from hometv.libraries import LibraryFolderSet

    folders = LibraryFolderSet(films=tuple(map(str, films)), shows=tuple(map(str, shows)))
    return lambda: folders


def _profile(mdb) -> int:
    with mdb.write_session() as s:
        profile = m.Profile(name="Kids", avatar_id="avatar-01", is_admin=False)
        s.add(profile)
        s.flush()
        return profile.id


def _progress(mdb, profile_id, *, film_id=None, episode_id=None) -> None:
    with mdb.write_session() as s:
        s.add(
            m.PlaybackProgress(
                profile_id=profile_id,
                film_id=film_id,
                episode_id=episode_id,
                position_seconds=60.0,
                duration_seconds=5400.0,
            )
        )


def _progress_rows(mdb) -> list[tuple[int | None, int | None]]:
    with mdb.read_session() as s:
        rows = [(p.film_id, p.episode_id) for p in s.scalars(select(m.PlaybackProgress))]
        return sorted(rows, key=lambda r: (r[0] or 0, r[1] or 0))


def _episode_ids(mdb, path: Path) -> list[int]:
    with mdb.read_session() as s:
        row = s.scalar(select(m.MediaFile).where(m.MediaFile.path == str(path)))
        return sorted(
            s.scalars(
                select(m.MediaFileEpisode.episode_id).where(
                    m.MediaFileEpisode.media_file_id == row.id
                )
            )
        )


def _show_titles(mdb) -> list[str]:
    return sorted(s.title for s in _all(mdb, m.Show))


async def test_provider_folders_are_walked_instead_of_settings(
    mdb, media: MediaTree, media_settings, stub_probe, tmp_path
):
    media.add("Dune (2021).mkv")
    other = tmp_path / "other"
    heat = media.add("Heat (1995).mkv", root=other)
    report = await _scan(mdb, media_settings(), stub_probe, folders=_provider(films=[other]))
    assert [f.path for f in _all(mdb, m.MediaFile)] == [str(heat)]
    assert report.counts["files_seen"] == 1


async def test_removed_folder_goes_missing_and_readding_restores_links_and_progress(
    mdb, media: MediaTree, media_settings, stub_probe
):
    dune = media.add("Dune (2021)/Dune (2021).mkv")
    settings = media_settings()
    both = _provider(films=[media.films], shows=[media.shows])
    await _scan(mdb, settings, stub_probe, folders=both)
    film_id = _file(mdb, dune).film_id
    _progress(mdb, _profile(mdb), film_id=film_id)

    report = await _scan(mdb, settings, stub_probe, folders=_provider(shows=[media.shows]))
    assert f"root_unconfigured: {media.films}" in report.errors
    assert _file(mdb, dune).missing_since is not None
    assert _progress_rows(mdb) == [(film_id, None)]

    await _scan(mdb, settings, stub_probe, folders=both)
    assert _file(mdb, dune).missing_since is None
    assert _file(mdb, dune).film_id == film_id
    assert _progress_rows(mdb) == [(film_id, None)]


@pytest.mark.parametrize("grace", [7, 0])
async def test_folder_moved_within_films_keeps_the_film_and_progress(
    mdb, media: MediaTree, media_settings, stub_probe, grace
):
    old = media.add("Dune (2021)/Dune (2021).mkv", root=media.base / "A")
    settings = media_settings(scan={"missing_grace_days": grace})
    await _scan(mdb, settings, stub_probe, folders=_provider(films=[media.base / "A"]))
    film_id = _file(mdb, old).film_id
    _progress(mdb, _profile(mdb), film_id=film_id)

    new = media.add("Dune (2021)/Dune (2021).mkv", root=media.base / "B")
    await _scan(mdb, settings, stub_probe, folders=_provider(films=[media.base / "B"]))
    assert _file(mdb, new).film_id == film_id
    assert _progress_rows(mdb) == [(film_id, None)]
    if grace == 0:
        assert _file(mdb, old) is None  # purged; the film lives on through the new file
        assert [f.id for f in _all(mdb, m.Film)] == [film_id]


async def test_films_root_replaced_by_parent_is_a_path_only_reroot(
    mdb, media: MediaTree, media_settings, stub_probe
):
    mkv = media.base / "MKV"
    dune = media.add("Dune (2021)/Dune (2021).mkv", root=mkv)
    heat = media.add("Heat (1995).mkv", root=mkv)
    settings = media_settings()
    await _scan(mdb, settings, stub_probe, folders=_provider(films=[mkv]))
    dune_row, heat_row = _file(mdb, dune), _file(mdb, heat)
    films_before = {(f.id, f.title, f.added_at) for f in _all(mdb, m.Film)}
    _progress(mdb, _profile(mdb), film_id=dune_row.film_id)
    media.touch(heat)
    new = media.add("películas/A/Alien (1979).mkv", root=media.base)
    stub_probe.calls.clear()

    report = await _scan(mdb, settings, stub_probe, folders=_provider(films=[media.base]))

    row = _file(mdb, dune)
    assert (row.id, row.film_id) == (dune_row.id, dune_row.film_id)
    assert row.root_path == str(media.base)
    assert row.relative_path == "MKV/Dune (2021)/Dune (2021).mkv"
    assert _file(mdb, heat).film_id == heat_row.film_id  # changed: reparsed, same film
    assert _file(mdb, heat).root_path == str(media.base)
    assert sorted(stub_probe.calls) == ["Alien (1979).mkv", "Heat (1995).mkv"]
    assert {(f.id, f.title, f.added_at) for f in _all(mdb, m.Film)} >= films_before
    assert sorted(f.title for f in _all(mdb, m.Film)) == ["Alien", "Dune", "Heat"]
    assert _file(mdb, new).relative_path == "películas/A/Alien (1979).mkv"
    assert _progress_rows(mdb) == [(dune_row.film_id, None)]
    assert report.counts["unchanged"] == 1 and report.counts["changed"] == 1
    assert not any(e.startswith("relink_pending") for e in report.errors)


async def test_reroot_of_a_missing_file_restores_it_with_the_same_links(
    mdb, media: MediaTree, media_settings, stub_probe
):
    mkv = media.base / "MKV"
    dune = media.add("Dune (2021).mkv", root=mkv)
    settings = media_settings()
    await _scan(mdb, settings, stub_probe, folders=_provider(films=[mkv]))
    before = _file(mdb, dune)
    await _scan(mdb, settings, stub_probe, folders=_provider())
    assert _file(mdb, dune).missing_since is not None
    stub_probe.calls.clear()

    report = await _scan(mdb, settings, stub_probe, folders=_provider(films=[media.base]))
    row = _file(mdb, dune)
    assert row.missing_since is None and row.root_path == str(media.base)
    assert (row.id, row.film_id) == (before.id, before.film_id)
    assert stub_probe.calls == []
    assert report.counts["restored"] == 1


def _user_series(media: MediaTree) -> dict[str, Path]:
    series = media.base / "series"
    return {
        "naruto": media.add("Naruto/Naruto.S01E01.mkv", root=series),
        "office": media.add("The Office/Season 2/The.Office.S02E03.mkv", root=series),
        "loose": media.add("Bluey.S01E02.mkv", root=series),
    }


async def test_user_series_layout_names_shows_after_folders_and_loose_files(
    mdb, media: MediaTree, media_settings, stub_probe
):
    _user_series(media)
    await _scan(mdb, media_settings(), stub_probe, folders=_provider(shows=[media.base / "series"]))
    assert _show_titles(mdb) == ["Bluey", "Naruto", "The Office"]


async def test_wrong_series_folder_corrected_by_a_confirmed_relayout(
    mdb, media: MediaTree, media_settings, stub_probe
):
    files = _user_series(media)
    settings = media_settings()
    await _scan(mdb, settings, stub_probe, folders=_provider(shows=[media.base]))
    assert "series" in [t.lower() for t in _show_titles(mdb)]
    wrong_episode = _episode_ids(mdb, files["naruto"])[0]
    _progress(mdb, _profile(mdb), episode_id=wrong_episode)

    fixed = _provider(shows=[media.base / "series"])
    report = await _scan(mdb, settings, stub_probe, folders=fixed, relinks=True)

    assert _show_titles(mdb) == ["Bluey", "Naruto", "The Office"]
    assert _progress_rows(mdb) == []
    assert {f.root_path for f in _all(mdb, m.MediaFile)} == {str(media.base / "series")}
    assert not any(e.startswith("relink_pending") for e in report.errors)


async def test_relayout_keeping_the_show_name_keeps_ids_and_progress(
    mdb, media: MediaTree, media_settings, stub_probe
):
    series = media.base / "series"
    naruto = media.add("Naruto/Naruto.S01E01.mkv", root=series)
    settings = media_settings()
    await _scan(mdb, settings, stub_probe, folders=_provider(shows=[series]))
    episodes = _episode_ids(mdb, naruto)
    show_ids = [s.id for s in _all(mdb, m.Show)]
    _progress(mdb, _profile(mdb), episode_id=episodes[0])
    stub_probe.calls.clear()

    await _scan(
        mdb, settings, stub_probe, folders=_provider(shows=[series / "Naruto"]), relinks=True
    )

    row = _file(mdb, naruto)
    assert row.root_path == str(series / "Naruto") and row.relative_path == "Naruto.S01E01.mkv"
    assert _episode_ids(mdb, naruto) == episodes
    assert [s.id for s in _all(mdb, m.Show)] == show_ids
    assert _progress_rows(mdb) == [(None, episodes[0])]
    assert stub_probe.calls == []  # unchanged file: not re-probed


async def test_confirmed_move_from_films_to_shows_relinks_and_drops_only_moved_progress(
    mdb, media: MediaTree, media_settings, stub_probe
):
    x = media.base / "X"
    moved = media.add("Lost.S01E01.mkv", root=x)
    keep = media.add("Dune (2021).mkv", root=media.base / "F")
    settings = media_settings()
    await _scan(mdb, settings, stub_probe, folders=_provider(films=[x, media.base / "F"]))
    moved_film, kept_film = _file(mdb, moved).film_id, _file(mdb, keep).film_id
    assert moved_film is not None
    profile = _profile(mdb)
    _progress(mdb, profile, film_id=moved_film)
    _progress(mdb, profile, film_id=kept_film)

    await _scan(
        mdb,
        settings,
        stub_probe,
        folders=_provider(films=[media.base / "F"], shows=[x]),
        relinks=True,
    )

    row = _file(mdb, moved)
    assert row.film_id is None and len(_episode_ids(mdb, moved)) == 1
    assert _show_titles(mdb) == ["Lost"]
    assert [f.id for f in _all(mdb, m.Film)] == [kept_film]
    assert _progress_rows(mdb) == [(kept_film, None)]


async def _pending_setup(mdb, media: MediaTree, media_settings, stub_probe):
    """Rows left by an older configuration: films-library rows under the Series folder S
    (stale library_id) and Series rows stored under root ``base`` inside S."""
    s_root = media.base / "S"
    stale = media.add("Dune (2021).mkv", root=s_root)
    settings = media_settings()
    await _scan(mdb, settings, stub_probe, folders=_provider(films=[s_root]))
    episode = media.add("Lost/Lost.S01E01.mkv", root=s_root)
    await _scan(mdb, settings, stub_probe, folders=_provider(shows=[media.base]))
    return settings, s_root, stale, episode


async def test_relinks_only_in_a_confirmed_run(mdb, media: MediaTree, media_settings, stub_probe):
    settings, s_root, stale, episode = await _pending_setup(mdb, media, media_settings, stub_probe)
    before = {f.path: (f.id, f.library_id, f.root_path, f.film_id) for f in _all(mdb, m.MediaFile)}
    episodes = _episode_ids(mdb, episode)
    profile = _profile(mdb)
    _progress(mdb, profile, film_id=before[str(stale)][3])
    _progress(mdb, profile, episode_id=episodes[0])
    media.touch(stale)
    media.touch(episode)
    stub_probe.calls.clear()
    folders = _provider(shows=[s_root])

    report = await _scan(mdb, settings, stub_probe, folders=folders)

    after = {f.path: (f.id, f.library_id, f.root_path, f.film_id) for f in _all(mdb, m.MediaFile)}
    assert after == before
    assert all(f.missing_since is None for f in _all(mdb, m.MediaFile))
    assert _episode_ids(mdb, episode) == episodes
    assert len(_progress_rows(mdb)) == 2
    assert stub_probe.calls == []
    assert f"relink_pending: {media.base} -> {s_root}" in report.errors
    assert f"relink_pending: {s_root} -> {s_root}" in report.errors
    assert report.counts["unchanged"] == 2

    report = await _scan(mdb, settings, stub_probe, folders=folders, relinks=True)
    rows = {f.path: f for f in _all(mdb, m.MediaFile)}
    assert {r.root_path for r in rows.values()} == {str(s_root)}
    assert rows[str(stale)].film_id is None
    assert not any(e.startswith("relink_pending") for e in report.errors)
    report = await _scan(mdb, settings, stub_probe, folders=folders)
    assert not any(e.startswith("relink_pending") for e in report.errors)


async def test_relinks_applied_by_a_confirmed_run_are_exactly_the_planned_ones(
    mdb, media: MediaTree, media_settings, make_settings, stub_probe
):
    from hometv.libraries import LibraryFolders

    settings, s_root, stale, episode = await _pending_setup(mdb, media, media_settings, stub_probe)
    other = media.add("Heat (1995).mkv", root=media.base / "F")
    await _scan(
        mdb, settings, stub_probe, folders=_provider(films=[media.base / "F"], shows=[media.base])
    )
    lf = LibraryFolders(mdb, make_settings())
    lf.seed_once()
    with mdb.write_session() as s:
        libs = {lib.type: lib.id for lib in s.scalars(select(m.Library))}
        s.execute(m.LibraryFolder.__table__.delete())
        s.flush()
        s.add(m.LibraryFolder(library_id=libs["films"], path=str(media.base / "F")))
        s.add(m.LibraryFolder(library_id=libs["shows"], path=str(s_root)))
    lf.load()
    plan = lf.plan([str(media.base / "F")], [str(s_root)])
    planned = {(mv.from_path, mv.to_path) for mv in plan.moved}
    planned |= {(r.from_path, r.to_path) for r in plan.relayout}

    before = {f.path: (f.library_id, f.root_path) for f in _all(mdb, m.MediaFile)}
    report = await _scan(mdb, settings, stub_probe, folders=lf.current)
    pending = {
        tuple(e.removeprefix("relink_pending: ").split(" -> "))
        for e in report.errors
        if e.startswith("relink_pending")
    }
    assert pending == planned
    await _scan(mdb, settings, stub_probe, folders=lf.current, relinks=True)
    after = {f.path: (f.library_id, f.root_path) for f in _all(mdb, m.MediaFile)}
    relinked = {p for p in before if before[p] != after[p]}
    assert relinked == {str(stale), str(episode)}
    assert after[str(other)] == before[str(other)]
    assert not lf.plan([str(media.base / "F")], [str(s_root)]).has_relinks


async def test_upgrade_with_series_env_changed_to_a_parent_deletes_nothing(
    mdb, media: MediaTree, make_settings, stub_probe
):
    from hometv.libraries import LibraryFolders, PlanRelayout

    series = media.base / "series"
    naruto = media.add("Naruto/Naruto.S01E01.mkv", root=series)
    old = make_settings(libraries={"shows": [str(series)]})
    await _scan(mdb, old, stub_probe)  # pre-upgrade scan from env roots (default provider)
    episodes = _episode_ids(mdb, naruto)
    _progress(mdb, _profile(mdb), episode_id=episodes[0])

    upgraded = make_settings(libraries={"shows": [str(media.base)]})
    lf = LibraryFolders(mdb, upgraded)
    lf.seed_once()
    report = await _scan(mdb, upgraded, stub_probe, folders=lf.current)

    assert _show_titles(mdb) == ["Naruto"]
    assert _episode_ids(mdb, naruto) == episodes
    assert _progress_rows(mdb) == [(None, episodes[0])]
    assert f"relink_pending: {series} -> {media.base}" in report.errors
    plan = lf.plan([], [str(media.base)])
    assert plan.relayout == (PlanRelayout(str(series), str(media.base), 1),)


async def test_seeded_media_file_roots_leave_nothing_unconfigured(
    mdb, media: MediaTree, media_settings, make_settings, stub_probe
):
    from hometv.libraries import LibraryFolders

    media.add("Dune (2021).mkv")
    media.show("Lost/Lost.S01E01.mkv")
    await _scan(mdb, media_settings(), stub_probe)
    no_env = make_settings()
    lf = LibraryFolders(mdb, no_env)
    lf.seed_once()
    report = await _scan(mdb, no_env, stub_probe, folders=lf.current)
    assert not any(e.startswith("root_unconfigured") for e in report.errors)
    assert report.counts["missing"] == 0
