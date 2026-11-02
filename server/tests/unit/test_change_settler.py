"""``changes.settle.ChangeSettler``: table tests with a fake ``now``."""

import os

from hometv.changes.backend import WatchEvent
from hometv.changes.scope import ScanScope
from hometv.changes.settle import ChangeSettler

P = "/media/películas"
S = "/media/series"
M = "/media/MKV"
ROOTS = [M, P, S]
Q = 30.0


def ev(kind, path, *, is_dir=False, size=None, root=None):
    return WatchEvent(kind, path, is_dir=is_dir, size=size, root=root)


def settler(roots=ROOTS, quiet=Q, **kw):
    return ChangeSettler(roots, quiet, **kw)


def run_due(s: ChangeSettler, now: float, sizes: dict[str, int | None]):
    """Simulate ``poll_once``: stat the due paths with ``sizes`` (absent -> None)."""
    paths = s.due(now)
    return s.settle(now, {p: sizes.get(p) for p in paths})


def test_rsync_temp_file_then_rename():
    s = settler()
    s.add(ev("created", f"{P}/D/.Dune.mkv.Xy12", size=0), 0)
    s.add(ev("modified", f"{P}/D/.Dune.mkv.Xy12"), 1)
    assert s.pending == []
    s.add(ev("moved_from", f"{P}/D/.Dune.mkv.Xy12"), 2)
    s.add(ev("moved_to", f"{P}/D/Dune.mkv", size=100), 2)
    assert s.pending == [ScanScope(P, "D", True)]
    assert s.next_deadline() == 32
    assert run_due(s, 31, {f"{P}/D/Dune.mkv": 100}) == []
    assert run_due(s, 32, {f"{P}/D/Dune.mkv": 100}) == [ScanScope(P, "D", True)]
    assert s.pending == []
    assert s.next_deadline() is None


def test_download_part_extension_is_irrelevant_until_rename():
    s = settler()
    s.add(ev("created", f"{P}/D/Dune.mkv.part", size=0), 0)
    s.add(ev("closed_write", f"{P}/D/Dune.mkv.!qB", size=9), 0)
    assert s.pending == []


def test_cp_settles_after_close_write():
    s = settler()
    f = f"{P}/A/Alien.mkv"
    s.add(ev("created", f, size=0), 0)
    for t in range(1, 10):
        s.add(ev("modified", f), t)
    s.add(ev("closed_write", f, size=500), 10)
    assert s.due(39) == []
    assert s.due(40) == [f]
    assert s.settle(40, {f: 500}) == [ScanScope(P, "A", True)]


def test_stalled_copy_rearms_once():
    s = settler()
    f = f"{P}/A/Alien.mkv"
    s.add(ev("created", f, size=0), 0)
    s.add(ev("modified", f), 1)
    assert run_due(s, 31, {f: 200}) == []  # grew since the record: re-armed
    assert s.next_deadline() == 61
    assert run_due(s, 61, {f: 200}) == [ScanScope(P, "A", True)]


def test_modified_keeps_the_recorded_size():
    s = settler()
    f = f"{P}/A/Alien.mkv"
    s.add(ev("closed_write", f, size=500), 0)
    s.add(ev("modified", f), 1)  # no size: the recorded 500 stays
    assert run_due(s, 31, {f: 500}) == [ScanScope(P, "A", True)]


def test_season_burst_is_one_scope_one_batch():
    s = settler()
    for i in range(10):
        s.add(ev("closed_write", f"{S}/Breaking Bad/T5/BB S05E{i:02d}.mkv", size=10), i)
    assert s.pending == [ScanScope(S, "Breaking Bad", True)]
    sizes = {p: 10 for p in s.due(39)}
    assert len(sizes) == 10
    assert s.settle(39, sizes) == [ScanScope(S, "Breaking Bad", True)]


