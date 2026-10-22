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


async def test_missing_file_is_purged_only_after_grace_period(
    mdb, media: MediaTree, media_settings, stub_probe
):
    a = media.add("Dune (2021)/Dune (2021).mkv")
    media.add("Alien (1979)/Alien (1979).mkv")
    settings = media_settings(scan={"missing_grace_days": 7})
    await _scan(mdb, settings, stub_probe)
    a.unlink()
    await _scan(mdb, settings, stub_probe)
    row = _file(mdb, a)
    with mdb.write_session() as s:
        s.get(m.MediaFile, row.id).missing_since = m.utcnow() - timedelta(days=8)

    report = await _scan(mdb, settings, stub_probe)

    assert report.counts["removed"] == 1
    assert _file(mdb, a) is None
    assert [f.title for f in _all(mdb, m.Film)] == ["Alien"]


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
