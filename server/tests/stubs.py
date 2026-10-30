"""Fake subprocesses for service tests (keyframe ffprobe, HLS ffmpeg, subtitle ffmpeg), and a
fake change-detection backend.

``FakeProc`` implements the ``procs.Proc`` protocol: ``stdout``/``stderr`` are real
``asyncio.StreamReader``s the scenario feeds; signals are recorded; SIGSTOP/SIGCONT toggle
``stopped``; SIGTERM/SIGKILL end it (unless ``ignore_kill``).

``FakeWatchBackend`` implements ``changes.backend.WatchBackend``: ``emit(events)`` calls the
``deliver`` callback (from any thread, like the reader thread); ``calls`` records
``("add_tree"|"remove"|"close", root)``; ``fail_with[root]`` makes ``add_tree(root)`` raise;
``alive`` drives ``is_alive()``; ``gate(op, roots=None)`` blocks that operation (in its worker
thread) until ``open_gate(op)`` or ``abort()``; ``max_active`` observes concurrent calls.

``capture_selects(db)`` / ``capture_writes(db)``: context managers collecting the SQL the read
engine runs (SELECTs with parameters) / every statement the write engine runs.
"""

import asyncio
import itertools
import signal
import threading
import time
from collections.abc import Awaitable, Callable, Iterator, Sequence
from contextlib import contextmanager
from typing import Any

from sqlalchemy import event

from hometv.db.engine import Database

_pids = itertools.count(50_000)


class FakeProc:
    def __init__(self, argv: Sequence[str]) -> None:
        self.argv = list(argv)
        self.pid = next(_pids)
        self.returncode: int | None = None
        self.stdout = asyncio.StreamReader(limit=1024 * 1024)
        self.stderr = asyncio.StreamReader()
        self.signals: list[int] = []
        self.stopped = False
        self.ignore_kill = False
        self._exit = asyncio.Event()
        self._resumed = asyncio.Event()
        self._resumed.set()

    async def wait(self) -> int:
        await self._exit.wait()
        assert self.returncode is not None
        return self.returncode

    def finish(self, code: int = 0) -> None:
        if self.returncode is not None:
            return
        self.returncode = code
        self.stdout.feed_eof()
        self.stderr.feed_eof()
        self._exit.set()
        self._resumed.set()

    @property
    def running(self) -> bool:
        return self.returncode is None

    async def checkpoint(self) -> None:
        """Scenario helper: block while SIGSTOPped."""
        await self._resumed.wait()

    def send_signal(self, sig: int) -> None:
        self.signals.append(sig)
        if sig == signal.SIGSTOP:
            self.stopped = True
            self._resumed.clear()
        elif sig == signal.SIGCONT:
            self.stopped = False
            self._resumed.set()
        elif sig == signal.SIGTERM:
            self.finish(-15)
        elif sig == signal.SIGKILL:
            self.kill()

    def kill(self) -> None:
        if signal.SIGKILL not in self.signals:
            self.signals.append(signal.SIGKILL)
        if not self.ignore_kill:
            self.finish(-9)


Scenario = Callable[[FakeProc], Awaitable[Any]]


class Spawner:
    """``spawn`` replacement: records argv, runs ``scenario(proc)`` as a task per process."""

    def __init__(self, scenario: Scenario) -> None:
        self.scenario = scenario
        self.procs: list[FakeProc] = []
        self.tasks: list[asyncio.Task[Any]] = []
        self.active = 0
        self.max_active = 0
        self.exits = 0

    @property
    def count(self) -> int:
        return len(self.procs)

    async def __call__(self, argv: Sequence[str], **_kwargs: Any) -> FakeProc:
        proc = FakeProc(argv)
        self.procs.append(proc)
        self.active += 1
        self.max_active = max(self.max_active, self.active)

        async def run() -> None:
            try:
                await self.scenario(proc)
            finally:
                if not proc.ignore_kill:
                    proc.finish(proc.returncode if proc.returncode is not None else 0)
                self.active -= 1
                self.exits += 1

        self.tasks.append(asyncio.create_task(run()))
        return proc

    async def close(self) -> None:
        for task in self.tasks:
            task.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)


class ScaledClock:
    """Monotonic clock running ``factor`` times faster than real time."""

    def __init__(self, factor: float) -> None:
        self.factor = factor
        self.origin = time.monotonic()

    def __call__(self) -> float:
        return (time.monotonic() - self.origin) * self.factor


async def wait_until(predicate: Callable[[], bool], timeout: float = 5.0) -> None:
    deadline = time.monotonic() + timeout
    while not predicate():
        if time.monotonic() > deadline:
            raise AssertionError("condition not reached in time")
        await asyncio.sleep(0.01)


