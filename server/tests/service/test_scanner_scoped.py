"""Scoped ``Scanner``: only the scope is walked, diffed and marked missing.

The layout mirrors the household library: films roots ``MKV/`` (flat) and ``películas/{A,B}``
(letter folders); shows root ``series/`` with ``Breaking Bad/…`` and a loose episode.
"""

import errno
import os
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest
from sqlalchemy import select

from hometv.changes.scope import ScanScope
from hometv.db import models as m
from hometv.scan_report import ScanReport
from hometv.scanner import FsOps, Scanner, _RootWalk

from .conftest import StubProbe


class Library:
    def __init__(self, base: Path) -> None:
        self.base = base
        self.mkv = base / "MKV"
        self.peliculas = base / "películas"
        self.series = base / "series"
        for root in (self.mkv, self.peliculas, self.series):
            root.mkdir(parents=True)

    def add(self, path: Path, content: bytes = b"x") -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        return path

    def populate(self) -> None:
        self.add(self.mkv / "Heat (1995).mkv")
        self.add(self.mkv / "x.mkv")
        self.add(self.mkv / "x.en.srt")
        self.add(self.peliculas / "A" / "Alien (1979).mkv")
        self.add(self.peliculas / "A" / "Amélie (2001).mkv")
        self.add(self.peliculas / "B" / "Blade Runner (1982).mkv")
        self.add(self.series / "Breaking Bad" / "Temporada 1" / "Breaking Bad S01E01.mkv")
        self.add(self.series / "Breaking Bad" / "Temporada 1" / "Breaking Bad S01E02.mkv")
        self.add(self.series / "The Office S01E01.mkv")

    def scope(self, root: Path, rel_dir: str = "", recursive: bool = True) -> ScanScope:
        return ScanScope(str(root), rel_dir, recursive)


@pytest.fixture
def lib(tmp_path: Path) -> Library:
    library = Library(tmp_path / "media")
    library.populate()
    return library


@pytest.fixture
def lib_settings(make_settings: Callable[..., Any], lib: Library):
    return make_settings(
        libraries={"films": [str(lib.mkv), str(lib.peliculas)], "shows": [str(lib.series)]}
    )


async def _scan(mdb, settings, probe, **kw) -> ScanReport:
    return await Scanner(mdb, settings, probe_fn=probe, **kw).run()


def _rows(mdb) -> dict[str, m.MediaFile]:
    with mdb.read_session() as s:
        return {r.path: r for r in s.scalars(select(m.MediaFile))}


def _missing(mdb) -> set[str]:
    return {p for p, r in _rows(mdb).items() if r.missing_since is not None}


def _subs(mdb) -> set[str]:
    with mdb.read_session() as s:
        return set(s.scalars(select(m.SubtitleFile.path)))


@pytest.fixture
async def scanned(mdb, lib_settings, lib):
    """The library after a first full scan (every file present)."""
    await _scan(mdb, lib_settings, StubProbe())
    assert _missing(mdb) == set()
    return lib


async def test_scope_dir_new_file_created_deletions_only_inside_scope(
    mdb, lib_settings, scanned: Library
):
    lib = scanned
    new = lib.add(lib.peliculas / "A" / "Avatar (2009).mkv")
    (lib.peliculas / "A" / "Alien (1979).mkv").unlink()
    (lib.peliculas / "B" / "Blade Runner (1982).mkv").unlink()  # outside the scope
    probe = StubProbe()

    report = await _scan(mdb, lib_settings, probe, scope=[lib.scope(lib.peliculas, "A")])

    rows = _rows(mdb)
    assert str(new) in rows
    assert _missing(mdb) == {str(lib.peliculas / "A" / "Alien (1979).mkv")}
    assert probe.calls == ["Avatar (2009).mkv"]
    assert report.counts["files_seen"] == 2
    assert (report.counts["new"], report.counts["missing"]) == (1, 1)
    assert report.total == 2


async def test_removed_scope_dir_under_present_root_marks_rows_missing(
    mdb, lib_settings, scanned: Library
):
    lib = scanned
    for path in (lib.peliculas / "A").iterdir():
        path.unlink()
    (lib.peliculas / "A").rmdir()
    report = await _scan(mdb, lib_settings, StubProbe(), scope=[lib.scope(lib.peliculas, "A")])
    assert _missing(mdb) == {
        str(lib.peliculas / "A" / "Alien (1979).mkv"),
        str(lib.peliculas / "A" / "Amélie (2001).mkv"),
    }
    assert report.errors == []


async def test_missing_root_marks_nothing(mdb, lib_settings, scanned: Library):
    lib = scanned
    os.rename(lib.peliculas, lib.base / "elsewhere")
    report = await _scan(mdb, lib_settings, StubProbe(), scope=[lib.scope(lib.peliculas, "A")])
    assert f"root_unavailable: {lib.peliculas}" in report.errors
    assert report.unreachable_roots == {str(lib.peliculas)}
    assert _missing(mdb) == set()


