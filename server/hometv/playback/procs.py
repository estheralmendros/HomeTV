"""Subprocess plumbing shared by the playback modules (no shell, ever).

- ``IDLE_PREFIX``: ``nice -n 10 ionice -c 3`` for whole-file readers. ``spawn`` drops the parts
  whose binary is missing (``ionice`` does not exist on macOS), so argv built by the modules
  (and asserted by tests) always carries the full prefix.
- ``read_rchar(pid)``: ``/proc/{pid}/io`` ``rchar`` or ``None`` when unreadable.
- ``kill_sequence(proc)``: SIGCONT, SIGTERM, SIGKILL after a grace period (§7 HLS step 7).
"""

import asyncio
import contextlib
import functools
import shutil
import signal
from collections.abc import Sequence
from typing import Protocol

IDLE_PREFIX = ("nice", "-n", "10", "ionice", "-c", "3")


class Proc(Protocol):
    """The subset of ``asyncio.subprocess.Process`` the playback code uses."""

    @property
    def pid(self) -> int: ...

    @property
    def returncode(self) -> int | None: ...

    @property
    def stdout(self) -> asyncio.StreamReader | None: ...

    @property
    def stderr(self) -> asyncio.StreamReader | None: ...

    async def wait(self) -> int: ...

    def send_signal(self, sig: int) -> None: ...

    def kill(self) -> None: ...


@functools.cache
def _available(binary: str) -> bool:
    return shutil.which(binary) is not None


def effective_argv(argv: Sequence[str]) -> list[str]:
    """``argv`` with the idle-priority prefix reduced to the tools that exist on this host."""
    args = list(argv)
    if tuple(args[: len(IDLE_PREFIX)]) != IDLE_PREFIX:
        return args
    rest = args[len(IDLE_PREFIX) :]
    prefix: list[str] = []
    if _available("nice"):
        prefix += ["nice", "-n", "10"]
    if _available("ionice"):
        prefix += ["ionice", "-c", "3"]
    return prefix + rest


async def spawn(
    argv: Sequence[str],
    *,
    stdout: int | None = asyncio.subprocess.PIPE,
    stderr: int | None = asyncio.subprocess.PIPE,
) -> Proc:
    return await asyncio.create_subprocess_exec(
        *effective_argv(argv),
        stdin=asyncio.subprocess.DEVNULL,
        stdout=stdout,
        stderr=stderr,
        limit=1024 * 1024,
    )


def read_rchar(pid: int) -> int | None:
    try:
        with open(f"/proc/{pid}/io", encoding="ascii") as fh:
            for line in fh:
                if line.startswith("rchar:"):
                    return int(line.split()[1])
    except (OSError, ValueError, IndexError):
        return None
    return None


def signal_proc(proc: Proc, sig: int) -> bool:
    if proc.returncode is not None:
        return False
    try:
        proc.send_signal(sig)
    except (ProcessLookupError, OSError):
        return False
    return True


async def kill_sequence(proc: Proc, grace: float = 3.0, reap_timeout: float = 2.0) -> None:
    """SIGCONT (a stopped process cannot act on SIGTERM), SIGTERM, SIGKILL after ``grace``."""
    if proc.returncode is not None:
        return
    signal_proc(proc, signal.SIGCONT)
    signal_proc(proc, signal.SIGTERM)
    try:
        await asyncio.wait_for(proc.wait(), grace)
        return
    except TimeoutError:
        pass
    with contextlib.suppress(ProcessLookupError, OSError):
        proc.kill()
    with contextlib.suppress(TimeoutError):
        await asyncio.wait_for(proc.wait(), reap_timeout)


async def drain(stream: asyncio.StreamReader | None, keep: int = 4096) -> bytes:
    """Read ``stream`` to EOF, keeping only the last ``keep`` bytes."""
    if stream is None:
        return b""
    tail = b""
    while True:
        chunk = await stream.read(65536)
        if not chunk:
            return tail
        tail = (tail + chunk)[-keep:]


def last_line(data: bytes) -> str:
    lines = [ln.strip() for ln in data.decode("utf-8", "replace").splitlines() if ln.strip()]
    return lines[-1] if lines else ""