class FakeWatchBackend:
    def __init__(self, name: str = "backend") -> None:
        self.name = name
        self.deliver: Callable[[list[Any]], None] | None = None
        self.calls: list[tuple[str, str]] = []
        self.fail_with: dict[str, BaseException] = {}
        self.alive = True
        self.closed = False
        self._closing = False
        self.close_thread: str | None = None
        self.entered: dict[str, threading.Event] = {}
        self._gates: dict[str, tuple[threading.Event, set[str] | None]] = {}
        self._lock = threading.Lock()
        self._active = 0
        self.max_active = 0

    def __repr__(self) -> str:
        return f"FakeWatchBackend({self.name})"

    # -- test controls --

    def emit(self, events: Sequence[Any]) -> None:
        assert self.deliver is not None, "backend not started"
        self.deliver(list(events))

    def gate(self, op: str, roots: set[str] | None = None) -> threading.Event:
        """Block ``op`` (for ``roots`` only, when given) until ``open_gate(op)``."""
        self._gates[op] = (threading.Event(), roots)
        self.entered[op] = threading.Event()
        return self.entered[op]

    def open_gate(self, op: str) -> None:
        gate = self._gates.pop(op, None)
        if gate is not None:
            gate[0].set()

    def ops(self, op: str) -> list[str]:
        return [root for kind, root in self.calls if kind == op]

    def _wait_gate(self, op: str, root: str) -> None:
        gate = self._gates.get(op)
        if gate is None or (gate[1] is not None and root not in gate[1]):
            return
        self.entered[op].set()
        while not gate[0].wait(0.01):
            if self._closing and op != "close":
                return

    def _enter(self, op: str, root: str) -> None:
        with self._lock:
            self.calls.append((op, root))
            self._active += 1
            self.max_active = max(self.max_active, self._active)

    def _leave(self) -> None:
        with self._lock:
            self._active -= 1

    # -- WatchBackend --

    def start(self, deliver: Callable[[list[Any]], None]) -> None:
        self.deliver = deliver

    def add_tree(self, root: str) -> int:
        self._enter("add_tree", root)
        try:
            self._wait_gate("add_tree", root)
            if self._closing:
                return 0
            error = self.fail_with.get(root)
            if error is not None:
                raise error
            return 1
        finally:
            self._leave()

    def remove(self, root: str) -> None:
        self._enter("remove", root)
        try:
            self._wait_gate("remove", root)
        finally:
            self._leave()

    def is_alive(self) -> bool:
        return self.alive and not self.closed

    def abort(self) -> None:
        self._closing = True

    def close(self) -> None:
        self._enter("close", "")
        try:
            self._closing = True
            self._wait_gate("close", "")
            self.closed = True
            self.close_thread = threading.current_thread().name
        finally:
            self._leave()


class BackendFactory:
    """``backend_factory`` for ``ChangeWatcher`` tests: builds ``FakeWatchBackend``s
    (``created``), or raises ``error`` while it is set."""

    def __init__(self) -> None:
        self.created: list[FakeWatchBackend] = []
        self.error: BaseException | None = None
        self.calls = 0
        self.configure: Callable[[FakeWatchBackend], None] | None = None

    def __call__(self) -> FakeWatchBackend:
        self.calls += 1
        if self.error is not None:
            raise self.error
        backend = FakeWatchBackend(f"B{len(self.created) + 1}")
        if self.configure is not None:
            self.configure(backend)
        self.created.append(backend)
        return backend

    @property
    def last(self) -> FakeWatchBackend:
        return self.created[-1]


@contextmanager
def capture_selects(db: Database) -> Iterator[list[tuple[str, object]]]:
    """Every SELECT the read engine runs, with its parameters."""
    statements: list[tuple[str, object]] = []

    def before(_conn: Any, _cursor: Any, statement: str, parameters: object, *_args: Any) -> None:
        if statement.lstrip().upper().startswith("SELECT"):
            statements.append((statement, parameters))

    event.listen(db.read_engine, "before_cursor_execute", before)
    try:
        yield statements
    finally:
        event.remove(db.read_engine, "before_cursor_execute", before)


@contextmanager
def capture_writes(db: Database) -> Iterator[list[str]]:
    """Every statement the write engine runs."""
    statements: list[str] = []

    def before(_conn: Any, _cursor: Any, statement: str, *_args: Any) -> None:
        statements.append(statement)

    event.listen(db.write_engine, "before_cursor_execute", before)
    try:
        yield statements
    finally:
        event.remove(db.write_engine, "before_cursor_execute", before)