@pytest.mark.parametrize("shape", ["dir", "shallow"])
async def test_empty_root_with_present_rows_is_protected(
    mdb, lib_settings, scanned: Library, shape
):
    lib = scanned
    root = lib.peliculas if shape == "dir" else lib.series
    for path in sorted(root.rglob("*"), reverse=True):
        path.rmdir() if path.is_dir() else path.unlink()
    scope = lib.scope(root, "A") if shape == "dir" else lib.scope(root, "", False)
    report = await _scan(mdb, lib_settings, StubProbe(), scope=[scope])
    assert f"root_empty_skipped_removal: {root}" in report.errors
    assert _missing(mdb) == set()


async def test_whole_root_scope_without_videos_is_protected(mdb, lib_settings, scanned: Library):
    lib = scanned
    for path in sorted(lib.peliculas.rglob("*.mkv")):
        path.unlink()
    lib.add(lib.peliculas / "A" / "notes.txt")
    report = await _scan(mdb, lib_settings, StubProbe(), scope=[lib.scope(lib.peliculas)])
    assert f"root_empty_skipped_removal: {lib.peliculas}" in report.errors
    assert _missing(mdb) == set()


async def test_unreachable_roots_only_for_root_failures(mdb, lib_settings, scanned: Library):
    lib = scanned
    real_scandir = os.scandir

    def scandir(path):
        if os.fspath(path) == str(lib.peliculas / "A"):
            raise PermissionError(errno.EACCES, "denied")
        return real_scandir(path)

    fs = FsOps(scandir=scandir)
    scoped = await _scan(
        mdb, lib_settings, StubProbe(), fs=fs, scope=[lib.scope(lib.peliculas, "A")]
    )
    assert f"walk_error: {lib.peliculas / 'A'}: EACCES" in scoped.errors
    assert scoped.unreachable_roots == set()
    assert _missing(mdb) == set()  # nothing in the failed dir marked missing
    full = await _scan(mdb, lib_settings, StubProbe(), fs=fs)
    assert full.unreachable_roots == set()
    assert _missing(mdb) == set()


async def test_full_scan_reports_unreachable_roots(mdb, lib_settings, scanned: Library):
    lib = scanned
    os.rename(lib.series, lib.base / "gone")
    report = await _scan(mdb, lib_settings, StubProbe())
    assert report.unreachable_roots == {str(lib.series)}

    real_stat = os.stat
    calls = {"n": 0}

    def flaky_stat(path, *args, **kwargs):
        if os.fspath(path) == str(lib.mkv):
            calls["n"] += 1
            if calls["n"] > 1:  # the final stat of the root fails
                raise OSError(errno.EIO, "I/O error")
        return real_stat(path, *args, **kwargs)

    report = await _scan(mdb, lib_settings, StubProbe(), fs=FsOps(stat=flaky_stat))
    assert str(lib.mkv) in report.unreachable_roots


async def test_scope_dir_stat_error_protects_its_rows(mdb, lib_settings, scanned: Library):
    lib = scanned
    real_stat = os.stat
    target = str(lib.peliculas / "A")

    def stat(path, *args, **kwargs):
        if os.fspath(path) == target:
            raise PermissionError(errno.EACCES, "denied")
        return real_stat(path, *args, **kwargs)

    for path in (lib.peliculas / "A").iterdir():
        path.unlink()
    report = await _scan(
        mdb,
        lib_settings,
        StubProbe(),
        fs=FsOps(stat=stat),
        scope=[lib.scope(lib.peliculas, "A")],
    )
    assert f"walk_error: {target}: EACCES" in report.errors
    assert _missing(mdb) == set()


async def test_root_shallow_scope_links_loose_episode_and_sidecar_only(
    mdb, lib_settings, scanned: Library
):
    lib = scanned
    real_scandir = os.scandir
    listed: list[str] = []

    def scandir(path):
        listed.append(os.fspath(path))
        return real_scandir(path)

    lib.add(lib.series / "The Office S01E02.mkv")
    lib.add(lib.series / "The Office S01E02.es.srt")
    (lib.series / "Breaking Bad" / "Temporada 1" / "Breaking Bad S01E02.mkv").unlink()
    probe = StubProbe()
    report = await _scan(
        mdb,
        lib_settings,
        probe,
        fs=FsOps(scandir=scandir),
        scope=[lib.scope(lib.series, "", False)],
    )
    assert listed == [str(lib.series)]
    assert probe.calls == ["The Office S01E02.mkv"]
    assert str(lib.series / "The Office S01E02.es.srt") in _subs(mdb)
    assert _missing(mdb) == set()  # Breaking Bad never walked: untouched
    assert report.counts["files_seen"] == 2

    (lib.series / "The Office S01E01.mkv").unlink()
    await _scan(mdb, lib_settings, StubProbe(), scope=[lib.scope(lib.series, "", False)])
    assert _missing(mdb) == {str(lib.series / "The Office S01E01.mkv")}


