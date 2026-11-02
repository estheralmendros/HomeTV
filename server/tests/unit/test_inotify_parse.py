"""``changes.inotify`` pure parts: ``parse_events`` and ``map_batch``."""

import struct

import pytest

from hometv.changes import inotify as ino
from hometv.changes.backend import WatchEvent
from hometv.changes.inotify import RawEvent, map_batch, parse_events


def pack(wd: int, mask: int, cookie: int = 0, name: bytes = b"") -> bytes:
    if name:
        length = (len(name) // 16 + 1) * 16
        name = name.ljust(length, b"\0")
    return struct.pack("iIII", wd, mask, cookie, len(name)) + name


def test_parse_several_events_padded_and_non_utf8_names():
    buf = (
        pack(1, ino.IN_CREATE, 0, b"Dune (2021).mkv")
        + pack(2, ino.IN_MOVED_FROM | ino.IN_ISDIR, 7, b"Old Show")
        + pack(3, ino.IN_DELETE_SELF)
        + pack(1, ino.IN_CREATE, 0, b"\xff.mkv")
        + pack(1, ino.IN_CLOSE_WRITE, 0, b"exactly16bytes!!")
    )
    assert parse_events(buf) == [
        RawEvent(1, ino.IN_CREATE, 0, "Dune (2021).mkv"),
        RawEvent(2, ino.IN_MOVED_FROM | ino.IN_ISDIR, 7, "Old Show"),
        RawEvent(3, ino.IN_DELETE_SELF, 0, ""),
        RawEvent(1, ino.IN_CREATE, 0, "\udcff.mkv"),
        RawEvent(1, ino.IN_CLOSE_WRITE, 0, "exactly16bytes!!"),
    ]


def test_parse_empty_and_overflow():
    assert parse_events(b"") == []
    assert parse_events(pack(-1, ino.IN_Q_OVERFLOW)) == [RawEvent(-1, ino.IN_Q_OVERFLOW, 0, "")]


S = "/media/series"
P = "/media/películas"
WD = {1: S, 2: f"{S}/Old Show", 3: f"{S}/Old Show/T1", 10: P, 11: f"{P}/A"}
ROOTS = {1: S, 10: P}


def mapped(raw):
    return map_batch(raw, WD, ROOTS)


def test_repeated_modify_collapses_to_one_event():
    raw = [RawEvent(11, ino.IN_MODIFY, 0, "Alien.mkv")] * 500
    batch = mapped(raw)
    assert batch.events == [WatchEvent("modified", f"{P}/A/Alien.mkv", root=P)]


@pytest.mark.parametrize(
    ("raw", "events", "arm", "drop", "forget"),
    [
        (
            RawEvent(-1, ino.IN_Q_OVERFLOW, 0, ""),
            [WatchEvent("overflow", "")],
            [],
            [],
            [],
        ),
        (
            RawEvent(11, ino.IN_CREATE, 0, "Dune.mkv"),
            [WatchEvent("created", f"{P}/A/Dune.mkv", root=P)],
            [],
            [],
            [],
        ),
        (
            RawEvent(11, ino.IN_CLOSE_WRITE, 0, "Dune.mkv"),
            [WatchEvent("closed_write", f"{P}/A/Dune.mkv", root=P)],
            [],
            [],
            [],
        ),
        (
            RawEvent(11, ino.IN_MOVED_TO, 0, "Dune.mkv"),
            [WatchEvent("moved_to", f"{P}/A/Dune.mkv", root=P)],
            [],
            [],
            [],
        ),
        (
            RawEvent(11, ino.IN_DELETE, 0, "Dune.mkv"),
            [WatchEvent("deleted", f"{P}/A/Dune.mkv", root=P)],
            [],
            [],
            [],
        ),
        (
            RawEvent(11, ino.IN_MOVED_FROM, 0, "Dune.mkv"),
            [WatchEvent("moved_from", f"{P}/A/Dune.mkv", root=P)],
            [],
            [],
            [],
        ),
        (
            RawEvent(10, ino.IN_CREATE | ino.IN_ISDIR, 0, "B"),
            [WatchEvent("created", f"{P}/B", is_dir=True, root=P)],
            [f"{P}/B"],
            [],
            [],
        ),
        (
            RawEvent(1, ino.IN_MOVED_TO | ino.IN_ISDIR, 0, "New Show"),
            [WatchEvent("moved_to", f"{S}/New Show", is_dir=True, root=S)],
            [f"{S}/New Show"],
            [],
            [],
        ),
        (  # dot directories are never armed (the scanner and the settler skip them too)
            RawEvent(10, ino.IN_CREATE | ino.IN_ISDIR, 0, ".Trash-1000"),
            [WatchEvent("created", f"{P}/.Trash-1000", is_dir=True, root=P)],
            [],
            [],
            [],
        ),
        (
            RawEvent(1, ino.IN_MOVED_TO | ino.IN_ISDIR, 0, ".rsync-partial"),
            [WatchEvent("moved_to", f"{S}/.rsync-partial", is_dir=True, root=S)],
            [],
            [],
            [],
        ),
        (  # undecodable directory names are never armed either
            RawEvent(10, ino.IN_CREATE | ino.IN_ISDIR, 0, "\udcffB"),
            [WatchEvent("created", f"{P}/\udcffB", is_dir=True, root=P)],
            [],
            [],
            [],
        ),
        (
            RawEvent(1, ino.IN_MOVED_TO | ino.IN_ISDIR, 0, "\udcffShow"),
            [WatchEvent("moved_to", f"{S}/\udcffShow", is_dir=True, root=S)],
            [],
            [],
            [],
        ),
        (
            RawEvent(1, ino.IN_MOVED_FROM | ino.IN_ISDIR, 0, "Old Show"),
            [WatchEvent("moved_from", f"{S}/Old Show", is_dir=True, root=S)],
            [],
            [2, 3],
            [],
        ),
        (
            RawEvent(1, ino.IN_DELETE | ino.IN_ISDIR, 0, "Gone"),
            [WatchEvent("deleted", f"{S}/Gone", is_dir=True, root=S)],
            [],
            [],
            [],
        ),
        (  # IN_MOVE_SELF on a non-root wd: no event, no drop
            RawEvent(2, ino.IN_MOVE_SELF, 0, ""),
            [],
            [],
            [],
            [],
        ),
        (  # IN_IGNORED / IN_DELETE_SELF on a non-root wd: mapping dropped silently
            RawEvent(3, ino.IN_IGNORED, 0, ""),
            [],
            [],
            [],
            [3],
        ),
        (RawEvent(3, ino.IN_DELETE_SELF, 0, ""), [], [], [], [3]),
        (  # unmapped wd: ignored
            RawEvent(99, ino.IN_CREATE, 0, "x.mkv"),
            [],
            [],
            [],
            [],
        ),
        (
            RawEvent(1, ino.IN_DELETE_SELF, 0, ""),
            [WatchEvent("root_gone", S, is_dir=True, root=S)],
            [],
            [1, 2, 3],
            [],
        ),
        (
            RawEvent(10, ino.IN_MOVE_SELF, 0, ""),
            [WatchEvent("root_gone", P, is_dir=True, root=P)],
            [],
            [10, 11],
            [],
        ),
        (
            RawEvent(10, ino.IN_IGNORED, 0, ""),
            [WatchEvent("root_gone", P, is_dir=True, root=P)],
            [],
            [10, 11],
            [],
        ),
        (  # IN_UNMOUNT on any wd: root_gone of its root
            RawEvent(3, ino.IN_UNMOUNT, 0, ""),
            [WatchEvent("root_gone", S, is_dir=True, root=S)],
            [],
            [1, 2, 3],
            [],
        ),
    ],
)
def test_map_batch_rows(raw, events, arm, drop, forget):
    batch = mapped([raw])
    assert batch.events == events
    assert batch.arm == arm
    assert sorted(batch.drop) == drop
    assert batch.forget == forget


def test_root_gone_is_deduplicated_and_hides_later_events_of_that_root():
    raw = [
        RawEvent(3, ino.IN_UNMOUNT, 0, ""),
        RawEvent(2, ino.IN_UNMOUNT, 0, ""),
        RawEvent(1, ino.IN_UNMOUNT, 0, ""),
        RawEvent(1, ino.IN_IGNORED, 0, ""),
        RawEvent(2, ino.IN_CREATE, 0, "x.mkv"),
        RawEvent(11, ino.IN_CREATE, 0, "y.mkv"),
    ]
    batch = mapped(raw)
    assert batch.events == [
        WatchEvent("root_gone", S, is_dir=True, root=S),
        WatchEvent("created", f"{P}/A/y.mkv", root=P),
    ]


def test_rename_in_one_batch_drops_old_subtree_and_arms_new():
    raw = [
        RawEvent(1, ino.IN_MOVED_FROM | ino.IN_ISDIR, 5, "Old Show"),
        RawEvent(1, ino.IN_MOVED_TO | ino.IN_ISDIR, 5, "New Show"),
        RawEvent(2, ino.IN_MOVE_SELF, 0, ""),
    ]
    batch = mapped(raw)
    assert [e.kind for e in batch.events] == ["moved_from", "moved_to"]
    assert sorted(batch.drop) == [2, 3]
    assert batch.arm == [f"{S}/New Show"]


def test_subtree_drop_stops_at_a_nested_root():
    wd = {1: "/media", 2: "/media/series", 3: "/media/series/Show", 4: "/media/MKV"}
    roots = {1: "/media", 2: "/media/series"}
    batch = map_batch([RawEvent(1, ino.IN_DELETE_SELF, 0, "")], wd, roots)
    assert sorted(batch.drop) == [1, 4]
    show = map_batch([RawEvent(2, ino.IN_CREATE, 0, "x.mkv")], wd, roots)
    assert show.events == [WatchEvent("created", "/media/series/x.mkv", root="/media/series")]
