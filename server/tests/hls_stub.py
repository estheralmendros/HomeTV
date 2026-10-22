"""Argv-driven stub ffmpeg for API tests: derives the segments of a run from ``-ss``,
``-segment_times`` (absolute mode: boundaries + T0) and ``-segment_start_number``, and writes
synthetic ``raw_k.mp4`` files + ``run.csv`` rows like the real segment muxer."""

import asyncio
from pathlib import Path

from hometv.playback.segments import TS_OFFSET_SECONDS as T0
from tests import fmp4_builder as fb
from tests.stubs import FakeProc, Spawner


class PlanFfmpeg:
    def __init__(self, duration: float, interval: float = 0.01) -> None:
        self.duration = duration
        self.interval = interval
        self.spawner = Spawner(self.scenario)

    @property
    def count(self) -> int:
        return self.spawner.count

    async def scenario(self, proc: FakeProc) -> None:
        argv = proc.argv
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