async def test_root_shallow_scope_loads_only_direct_children(
    mdb, lib_settings, scanned: Library, monkeypatch
):
    lib = scanned
    scope = lib.scope(lib.series, "", False)
    scanner = Scanner(mdb, lib_settings, probe_fn=StubProbe(), scope=[scope])
    walk = _RootWalk(root=str(lib.series), library_type="shows", scopes=[scope])
    monkeypatch.setattr(ScanScope, "contains", lambda self, path: True)  # the SQL alone
    with mdb.read_session() as session:
        rows = scanner._load_rows(session, [walk])
    assert set(rows) == {str(lib.series / "The Office S01E01.mkv")}


async def test_sidecar_outside_scope_is_left_alone(mdb, lib_settings, scanned: Library):
    lib = scanned
    sub = str(lib.mkv / "x.en.srt")
    assert sub in _subs(mdb)
    await _scan(mdb, lib_settings, StubProbe(), scope=[lib.scope(lib.peliculas, "A")])
    assert sub in _subs(mdb)


async def test_sidecar_removed_inside_scope_is_deleted(mdb, lib_settings, scanned: Library):
    lib = scanned
    (lib.mkv / "x.en.srt").unlink()
    await _scan(mdb, lib_settings, StubProbe(), scope=[lib.scope(lib.mkv, "", False)])
    assert str(lib.mkv / "x.en.srt") not in _subs(mdb)


async def test_restored_file_inside_scope_keeps_its_id(mdb, lib_settings, scanned: Library):
    lib = scanned
    alien = lib.peliculas / "A" / "Alien (1979).mkv"
    before = _rows(mdb)[str(alien)]
    content = alien.read_bytes()
    stat = alien.stat()
    alien.unlink()
    await _scan(mdb, lib_settings, StubProbe(), scope=[lib.scope(lib.peliculas, "A")])
    assert str(alien) in _missing(mdb)
    alien.write_bytes(content)
    os.utime(alien, ns=(stat.st_atime_ns, stat.st_mtime_ns))
    report = await _scan(mdb, lib_settings, StubProbe(), scope=[lib.scope(lib.peliculas, "A")])
    after = _rows(mdb)[str(alien)]
    assert report.counts["restored"] == 1
    assert (after.id, after.film_id, after.missing_since) == (before.id, before.film_id, None)


async def test_move_across_roots_in_one_run(mdb, lib_settings, scanned: Library):
    lib = scanned
    target = lib.peliculas / "X" / "x.mkv"
    target.parent.mkdir()
    os.rename(lib.mkv / "x.mkv", target)
    report = await _scan(
        mdb,
        lib_settings,
        StubProbe(),
        scope=[lib.scope(lib.mkv, "", False), lib.scope(lib.peliculas, "X")],
    )
    assert _missing(mdb) == {str(lib.mkv / "x.mkv")}
    assert str(target) in _rows(mdb)
    assert report.counts["new"] == 1


async def test_unconfigured_scope_root_changes_nothing(mdb, lib_settings, scanned: Library):
    lib = scanned
    other = lib.base / "otra"
    lib.add(other / "A" / "Zodiac (2007).mkv")
    before = {p: (r.id, r.missing_since) for p, r in _rows(mdb).items()}
    report = await _scan(mdb, lib_settings, StubProbe(), scope=[ScanScope(str(other), "A", True)])
    assert f"scope_unconfigured: {other / 'A'}" in report.errors
    assert {p: (r.id, r.missing_since) for p, r in _rows(mdb).items()} == before


async def test_symlinked_top_level_dir_is_not_walked(mdb, lib_settings, scanned: Library):
    lib = scanned
    elsewhere = lib.base / "outside"
    lib.add(elsewhere / "Cars (2006).mkv")
    (lib.peliculas / "C").symlink_to(elsewhere, target_is_directory=True)
    report = await _scan(mdb, lib_settings, StubProbe(), scope=[lib.scope(lib.peliculas, "C")])
    assert report.counts["files_seen"] == 0  # like the full scan: symlinked dirs never walked
    assert str(lib.peliculas / "C" / "Cars (2006).mkv") not in _rows(mdb)


