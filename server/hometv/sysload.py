"""CPU load for the admin sessions view: the load average from procfs (Linux only).

``parse_loadavg`` is pure; ``read_cpu_load`` is the seam the lifespan binds to
``app.state.cpu_load`` (tests swap it). One tiny procfs read per request, done inline.
"""

import math
import os
from collections.abc import Callable
from pathlib import Path

from hometv.api.schemas import CpuLoad

LOADAVG_PATH = Path("/proc/loadavg")


def parse_loadavg(text: str, cores: int) -> CpuLoad | None:
    """``"0.52 0.58 0.59 1/389 12345"`` -> 1 and 5 minute loads; ``None`` when unparseable."""
    fields = text.split()
    if len(fields) < 3 or cores < 1:
        return None
    try:
        load_1m, load_5m = float(fields[0]), float(fields[1])
    except ValueError:
        return None
    if not (math.isfinite(load_1m) and math.isfinite(load_5m)):
        return None
    return CpuLoad(load_1m=load_1m, load_5m=load_5m, cores=cores)


def read_cpu_load(
    path: Path = LOADAVG_PATH, cpu_count: Callable[[], int | None] = os.cpu_count
) -> CpuLoad | None:
    """The current load, or ``None`` off Linux / when the file or the core count is unavailable."""
    cores = cpu_count()
    if cores is None:
        return None
    try:
        text = path.read_text()
    except OSError:
        return None
    return parse_loadavg(text, cores)
