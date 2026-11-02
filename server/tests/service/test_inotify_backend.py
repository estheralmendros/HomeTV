"""``InotifyBackend`` with a fake ``libc`` and a scripted ``read`` (every OS).

``FakeLibc`` behaves like the kernel where it matters: ``inotify_add_watch`` on an inode that is
already watched returns the existing wd; errors go through ``ctypes.set_errno``. Its
``inotify_init1`` returns the read end of a real pipe, so the real ``select.poll`` wakes up when
``Script.feed`` queues a buffer (one byte per buffer); the scripted ``read`` drains that byte.
"""

import contextlib
import ctypes
import errno
import os
import queue
import struct
import threading
import time
from pathlib import Path

import pytest

from hometv.changes import inotify as ino
from hometv.changes.backend import WatchEvent, WatchLimitError
from hometv.changes.inotify import InotifyBackend


def pack(wd: int, mask: int, name: bytes = b"", cookie: int = 0) -> bytes:
    if name:
        name = name.ljust((len(name) // 16 + 1) * 16, b"\0")
    return struct.pack("iIII", wd, mask, cookie, len(name)) + name


class FakeLibc:
    def __init__(self) -> None:
        self.calls: list[tuple] = []
        self.inodes: dict[tuple[int, int], int] = {}
        self.next_wd = 1
        self.fail: dict[str, int] = {}  # path -> errno for inotify_add_watch
        self.fail_all: int | None = None
        self.r, self.w = os.pipe()
        self.lock = threading.Lock()

    def inotify_init1(self, flags: int) -> int:
        return self.r

    def inotify_add_watch(self, fd: int, path: bytes, mask: int) -> int:
        p = os.fsdecode(path)
        with self.lock:
            self.calls.append(("add", p, mask))
            code = self.fail.get(p, self.fail_all)
            if code is not None:
                ctypes.set_errno(code)
                return -1
            try:
                st = os.stat(p)
            except OSError as exc:
                ctypes.set_errno(exc.errno or errno.ENOENT)
                return -1
            key = (st.st_dev, st.st_ino)
            if key not in self.inodes:
                self.inodes[key] = self.next_wd
                self.next_wd += 1
            return self.inodes[key]

    def inotify_rm_watch(self, fd: int, wd: int) -> int:
        with self.lock:
            self.calls.append(("rm", wd))
            for key, value in list(self.inodes.items()):
                if value == wd:
                    del self.inodes[key]
                    return 0
        ctypes.set_errno(errno.EINVAL)
        return -1

    def wd_of(self, path: Path) -> int:
        st = os.stat(path)
        return self.inodes[(st.st_dev, st.st_ino)]


class Script:
    """Scripted ``read``: each fed item is bytes (returned) or an exception (raised)."""

    def __init__(self, libc: FakeLibc) -> None:
        self.libc = libc
        self.items: queue.Queue[object] = queue.Queue()

    def feed(self, item: object) -> None:
        self.items.put(item)
        os.write(self.libc.w, b"!")

    def __call__(self, fd: int, size: int) -> bytes:
        os.read(fd, 1)
        item = self.items.get_nowait()
        if isinstance(item, BaseException):
            raise item
        assert isinstance(item, bytes)
        return item


class Recorder:
    def __init__(self) -> None:
        self.batches: list[list[WatchEvent]] = []
        self.cond = threading.Condition()
        self.raise_on_deliver = False

    def __call__(self, batch: list[WatchEvent]) -> None:
        with self.cond:
            self.batches.append(batch)
            self.cond.notify_all()
        if self.raise_on_deliver:
            raise RuntimeError("deliver broke")

    def wait(self, n: int, timeout: float = 5.0) -> None:
        with self.cond:
            assert self.cond.wait_for(lambda: len(self.batches) >= n, timeout), self.batches


def wait_for(predicate, timeout: float = 5.0) -> None:
    deadline = time.monotonic() + timeout
    while not predicate():
        assert time.monotonic() < deadline, "condition not reached"
        time.sleep(0.01)


@pytest.fixture
def libc():
    fake = FakeLibc()
    yield fake
    with contextlib.suppress(OSError):
        os.close(fake.w)


def make(libc: FakeLibc, **kw) -> tuple[InotifyBackend, Script, Recorder]:
    script = Script(libc)
    backend = InotifyBackend(libc=libc, read=script, read_sleep=0, **kw)
    recorder = Recorder()
    return backend, script, recorder


def tree(tmp_path: Path) -> Path:
    root = tmp_path / "series"
    (root / "Old Show" / "T1").mkdir(parents=True)
    (root / "Other").mkdir()
    (root / ".hidden").mkdir()
    (root / "loose.mkv").write_bytes(b"x")
    return root


def test_add_tree_watches_before_listing_and_root_mask_follows_symlinks(libc, tmp_path):
    root = tree(tmp_path)
    timeline: list[str] = []
    real_scandir = os.scandir
    original_add = libc.inotify_add_watch

    def scandir(path):
        timeline.append(f"scan:{os.fspath(path)}")
        return real_scandir(path)

    def add(fd, path, mask):
        timeline.append(f"add:{os.fsdecode(path)}")
        return original_add(fd, path, mask)

    libc.inotify_add_watch = add
    backend, _, _ = make(libc, scandir=scandir)
    try:
        assert backend.add_tree(str(root)) == 4  # series, Old Show, Old Show/T1, Other
        for path in (root, root / "Old Show", root / "Old Show" / "T1", root / "Other"):
            assert timeline.index(f"add:{path}") < timeline.index(f"scan:{path}")
        assert f"add:{root / '.hidden'}" not in timeline
        masks = {path: mask for kind, path, mask in libc.calls if kind == "add"}
        assert not masks[str(root)] & ino.IN_DONT_FOLLOW
        assert masks[str(root / "Other")] & ino.IN_DONT_FOLLOW
        assert all(m & ino.IN_ONLYDIR for m in masks.values())
        maps = dict(backend._wd_paths)
        assert backend.add_tree(str(root)) == 0  # idempotent: nothing new in the maps
        assert backend._wd_paths == maps
    finally:
        backend.close()


def test_close_during_add_tree_aborts_it(libc, tmp_path):
    root = tmp_path / "root"
    for i in range(5):
        (root / f"d{i}").mkdir(parents=True)
    real_scandir = os.scandir
    backend: InotifyBackend

    def scandir(path):
        backend.abort()  # what close() does first, from another thread
        return real_scandir(path)

    backend, _, _ = make(libc, scandir=scandir)
    try:
        assert backend.add_tree(str(root)) == 1
        assert len([c for c in libc.calls if c[0] == "add"]) == 1
    finally:
        backend.close()


def test_enospc_raises_watch_limit_error(libc, tmp_path):
    root = tree(tmp_path)
    libc.fail[str(root / "Other")] = errno.ENOSPC
    backend, _, _ = make(libc)
    try:
        with pytest.raises(WatchLimitError):
            backend.add_tree(str(root))
    finally:
        backend.close()


def test_subdirectory_errors_are_skipped_root_errors_propagate(libc, tmp_path):
    root = tree(tmp_path)
    libc.fail[str(root / "Other")] = errno.EACCES
    backend, _, _ = make(libc)
    try:
        assert backend.add_tree(str(root)) == 3
        libc.fail[str(root)] = errno.EACCES
        with pytest.raises(OSError):
            backend.add_tree(str(root))
        with pytest.raises(OSError):
            backend.add_tree(str(tmp_path / "missing"))
    finally:
        backend.close()


def test_init_failure_raises_oserror(tmp_path):
    class Broken(FakeLibc):
        def inotify_init1(self, flags):
            ctypes.set_errno(errno.EMFILE)
            return -1

    with pytest.raises(OSError) as exc:
        InotifyBackend(libc=Broken())
    assert exc.value.errno == errno.EMFILE


def test_init_closes_the_inotify_fd_when_a_later_step_fails(libc, monkeypatch):
    def no_pipe():
        raise OSError(errno.EMFILE, "Too many open files")

    monkeypatch.setattr(ino.os, "pipe", no_pipe)
    with pytest.raises(OSError) as exc:
        InotifyBackend(libc=libc)
    assert exc.value.errno == errno.EMFILE
    with pytest.raises(OSError) as closed:
        os.fstat(libc.r)
    assert closed.value.errno == errno.EBADF


def test_reader_delivers_events_with_sizes(libc, tmp_path):
    root = tree(tmp_path)
    backend, script, rec = make(libc)
    backend.add_tree(str(root))
    backend.start(rec)
    try:
        (root / "Other" / "a.mkv").write_bytes(b"12345")
        wd = libc.wd_of(root / "Other")
        script.feed(pack(wd, ino.IN_CLOSE_WRITE, b"a.mkv") + pack(wd, ino.IN_MODIFY, b"a.mkv"))
        rec.wait(1)
        assert rec.batches[0] == [
            WatchEvent("closed_write", str(root / "Other" / "a.mkv"), root=str(root), size=5),
            WatchEvent("modified", str(root / "Other" / "a.mkv"), root=str(root)),
        ]
    finally:
        backend.close()
    assert not backend.is_alive()


def test_new_directory_is_armed_with_synthetic_created_events(libc, tmp_path):
    root = tree(tmp_path)
    backend, script, rec = make(libc)
    backend.add_tree(str(root))
    backend.start(rec)
    try:
        new = root / "New Show" / "T1"
        new.mkdir(parents=True)
        (new / "e1.mkv").write_bytes(b"abc")
        (new / "e1.es.srt").write_bytes(b"s")
        script.feed(pack(libc.wd_of(root), ino.IN_CREATE | ino.IN_ISDIR, b"New Show"))
        rec.wait(1)
        assert rec.batches[0] == [
            WatchEvent("created", str(root / "New Show"), is_dir=True, root=str(root)),
            WatchEvent("created", str(new / "e1.mkv"), root=str(root), size=3),
        ]
        wd = libc.wd_of(new)
        script.feed(pack(wd, ino.IN_DELETE, b"e1.mkv"))
        rec.wait(2)
        assert rec.batches[1] == [WatchEvent("deleted", str(new / "e1.mkv"), root=str(root))]
    finally:
        backend.close()


def test_watch_limit_while_arming_keeps_the_reader_alive(libc, tmp_path):
    root = tree(tmp_path)
    backend, script, rec = make(libc)
    backend.add_tree(str(root))
    backend.start(rec)
    try:
        (root / "Big").mkdir()
        libc.fail[str(root / "Big")] = errno.ENOSPC
        script.feed(pack(libc.wd_of(root), ino.IN_CREATE | ino.IN_ISDIR, b"Big"))
        rec.wait(1)
        big = str(root / "Big")
        assert rec.batches[0] == [
            WatchEvent("created", big, is_dir=True, root=str(root)),
            WatchEvent("watch_limit", big, is_dir=True, root=str(root)),
        ]
        assert backend.is_alive()
        script.feed(pack(libc.wd_of(root), ino.IN_DELETE, b"loose.mkv"))
        rec.wait(2)
        assert rec.batches[1] == [WatchEvent("deleted", str(root / "loose.mkv"), root=str(root))]
    finally:
        backend.close()


def test_vanished_new_directory_is_skipped(libc, tmp_path):
    root = tree(tmp_path)
    backend, script, rec = make(libc)
    backend.add_tree(str(root))
    backend.start(rec)
    try:
        script.feed(pack(libc.wd_of(root), ino.IN_CREATE | ino.IN_ISDIR, b"Gone"))
        rec.wait(1)
        assert rec.batches[0] == [
            WatchEvent("created", str(root / "Gone"), is_dir=True, root=str(root))
        ]
        assert backend.is_alive()
    finally:
        backend.close()


def test_read_error_delivers_backend_failed_and_exits(libc, tmp_path):
    root = tree(tmp_path)
    backend, script, rec = make(libc)
    backend.add_tree(str(root))
    backend.start(rec)
    script.feed(OSError(errno.EBADF, "Bad file descriptor"))
    rec.wait(1)
    [[event]] = rec.batches
    assert event.kind == "backend_failed"
    assert event.detail == "OSError: [Errno 9] Bad file descriptor"
    wait_for(lambda: not backend.is_alive())
    backend.close()
    backend.close()  # idempotent


def test_eintr_is_retried(libc, tmp_path):
    root = tree(tmp_path)
    backend, script, rec = make(libc)
    backend.add_tree(str(root))
    backend.start(rec)
    try:
        script.feed(OSError(errno.EINTR, "Interrupted"))
        script.feed(pack(libc.wd_of(root), ino.IN_DELETE, b"loose.mkv"))
        rec.wait(1)
        assert [e.kind for e in rec.batches[0]] == ["deleted"]
        assert backend.is_alive()
    finally:
        backend.close()


def test_deliver_raising_ends_the_thread(libc, tmp_path):
    root = tree(tmp_path)
    backend, script, rec = make(libc)
    rec.raise_on_deliver = True
    backend.add_tree(str(root))
    backend.start(rec)
    script.feed(pack(libc.wd_of(root), ino.IN_DELETE, b"loose.mkv"))
    rec.wait(1)
    wait_for(lambda: not backend.is_alive())
    assert len(rec.batches) == 1  # no backend_failed after a deliver failure
    backend.close()


def test_directory_rename_drops_before_arming(libc, tmp_path):
    root = tree(tmp_path)
    backend, script, rec = make(libc)
    backend.add_tree(str(root))
    old_wd = libc.wd_of(root / "Old Show")
    old_sub_wd = libc.wd_of(root / "Old Show" / "T1")
    backend.start(rec)
    try:
        os.rename(root / "Old Show", root / "New Show")
        libc.calls.clear()
        rwd = libc.wd_of(root)
        script.feed(
            pack(rwd, ino.IN_MOVED_FROM | ino.IN_ISDIR, b"Old Show", 9)
            + pack(rwd, ino.IN_MOVED_TO | ino.IN_ISDIR, b"New Show", 9)
            + pack(old_wd, ino.IN_MOVE_SELF)
        )
        rec.wait(1)
        rm_old = libc.calls.index(("rm", old_wd))
        add_new = next(
            i for i, c in enumerate(libc.calls) if c[0] == "add" and c[1] == str(root / "New Show")
        )
        assert rm_old < add_new
        assert ("rm", old_sub_wd) in libc.calls
        paths = set(backend._wd_paths.values())
        assert str(root / "New Show") in paths
        assert str(root / "New Show" / "T1") in paths
        assert not any("Old Show" in p for p in paths)
        new_wd = libc.wd_of(root / "New Show")
        assert backend._wd_paths[new_wd] == str(root / "New Show")
        # the IN_IGNORED of the old wd (caused by our rm_watch) leaves the new mapping alone
        script.feed(pack(old_wd, ino.IN_IGNORED) + pack(new_wd, ino.IN_CREATE, b"e.mkv"))
        rec.wait(2)
        assert rec.batches[1] == [
            WatchEvent("created", str(root / "New Show" / "e.mkv"), root=str(root))
        ]
        assert backend._wd_paths[new_wd] == str(root / "New Show")
    finally:
        backend.close()


def test_root_gone_drops_every_watch_and_remove_clears_subtree(libc, tmp_path):
    root = tree(tmp_path)
    backend, script, rec = make(libc)
    backend.add_tree(str(root))
    backend.start(rec)
    try:
        script.feed(pack(libc.wd_of(root), ino.IN_DELETE_SELF))
        rec.wait(1)
        assert rec.batches[0] == [WatchEvent("root_gone", str(root), is_dir=True, root=str(root))]
        assert backend._wd_paths == {}
    finally:
        backend.close()


def test_kernel_side_removal_in_the_same_batch_leaves_no_removed_wd(libc, tmp_path):
    root = tree(tmp_path)
    backend, script, rec = make(libc)
    backend.add_tree(str(root))
    backend.start(rec)
    try:
        rwd = libc.wd_of(root)
        libc.inodes.clear()  # the kernel already removed every watch: rm_watch -> EINVAL
        script.feed(pack(rwd, ino.IN_DELETE_SELF) + pack(rwd, ino.IN_IGNORED))
        rec.wait(1)
        assert rec.batches[0] == [WatchEvent("root_gone", str(root), is_dir=True, root=str(root))]
        assert backend._wd_paths == {}
        assert backend._removed_wds == set()
    finally:
        backend.close()


def test_own_rm_watch_keeps_the_wd_until_its_in_ignored(libc, tmp_path):
    root = tree(tmp_path)
    backend, script, rec = make(libc)
    backend.add_tree(str(root))
    wds = sorted(backend._wd_paths)
    backend.start(rec)
    try:
        backend.remove(str(root))
        assert backend._removed_wds == set(wds)  # rm_watch succeeded: IN_IGNOREDs still due
        script.feed(b"".join(pack(wd, ino.IN_IGNORED) for wd in wds))
        wait_for(lambda: backend._removed_wds == set())
        assert rec.batches == []
    finally:
        backend.close()


def test_remove_drops_watches_of_the_root(libc, tmp_path):
    root = tree(tmp_path)
    backend, _, _ = make(libc)
    try:
        backend.add_tree(str(root))
        assert len(backend._wd_paths) == 4
        backend.remove(str(root))
        assert backend._wd_paths == {}
        assert len([c for c in libc.calls if c[0] == "rm"]) == 4
    finally:
        backend.close()


def test_symlinked_root_reports_paths_under_the_stored_root(libc, tmp_path):
    real = tree(tmp_path)
    link = tmp_path / "link"
    link.symlink_to(real, target_is_directory=True)
    backend, script, rec = make(libc)
    backend.add_tree(str(link))
    backend.start(rec)
    try:
        script.feed(pack(libc.wd_of(real / "Other"), ino.IN_DELETE, b"x.mkv"))
        rec.wait(1)
        assert rec.batches[0] == [
            WatchEvent("deleted", str(link / "Other" / "x.mkv"), root=str(link))
        ]
    finally:
        backend.close()


def test_non_linux_without_libc_is_unavailable(monkeypatch):
    from hometv.changes.backend import WatchUnavailable

    monkeypatch.setattr(ino.sys, "platform", "darwin")
    with pytest.raises(WatchUnavailable):
        InotifyBackend()
