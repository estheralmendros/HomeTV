"""Linux inotify backend over ctypes. Stdlib only; never writes to the media.

Watches are set on directories only. The root's own watch follows a symlinked root (no
``IN_DONT_FOLLOW``); every other watch does not, and symlinked subdirectories are never walked
(as in the scanner). Event paths always use the root as configured, never its realpath.

``add_tree`` adds a directory's watch **before** listing it, so a subdirectory created during
the walk is either listed or reported by ``IN_CREATE``. ``inotify_add_watch`` on an
already-watched inode returns the existing wd, so re-walking only adds the missing watches.
Every ``add_tree``/``remove`` (worker thread or reader thread) is serialised by ``_tree_lock``.

The reader thread polls the inotify fd and a wake pipe, parses each read (``parse_events``),
maps it (``map_batch``, pure), applies **every drop before any arm** (a renamed directory keeps
its wd: arming the new name first would return that wd and the drop of the old name would then
remove it), stats video files, and calls ``deliver(batch)``. It reports its own health with the
synthetic ``watch_limit`` (ENOSPC while arming a new directory; the thread keeps running) and
``backend_failed`` (any unexpected error; the thread exits) events.
"""

import contextlib
import ctypes
import errno as errno_mod
import logging
import os
import select
import struct
import sys
import threading
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field, replace
from typing import Any

from hometv.changes.backend import (
    SIZED_KINDS,
    Deliver,
    EventKind,
    WatchEvent,
    WatchLimitError,
    WatchUnavailable,
    is_video,
)
from hometv.changes.scope import root_of, within
from hometv.text import undecodable

logger = logging.getLogger("hometv.changes.inotify")

# Kernel constants (<sys/inotify.h>).
IN_MODIFY = 0x00000002
IN_CLOSE_WRITE = 0x00000008
IN_MOVED_FROM = 0x00000040
IN_MOVED_TO = 0x00000080
IN_CREATE = 0x00000100
IN_DELETE = 0x00000200
IN_DELETE_SELF = 0x00000400
IN_MOVE_SELF = 0x00000800
IN_UNMOUNT = 0x00002000
IN_Q_OVERFLOW = 0x00004000
IN_IGNORED = 0x00008000
IN_ONLYDIR = 0x01000000
IN_DONT_FOLLOW = 0x02000000
IN_EXCL_UNLINK = 0x04000000
IN_ISDIR = 0x40000000
IN_NONBLOCK = 0o4000
IN_CLOEXEC = 0o2000000

ROOT_MASK = (
    IN_CREATE
    | IN_DELETE
    | IN_MOVED_FROM
    | IN_MOVED_TO
    | IN_CLOSE_WRITE
    | IN_MODIFY
    | IN_DELETE_SELF
    | IN_MOVE_SELF
    | IN_ONLYDIR
    | IN_EXCL_UNLINK
)
DIR_MASK = ROOT_MASK | IN_DONT_FOLLOW

READ_SIZE = 64 * 1024
JOIN_TIMEOUT = 5.0
_HEADER = struct.Struct("iIII")
_SKIPPED_ERRNOS = {errno_mod.ENOENT, errno_mod.EACCES, errno_mod.ENOTDIR}
_RETRY_ERRNOS = {errno_mod.EINTR, errno_mod.EAGAIN}


@dataclass(frozen=True)
class RawEvent:
    wd: int
    mask: int
    cookie: int
    name: str


def parse_events(buf: bytes) -> list[RawEvent]:
    """Split one ``read()`` of the inotify fd into events (NUL-padded names, ``os.fsdecode``)."""
    events: list[RawEvent] = []
    offset = 0
    while offset + _HEADER.size <= len(buf):
        wd, mask, cookie, length = _HEADER.unpack_from(buf, offset)
        offset += _HEADER.size
        name = buf[offset : offset + length].split(b"\0", 1)[0]
        offset += length
        events.append(RawEvent(wd, mask, cookie, os.fsdecode(name)))
    return events


@dataclass(frozen=True)
class MappedBatch:
    events: list[WatchEvent] = field(default_factory=list)
    arm: list[str] = field(default_factory=list)  # directories to add_tree (after the drops)
    drop: list[int] = field(default_factory=list)  # wds to inotify_rm_watch and unmap
    forget: list[int] = field(default_factory=list)  # wds the kernel removed: unmap only


