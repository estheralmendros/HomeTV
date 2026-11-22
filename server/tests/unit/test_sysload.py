"""``sysload``: load average parsing and the injectable procfs read."""

from pathlib import Path

import pytest

from hometv.api.schemas import CpuLoad
from hometv.sysload import parse_loadavg, read_cpu_load


@pytest.mark.parametrize(
    ("text", "cores", "expected"),
    [
        ("0.52 0.58 0.59 1/389 12345\n", 4, CpuLoad(load_1m=0.52, load_5m=0.58, cores=4)),
        ("3.00 2.50 1.00", 2, CpuLoad(load_1m=3.0, load_5m=2.5, cores=2)),
        ("0.00 0.00 0.00 1/1 1", 1, CpuLoad(load_1m=0.0, load_5m=0.0, cores=1)),
        ("", 4, None),
        ("0.5\n", 4, None),
        ("abc def ghi", 4, None),
        ("0.5 nope 0.1", 4, None),
        ("nan 0.5 0.1", 4, None),
        ("1.0 2.0 3.0", 0, None),
    ],
)
def test_parse_loadavg(text, cores, expected):
    assert parse_loadavg(text, cores) == expected


def test_read_cpu_load_reads_the_file(tmp_path: Path):
    path = tmp_path / "loadavg"
    path.write_text("1.25 0.75 0.5 2/100 999\n")
    assert read_cpu_load(path, lambda: 8) == CpuLoad(load_1m=1.25, load_5m=0.75, cores=8)


def test_read_cpu_load_is_none_without_file_or_cores(tmp_path: Path):
    assert read_cpu_load(tmp_path / "missing", lambda: 4) is None
    path = tmp_path / "loadavg"
    path.write_text("1.0 1.0 1.0\n")
    assert read_cpu_load(path, lambda: None) is None
    path.write_text("garbage")
    assert read_cpu_load(path, lambda: 4) is None
    assert read_cpu_load(tmp_path, lambda: 4) is None  # a directory: OSError
