"""Watch backend protocol and events.

``deliver`` is called **from the backend's reader thread** with one batch per read, never from
the event loop. ``watch_limit`` and ``backend_failed`` are never produced by the kernel: the
reader thread reports its own health with them.
"""

import os
from collections.abc import Callable
from dataclasses import dataclass
from typing import Literal, Protocol

from hometv.parser import VIDEO_EXTENSIONS

EventKind = Literal[
    "created",
    "deleted",
    "moved_from",
    "moved_to",
    "modified",
    "closed_write",
    "overflow",
    "root_gone",
    "watch_limit",  # synthetic backend-health signals
    "backend_failed",
]
HEALTH_KINDS: frozenset[str] = frozenset({"overflow", "root_gone", "watch_limit", "backend_failed"})
SIZED_KINDS: frozenset[str] = frozenset({"created", "closed_write", "moved_to"})  # carry a size


def file_extension(path: str) -> str:
    """Lower-cased extension with the dot (``""`` when none)."""
    return os.path.splitext(path)[1].lower()


def is_video(path: str) -> bool:
    return file_extension(path) in VIDEO_EXTENSIONS


@dataclass(frozen=True)
class WatchEvent:
    kind: EventKind
    path: str  # absolute; "" for "overflow" and "backend_failed"
    is_dir: bool = False
    root: str | None = None  # the root, for "root_gone" (and filled for every event)
    size: int | None = None  # stat size for video files on created/closed_write/moved_to
    detail: str | None = None  # "backend_failed": "<ExcType>: <message>"


class WatchUnavailable(OSError):
    """The platform has no inotify (not Linux)."""


class WatchLimitError(OSError):
    """``inotify_add_watch`` failed with ENOSPC (``fs.inotify.max_user_watches``)."""


Deliver = Callable[[list[WatchEvent]], None]


class WatchBackend(Protocol):
    def start(self, deliver: Deliver) -> None: ...  # starts the reader thread

    def add_tree(self, root: str) -> int: ...  # recursive; returns watches added; blocking

    def remove(self, root: str) -> None: ...  # every watch under root

    def is_alive(self) -> bool: ...  # reader thread started and not exited

    def abort(self) -> None: ...  # any thread: an in-flight add_tree stops at the next dir

    def close(self) -> None: ...  # stops and joins the reader thread; idempotent