def test_two_quiet_scopes_are_one_batch():
    s = settler()
    s.add(ev("closed_write", f"{P}/A/a.mkv", size=1), 0)
    s.add(ev("closed_write", f"{P}/B/b.mkv", size=1), 5)
    assert run_due(s, 35, {f"{P}/A/a.mkv": 1, f"{P}/B/b.mkv": 1}) == [
        ScanScope(P, "A", True),
        ScanScope(P, "B", True),
    ]


def test_burst_across_folders_is_one_batch():
    s = settler()
    s.add(ev("moved_to", f"{P}/E", is_dir=True), 0)
    s.add(ev("moved_to", f"{P}/F", is_dir=True), 0.05)  # next reader batch
    s.add(ev("closed_write", f"{P}/G/g.mkv", size=1), 2)
    assert run_due(s, 30, {}) == []  # E is quiet, but the burst is not
    assert s.next_deadline() == 32
    assert run_due(s, 32, {f"{P}/G/g.mkv": 1}) == [
        ScanScope(P, "E", True),
        ScanScope(P, "F", True),
        ScanScope(P, "G", True),
    ]


def test_quiet_scope_is_held_at_most_one_more_quiet_period():
    s = settler()
    a, b = f"{P}/A/a.mkv", f"{P}/B/b.mkv"
    s.add(ev("closed_write", a, size=1), 0)
    for t in range(0, 100, 10):  # a long copy elsewhere keeps the burst going
        s.add(ev("modified", b), t)
    assert run_due(s, 59, {a: 1}) == []
    assert s.next_deadline() == 60
    assert run_due(s, 60, {a: 1}) == [ScanScope(P, "A", True)]
    assert s.pending == [ScanScope(P, "B", True)]


def test_root_shallow_file_with_its_sidecar():
    s = settler()
    s.add(ev("closed_write", f"{S}/The Office S01E01.mkv", size=7), 0)
    s.add(ev("closed_write", f"{S}/The Office S01E01.es.srt", size=1), 1)
    assert s.pending == [ScanScope(S, "", False)]
    assert s.due(31) == [f"{S}/The Office S01E01.mkv"]  # sidecars are never stat'ed
    assert s.settle(31, {f"{S}/The Office S01E01.mkv": 7}) == [ScanScope(S, "", False)]


def test_dir_rename_inside_a_top_level_dir_is_one_scope():
    s = settler()
    s.add(ev("moved_from", f"{P}/A/Old", is_dir=True), 0)
    s.add(ev("moved_to", f"{P}/A/New", is_dir=True), 0)
    assert s.pending == [ScanScope(P, "A", True)]
    assert s.due(30) == []  # no video paths: ready once quiet
    assert s.settle(30, {}) == [ScanScope(P, "A", True)]


def test_move_across_roots_marks_both_scopes():
    s = settler()
    s.add(ev("moved_from", f"{M}/x.mkv"), 0)
    s.add(ev("moved_to", f"{P}/X/x.mkv", size=3), 0)
    assert s.pending == [ScanScope(M, "", False), ScanScope(P, "X", True)]
    assert run_due(s, 30, {f"{P}/X/x.mkv": 3}) == [ScanScope(M, "", False), ScanScope(P, "X", True)]


def test_deleted_video_is_ready_when_stat_says_gone():
    s = settler()
    s.add(ev("deleted", f"{P}/A/a.mkv"), 0)
    assert run_due(s, 30, {}) == [ScanScope(P, "A", True)]


def test_more_than_cap_scopes_are_widened():
    s = settler(cap=50)
    for i in range(51):
        s.add(ev("created", f"{P}/D{i:02d}", is_dir=True), 0)
    assert s.pending == [ScanScope(P, "", True)]
    s.add(ev("created", f"{P}/Z", is_dir=True), 1)  # absorbed by the whole-root scope
    assert s.pending == [ScanScope(P, "", True)]
    assert s.next_deadline() == 31


def test_widening_keeps_recorded_videos():
    s = settler(cap=1)
    s.add(ev("closed_write", f"{P}/A/a.mkv", size=1), 0)
    s.add(ev("closed_write", f"{P}/B/b.mkv", size=2), 0)
    assert s.pending == [ScanScope(P, "", True)]
    assert sorted(s.due(30)) == [f"{P}/A/a.mkv", f"{P}/B/b.mkv"]