def _subtree_wds(path: str, wd_paths: Mapping[int, str], root_paths: list[str]) -> list[int]:
    """Wds at or under ``path``, stopping at a nested configured root."""
    owner = root_of(path, root_paths)
    return [wd for wd, p in wd_paths.items() if within(p, path) and root_of(p, root_paths) == owner]


def _skipped_name(name: str) -> bool:
    """Dot and undecodable names are never watched (the scanner and the settler skip them)."""
    return name.startswith(".") or undecodable(name)


def _file_kind(mask: int) -> EventKind | None:
    if mask & IN_CREATE:
        return "created"
    if mask & IN_CLOSE_WRITE:
        return "closed_write"
    if mask & IN_MOVED_TO:
        return "moved_to"
    if mask & IN_MODIFY:
        return "modified"
    if mask & IN_DELETE:
        return "deleted"
    if mask & IN_MOVED_FROM:
        return "moved_from"
    return None


def map_batch(
    raw: list[RawEvent], wd_paths: Mapping[int, str], roots: Mapping[int, str]
) -> MappedBatch:
    """Pure raw-to-``WatchEvent`` mapping of one read. ``roots`` maps the
    wd of each root's own watch to the root as configured."""
    root_paths = sorted(set(roots.values()))
    out = MappedBatch()
    gone: set[str] = set()
    dropped: set[int] = set()
    modified: set[str] = set()

    def drop(wds: list[int]) -> None:
        for wd in wds:
            if wd not in dropped:
                dropped.add(wd)
                out.drop.append(wd)

    def root_gone(root: str) -> None:
        if root in gone:
            return
        gone.add(root)
        out.events.append(WatchEvent("root_gone", root, is_dir=True, root=root))
        drop(_subtree_wds(root, wd_paths, root_paths))

    for event in raw:
        if event.mask & IN_Q_OVERFLOW:
            out.events.append(WatchEvent("overflow", ""))
            continue
        directory = wd_paths.get(event.wd)
        if directory is None or event.wd in dropped:
            continue  # unmapped (already dropped)
        root = root_of(directory, root_paths)
        if root is None or root in gone:
            continue
        if event.mask & IN_UNMOUNT:
            root_gone(root)
            continue
        if event.wd in roots and event.mask & (IN_DELETE_SELF | IN_MOVE_SELF | IN_IGNORED):
            root_gone(roots[event.wd])
            continue
        if event.mask & (IN_IGNORED | IN_DELETE_SELF):
            if event.wd not in out.forget:
                out.forget.append(event.wd)
            continue
        if event.mask & IN_MOVE_SELF:
            continue  # the parent's IN_MOVED_FROM / IN_MOVED_TO handle the move
        path = os.path.join(directory, event.name) if event.name else directory
        kind = _file_kind(event.mask)
        if kind is None:
            continue
        is_dir = bool(event.mask & IN_ISDIR)
        if is_dir:
            if kind in ("created", "moved_to"):
                if not _skipped_name(event.name):
                    out.arm.append(path)
            elif kind == "moved_from":
                drop(_subtree_wds(path, wd_paths, root_paths))
        elif kind == "modified":
            if path in modified:
                continue
            modified.add(path)
        out.events.append(WatchEvent(kind, path, is_dir=is_dir, root=root))
    return out


def _load_libc() -> Any:
    libc = ctypes.CDLL(None, use_errno=True)
    libc.inotify_init1.argtypes = [ctypes.c_int]
    libc.inotify_init1.restype = ctypes.c_int
    libc.inotify_add_watch.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_uint32]
    libc.inotify_add_watch.restype = ctypes.c_int
    libc.inotify_rm_watch.argtypes = [ctypes.c_int, ctypes.c_int]
    libc.inotify_rm_watch.restype = ctypes.c_int
    return libc