async def test_nested_root_rows_are_left_to_their_own_root(mdb, make_settings, tmp_path: Path):
    base = tmp_path / "nest"
    (base / "series" / "Show").mkdir(parents=True)
    (base / "MKV").mkdir()
    (base / "MKV" / "Heat (1995).mkv").write_bytes(b"x")
    (base / "series" / "Show" / "Show S01E01.mkv").write_bytes(b"x")
    settings = make_settings(libraries={"films": [str(base)], "shows": [str(base / "series")]})
    inner_only = make_settings(libraries={"shows": [str(base / "series")]})
    await _scan(mdb, inner_only, StubProbe())
    episode = str(base / "series" / "Show" / "Show S01E01.mkv")
    assert _rows(mdb)[episode].root_path == str(base / "series")
    (base / "series" / "Show" / "Show S01E01.mkv").unlink()
    # A scope of the outer root that covers the inner root's folder never judges its rows.
    await _scan(mdb, settings, StubProbe(), scope=[ScanScope(str(base), "series", True)])
    assert episode not in _missing(mdb)
    await _scan(mdb, settings, StubProbe(), scope=[ScanScope(str(base / "series"), "Show", True)])
    assert episode in _missing(mdb)


async def test_nested_roots_in_one_run_rows_are_judged_by_their_own_root_walk(
    mdb, make_settings, tmp_path: Path
):
    base = tmp_path / "nest"
    (base / "series" / "Show").mkdir(parents=True)
    (base / "MKV").mkdir()
    (base / "MKV" / "Heat (1995).mkv").write_bytes(b"x")
    (base / "series" / "Show" / "Show S01E01.mkv").write_bytes(b"x")
    settings = make_settings(libraries={"films": [str(base)], "shows": [str(base / "series")]})
    await _scan(mdb, make_settings(libraries={"shows": [str(base / "series")]}), StubProbe())
    episode = str(base / "series" / "Show" / "Show S01E01.mkv")
    (base / "series" / "Show" / "Show S01E01.mkv").unlink()
    # Both walks contain the row; the outer one comes first, the row's own root decides.
    scopes = [ScanScope(str(base), "series", True), ScanScope(str(base / "series"), "Show", True)]
    await _scan(mdb, settings, StubProbe(), scope=scopes)
    assert episode in _missing(mdb)


async def test_whole_root_scope_lists_the_root_once(mdb, lib_settings, scanned: Library):
    lib = scanned
    listed: list[str] = []

    def scandir(path):
        listed.append(os.fspath(path))
        return os.scandir(path)

    (lib.peliculas / "B" / "Blade Runner (1982).mkv").unlink()
    await _scan(
        mdb, lib_settings, StubProbe(), fs=FsOps(scandir=scandir), scope=[lib.scope(lib.peliculas)]
    )
    assert listed.count(str(lib.peliculas)) == 1
    assert _missing(mdb) == {str(lib.peliculas / "B" / "Blade Runner (1982).mkv")}


async def test_empty_whole_root_listing_still_guards_removals(mdb, lib_settings, scanned: Library):
    lib = scanned
    for path in sorted(lib.peliculas.rglob("*"), reverse=True):
        path.unlink() if path.is_file() else path.rmdir()
    (lib.peliculas / ".hidden").mkdir()
    report = await _scan(mdb, lib_settings, StubProbe(), scope=[lib.scope(lib.peliculas)])
    assert f"root_empty_skipped_removal: {lib.peliculas}" in report.errors
    assert _missing(mdb) == set()


async def test_scoped_walk_touches_only_the_root_and_the_scope(mdb, make_settings, tmp_path: Path):
    root = tmp_path / "big" / "películas"
    for i in range(200):
        folder = root / f"F{i:03d}"
        folder.mkdir(parents=True)
        (folder / f"Film {i} (2000).mkv").write_bytes(b"x")
    settings = make_settings(libraries={"films": [str(root)]})
    await _scan(mdb, settings, StubProbe())
    touched: list[str] = []

    def scandir(path):
        touched.append(os.fspath(path))
        return os.scandir(path)

    def stat(path, *args, **kwargs):
        touched.append(os.fspath(path))
        return os.stat(path, *args, **kwargs)

    (root / "F050" / "New (2001).mkv").write_bytes(b"y")
    report = await _scan(
        mdb,
        settings,
        StubProbe(),
        fs=FsOps(scandir=scandir, stat=stat),
        scope=[ScanScope(str(root), "F050", True)],
    )
    scope_dir = str(root / "F050")
    assert touched
    assert all(p == str(root) or p == scope_dir or p.startswith(scope_dir + "/") for p in touched)
    assert report.counts["new"] == 1


async def test_scope_none_or_empty_is_a_full_scan(mdb, lib_settings, scanned: Library):
    lib = scanned
    (lib.peliculas / "B" / "Blade Runner (1982).mkv").unlink()
    await _scan(mdb, lib_settings, StubProbe(), scope=[])
    assert _missing(mdb) == {str(lib.peliculas / "B" / "Blade Runner (1982).mkv")}
