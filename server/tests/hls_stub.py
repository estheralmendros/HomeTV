"""Argv-driven stub ffmpeg for API tests: derives the segments of a run from ``-ss``,
``-segment_times`` (absolute mode: boundaries + T0) and ``-segment_start_number``, and writes
synthetic ``raw_k.mp4`` files + ``run.csv`` rows like the real segment muxer. The encoder args of a
transcode argv are ignored; with ``progress_rate`` set, a run that has ``-progress <file>`` also
writes ``progress=continue`` blocks whose ``out_time_us`` advances ``progress_rate`` seconds per
second of ``clock`` (stalled while SIGSTOPped)."""

import asyncio
import time
from collections.abc import Callable
from pathlib import Path

from hometv.playback.segments import TS_OFFSET_SECONDS as T0
from tests import fmp4_builder as fb
from tests.stubs import FakeProc, Spawner

PROGRESS_BLOCK = "frame=1\nout_time_us={us}\nprogress=continue\n"


class PlanFfmpeg:
    def __init__(
        self,
        duration: float,
        interval: float = 0.01,
        *,
        progress_rate: float | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.duration = duration
        self.interval = interval
        self.progress_rate = progress_rate
        self.clock = clock
        self._writers: set[asyncio.Future[None]] = set()
        self.spawner = Spawner(self.scenario)

    @property
    def count(self) -> int:
        return self.spawner.count

    async def write_progress(self, proc: FakeProc, path: Path) -> None:
        assert self.progress_rate is not None
        out_time = 0.0
        last = self.clock()
        while proc.running:
            await asyncio.sleep(0.02)
            await proc.checkpoint()
            now = self.clock()
            out_time += (now - last) * self.progress_rate
            last = now
            with open(path, "a") as fh:
                fh.write(PROGRESS_BLOCK.format(us=int(out_time * 1_000_000)))

    async def scenario(self, proc: FakeProc) -> None:
        argv = proc.argv
        if self.progress_rate is not None and "-progress" in argv:
            writer = asyncio.ensure_future(
                self.write_progress(proc, Path(argv[argv.index("-progress") + 1]))
            )
            self._writers.add(writer)
            writer.add_done_callback(self._writers.discard)
        k = int(argv[argv.index("-segment_start_number") + 1])
        start = float(argv[argv.index("-ss") + 1]) - 0.001 if "-ss" in argv else 0.0
        times = argv[argv.index("-segment_times") + 1]
        cuts = [] if times == "999999" else [float(t) - T0 for t in times.split(",")]
        bounds = [start, *cuts, self.duration]
        csv = Path(argv[argv.index("-segment_list") + 1])
        pattern = argv[-1]
        for j in range(len(bounds) - 1):
            await asyncio.sleep(self.interval)
            await proc.checkpoint()
            if not proc.running:
                return
            Path(pattern % (k + j)).write_bytes(fb.raw_segment(bounds[j] + T0))
            with open(csv, "a") as fh:
                fh.write(f"raw_{k + j}.mp4,{bounds[j] + T0:.6f},{bounds[j + 1] + T0:.6f}\n")
        proc.finish(0)