class InotifyBackend:
    """``WatchBackend`` over inotify. Constructor seams for tests: ``libc`` (fake ctypes
    functions; errors through ``ctypes.set_errno``), ``scandir``, ``stat``, ``read``,
    ``poll_factory`` and ``read_sleep``."""

    def __init__(
        self,
        *,
        libc: Any = None,
        scandir: Callable[..., Any] = os.scandir,
        stat: Callable[..., os.stat_result] = os.stat,
        read: Callable[[int, int], bytes] = os.read,
        poll_factory: Callable[[], Any] = select.poll,
        read_sleep: float = 0.05,
    ) -> None:
        if libc is None:
            if not sys.platform.startswith("linux"):
                raise WatchUnavailable(errno_mod.ENOSYS, "inotify requires Linux")
            libc = _load_libc()
        self._libc = libc
        self._scandir = scandir
        self._stat = stat
        self._read = read
        self._poll_factory = poll_factory
        self._read_sleep = read_sleep
        fd = libc.inotify_init1(IN_NONBLOCK | IN_CLOEXEC)
        if fd < 0:
            code = ctypes.get_errno()
            raise OSError(code, os.strerror(code))
        self._fd: int | None = fd
        try:
            self._wake_r, self._wake_w = os.pipe()
        except BaseException:
            with contextlib.suppress(OSError):
                os.close(fd)  # e.g. EMFILE: never leak the inotify fd on a retry
            raise
        self._map_lock = threading.Lock()
        self._tree_lock = threading.RLock()
        self._wd_paths: dict[int, str] = {}
        self._path_wds: dict[str, int] = {}
        self._root_wds: dict[int, str] = {}
        self._root_paths: set[str] = set()
        self._removed_wds: set[int] = set()
        self._closing = False
        self._closed = False
        self._thread: threading.Thread | None = None

    # -- WatchBackend --

    def start(self, deliver: Deliver) -> None:
        self._thread = threading.Thread(
            target=self._reader, args=(deliver,), name="hometv-inotify", daemon=True
        )
        self._thread.start()

    def is_alive(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def abort(self) -> None:
        self._closing = True

    def add_tree(self, root: str) -> int:
        with self._tree_lock:
            self._root_paths.add(root)
            return self._add_tree(root, is_root=True, videos=None)

    def remove(self, root: str) -> None:
        with self._tree_lock:
            with self._map_lock:
                wds = _subtree_wds(root, self._wd_paths, sorted(self._root_paths | {root}))
            self._drop(wds)
            self._root_paths.discard(root)

    def close(self) -> None:
        self._closing = True
        if self._closed:
            return
        self._closed = True
        with contextlib.suppress(OSError):
            os.write(self._wake_w, b"x")
        thread = self._thread
        if thread is not None and thread is not threading.current_thread():
            thread.join(JOIN_TIMEOUT)
        for fd in (self._fd, self._wake_r, self._wake_w):
            if fd is not None:
                with contextlib.suppress(OSError):
                    os.close(fd)
        self._fd = None

    # -- watches --

    def _add_watch(self, path: str, mask: int) -> int:
        assert self._fd is not None
        wd = int(self._libc.inotify_add_watch(self._fd, os.fsencode(path), mask))
        if wd < 0:
            code = ctypes.get_errno()
            if code == errno_mod.ENOSPC:
                raise WatchLimitError(code, os.strerror(code), path)
            raise OSError(code, os.strerror(code), path)
        return wd

    def _map(self, wd: int, path: str, is_root: bool) -> bool:
        """Record ``wd -> path``; True when the wd was not mapped to this path before."""
        with self._map_lock:
            self._removed_wds.discard(wd)
            old = self._wd_paths.get(wd)
            if old == path:
                if is_root:
                    self._root_wds[wd] = path
                return False
            if old is not None and self._path_wds.get(old) == wd:
                del self._path_wds[old]
            stale = self._path_wds.get(path)
            if stale is not None and stale != wd:
                self._wd_paths.pop(stale, None)
            self._wd_paths[wd] = path
            self._path_wds[path] = wd
            if is_root:
                self._root_wds[wd] = path
            return True

    def _add_tree(self, start: str, *, is_root: bool, videos: list[WatchEvent] | None) -> int:
        """Iterative walk from ``start`` (``_tree_lock`` held). Errors on ``start`` itself
        propagate; ENOENT/EACCES/ENOTDIR on a subdirectory are skipped and logged; ENOSPC
        always raises ``WatchLimitError``. Aborts (returns the count so far) once closing."""
        added = 0
        stack = [start]
        while stack:
            if self._closing:
                return added
            directory = stack.pop()
            first = directory == start
            try:
                wd = self._add_watch(directory, ROOT_MASK if first and is_root else DIR_MASK)
                if self._map(wd, directory, first and is_root):
                    added += 1
                with self._scandir(directory) as it:
                    entries = sorted(it, key=lambda e: e.name)
            except WatchLimitError:
                raise
            except OSError as exc:
                if first:
                    raise
                if exc.errno in _SKIPPED_ERRNOS:
                    logger.warning(
                        "watch_add_failed: %s: %s",
                        directory,
                        errno_mod.errorcode.get(exc.errno or 0, exc.errno),
                    )
                    continue
                raise
            subdirs: list[str] = []
            for entry in entries:
                name = entry.name
                if _skipped_name(name):
                    continue
                try:
                    is_dir = not entry.is_symlink() and entry.is_dir(follow_symlinks=False)
                except OSError:
                    continue
                if is_dir:
                    subdirs.append(entry.path)
                elif videos is not None and is_video(entry.path):
                    videos.append(WatchEvent("created", entry.path, size=self._size(entry.path)))
            stack.extend(reversed(subdirs))
        return added

    def _unmap(self, wd: int) -> None:
        """Remove ``wd`` from every map (``_map_lock`` held)."""
        path = self._wd_paths.pop(wd, None)
        if path is not None and self._path_wds.get(path) == wd:
            del self._path_wds[path]
        self._root_wds.pop(wd, None)

    def _drop(self, wds: list[int]) -> None:
        """``inotify_rm_watch`` + unmap; the ``IN_IGNORED`` each causes is swallowed. When
        ``rm_watch`` fails (EINVAL: the kernel already removed the watch) the wd is not
        remembered: its ``IN_IGNORED``, read in this batch or still queued, is the kernel's and
        is dropped as unmapped, so ``_removed_wds`` cannot grow."""
        for wd in wds:
            with self._map_lock:
                self._unmap(wd)
                self._removed_wds.add(wd)
            if self._fd is not None and self._libc.inotify_rm_watch(self._fd, wd) < 0:
                with self._map_lock:
                    self._removed_wds.discard(wd)

    def _forget(self, wds: list[int]) -> None:
        with self._map_lock:
            for wd in wds:
                self._unmap(wd)

    def _size(self, path: str) -> int | None:
        try:
            return self._stat(path).st_size
        except OSError:
            return None

    # -- reader thread --

    def _reader(self, deliver: Deliver) -> None:
        try:
            poller = self._poll_factory()
            poller.register(self._fd, select.POLLIN)
            poller.register(self._wake_r, select.POLLIN)
            while not self._closing:
                ready = poller.poll()
                if self._closing or any(fd == self._wake_r for fd, _ in ready):
                    return
                try:
                    assert self._fd is not None
                    buf = self._read(self._fd, READ_SIZE)
                except OSError as exc:
                    if exc.errno in _RETRY_ERRNOS:
                        continue
                    raise
                batch = self._process(parse_events(buf))
                if batch:
                    try:
                        deliver(batch)
                    except Exception:
                        logger.exception("watch_deliver_failed")
                        return
                    if self._read_sleep > 0:
                        time.sleep(self._read_sleep)
        except Exception as exc:
            if self._closing:
                return
            logger.exception("watch_reader_failed")
            failed = [WatchEvent("backend_failed", "", detail=f"{type(exc).__name__}: {exc}")]
            try:
                deliver(failed)
            except Exception:
                logger.exception("watch_deliver_failed")

    def _process(self, raw: list[RawEvent]) -> list[WatchEvent]:
        with self._map_lock:
            kept: list[RawEvent] = []
            for raw_event in raw:
                if raw_event.mask & IN_IGNORED and raw_event.wd in self._removed_wds:
                    self._removed_wds.discard(raw_event.wd)  # caused by our own rm_watch
                    continue
                kept.append(raw_event)
            wd_paths = dict(self._wd_paths)
            roots = dict(self._root_wds)
        mapped = map_batch(kept, wd_paths, roots)
        limit: list[WatchEvent] = []
        synthetic: dict[str, list[WatchEvent]] = {}
        with self._tree_lock:
            self._forget(mapped.forget)
            self._drop(mapped.drop)  # every drop before any arm
            for directory in mapped.arm:
                if self._closing:
                    break
                videos: list[WatchEvent] = []
                try:
                    self._add_tree(directory, is_root=False, videos=videos)
                except WatchLimitError:
                    root = root_of(directory, self._root_paths)
                    limit.append(WatchEvent("watch_limit", directory, is_dir=True, root=root))
                    continue
                except OSError as exc:
                    logger.warning(
                        "watch_add_failed: %s: %s",
                        directory,
                        errno_mod.errorcode.get(exc.errno or 0, exc.errno),
                    )
                    continue
                synthetic[directory] = videos
        events: list[WatchEvent] = []
        for event in mapped.events:
            if event.kind in SIZED_KINDS and not event.is_dir and is_video(event.path):
                event = replace(event, size=self._size(event.path))
            events.append(event)
            if event.is_dir and event.path in synthetic:
                events.extend(replace(v, root=event.root) for v in synthetic.pop(event.path))
        return events + limit
