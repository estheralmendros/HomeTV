"""Scoped scan duration: a one-folder ``watch`` scan does not depend on library
size. 2,000 folders x 3 files already scanned; a scoped scan of one folder (stub probe) must
finish in < 1 s.

Not part of the default run: ``cd server && uv run pytest -m perf -s``.
"""

import time
from collections.abc import Iterator
from pathlib import Path

import pytest

from hometv.changes.scope import ScanScope
from hometv.db.engine import Database, run_migrations
from hometv.scanner import Scanner
from tests.service.conftest import StubProbe

pytestmark = pytest.mark.perf

FOLDERS, FILES = 2000, 3
BUDGET_SECONDS = 1.0


@pytest.fixture
def perf_db(tmp_path: Path) -> Iterator[Database]:
    database = Database(tmp_path / "perf.db")
    run_migrations(database)
    yield database
    database.dispose()


async def test_one_folder_scoped_scan_is_fast(perf_db, make_settings, tmp_path: Path):
    root = tmp_path / "media" / "películas"
    for i in range(FOLDERS):
        folder = root / f"Film {i:04d} (2000)"
        folder.mkdir(parents=True)
        (folder / f"Film {i:04d} (2000).mkv").write_bytes(b"x")
        (folder / f"Film {i:04d} (2000).es.srt").write_bytes(b"s")
        (folder / f"Film {i:04d} (2000).en.srt").write_bytes(b"s")
    settings = make_settings(libraries={"films": [str(root)]})
    await Scanner(perf_db, settings, probe_fn=StubProbe()).run()

    target = root / "Film 1000 (2000)"
    (target / "Film 1000 (2000) 4K.mkv").write_bytes(b"y")
    probe = StubProbe()
    started = time.perf_counter()
    report = await Scanner(
        perf_db, settings, probe_fn=probe, scope=[ScanScope(str(root), target.name, True)]
    ).run()
    elapsed = time.perf_counter() - started
    print(f"\nscoped scan of 1 folder among {FOLDERS}: {elapsed * 1000:.1f} ms")
    assert report.counts["new"] == 1
    assert probe.calls == ["Film 1000 (2000) 4K.mkv"]
    assert elapsed < BUDGET_SECONDS
