"""Fake subprocesses for service tests (keyframe ffprobe, HLS ffmpeg, subtitle ffmpeg).

``FakeProc`` implements the ``procs.Proc`` protocol: ``stdout``/``stderr`` are real
``asyncio.StreamReader``s the scenario feeds; signals are recorded; SIGSTOP/SIGCONT toggle
``stopped``; SIGTERM/SIGKILL end it (unless ``ignore_kill``).
"""

import asyncio
import itertools
import signal
import time
from collections.abc import Awaitable, Callable, Sequence
from typing import Any

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