def test_overflow_makes_every_root_whole():
    s = settler()
    s.add(ev("closed_write", f"{P}/A/a.mkv", size=1), 0)
    s.add(ev("overflow", ""), 5)
    assert s.pending == [ScanScope(M, "", True), ScanScope(P, "", True), ScanScope(S, "", True)]
    assert s.next_deadline() == 35


def test_irrelevant_events_are_ignored():
    s = settler()
    s.add(ev("created", f"{P}/A/poster.jpg"), 0)
    s.add(ev("created", f"{P}/A/movie.nfo"), 0)
    s.add(ev("created", f"{P}/.hidden", is_dir=True), 0)
    s.add(ev("created", "/elsewhere/a.mkv"), 0)
    s.add(ev("created", P, is_dir=True), 0)  # the root itself
    s.add(ev("watch_limit", f"{P}/A", is_dir=True), 0)
    s.add(ev("backend_failed", ""), 0)
    s.add(ev("created", f"{P}/{os.fsdecode(b'\xff.mkv')}"), 0)
    assert s.pending == []


def test_root_gone_drops_pending_scopes_of_that_root():
    s = settler()
    s.add(ev("closed_write", f"{S}/Show/a.mkv", size=1), 0)
    s.add(ev("closed_write", f"{P}/A/a.mkv", size=1), 0)
    s.add(ev("root_gone", S, is_dir=True, root=S), 1)
    assert s.pending == [ScanScope(P, "A", True)]


def test_set_roots_removing_a_root_drops_its_scopes():
    s = settler()
    s.add(ev("closed_write", f"{S}/Show/a.mkv", size=1), 0)
    s.add(ev("closed_write", f"{P}/A/a.mkv", size=1), 0)
    s.set_roots([M, P])
    assert s.pending == [ScanScope(P, "A", True)]
    s.add(ev("closed_write", f"{S}/Show/b.mkv", size=1), 2)
    assert s.pending == [ScanScope(P, "A", True)]


def test_suspend_keeps_pending_and_ignores_new_events():
    s = settler()
    s.add(ev("closed_write", f"{P}/A/a.mkv", size=1), 0)
    s.suspend()
    s.add(ev("closed_write", f"{P}/B/b.mkv", size=1), 1)
    assert s.pending == [ScanScope(P, "A", True)]
    assert s.next_deadline() == 30
    assert run_due(s, 30, {f"{P}/A/a.mkv": 1}) == [ScanScope(P, "A", True)]


def test_set_roots_after_suspend_accepts_again_and_keeps_pending():
    s = settler()
    s.add(ev("closed_write", f"{P}/A/a.mkv", size=1), 0)
    s.suspend()
    s.set_roots([P])
    s.add(ev("closed_write", f"{P}/B/b.mkv", size=1), 1)
    assert s.pending == [ScanScope(P, "A", True), ScanScope(P, "B", True)]


def test_nested_roots_use_the_longest_root():
    s = settler(roots=["/media", "/media/series"])
    s.add(ev("closed_write", "/media/series/Show/x.mkv", size=1), 0)
    assert s.pending == [ScanScope("/media/series", "Show", True)]


def test_event_after_due_rearms_the_scope():
    s = settler()
    f = f"{P}/A/a.mkv"
    s.add(ev("closed_write", f, size=1), 0)
    paths = s.due(30)
    s.add(ev("closed_write", f"{P}/A/b.mkv", size=1), 30.5)  # between due() and settle()
    assert s.settle(31, {p: 1 for p in paths}) == []  # no longer quiet
    assert s.pending == [ScanScope(P, "A", True)]


def test_root_dropped_between_due_and_settle():
    s = settler()
    f = f"{P}/A/a.mkv"
    s.add(ev("closed_write", f, size=1), 0)
    paths = s.due(30)
    s.drop_root(P)
    assert s.settle(30, {p: 1 for p in paths}) == []
    assert s.pending == []
