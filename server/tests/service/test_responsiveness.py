"""API responsiveness while background work runs (ACs "Scanner" responsiveness and Rev 4
"Background keyframe indexing ... GET /api/films stays responsive meanwhile").

Requests go through the in-process ASGI app, so they share the event loop and the thread pool
with the scanner / indexer, which is where a blocking call or a long write transaction would
show up. Latencies are measured concurrently with the background work, and each test asserts
the work was still in progress when the measurement window closed.
"""

import asyncio
import contextlib
import os
import statistics
import time
from collections.abc import Iterator

import pytest

from hometv import profiles as p
from hometv.db import models as m
from tests import factories as f
from tests.service.conftest import StubProbe
from tests.stubs import FakeProc, Spawner, wait_until

P95_LIMIT_MS = 200.0


def p95(samples: list[float]) -> float:
    return statistics.quantiles(samples, n=20, method="inclusive")[-1]


class SlowProbe(StubProbe):
    """Stub probe taking ``delay`` seconds per file (awaits; holds no lock)."""

    def __init__(self, delay: float) -> None:
        super().__init__()
        self.delay = delay

    async def __call__(self, path: str):
        await asyncio.sleep(self.delay)
        return await super().__call__(path)


def instrument_write_sessions(db) -> list[float]:
    """Record how long every ``db.write_session()`` holds the write lock (seconds)."""
    holds: list[float] = []
    original = db.write_session

    @contextlib.contextmanager
    def timed() -> Iterator:
        with original() as session:
            start = time.perf_counter()
            try:
                yield session
            finally:
                holds.append(time.perf_counter() - start)

    db.write_session = timed
    return holds


async def measure(client, requests, *, seconds: float, workers: int = 2) -> dict[str, list]:
    """Run ``requests`` (name -> async callable returning a status) round-robin from
    ``workers`` concurrent loops for ``seconds``; returns latencies in ms per name."""
    samples: dict[str, list[float]] = {name: [] for name in requests}
    deadline = time.perf_counter() + seconds
    names = list(requests)

    async def loop(offset: int) -> None:
        i = offset
        while time.perf_counter() < deadline:
            name = names[i % len(names)]
            start = time.perf_counter()
            status = await requests[name]()
            samples[name].append((time.perf_counter() - start) * 1000)
            assert status == 200, (name, status)
            i += 1

    await asyncio.gather(*(loop(k) for k in range(workers)))
    return samples


@pytest.fixture
def settings(media_settings):
    return media_settings(scan={"on_startup": False, "interval_hours": 0, "probe_concurrency": 2})


def viewer(app, db) -> dict[str, str]:
    profile = p.create_profile(db, name="Kids", avatar_id="avatar-01", is_admin=False, pin=None)
    token = app.state.tokens.issue_profile_token(profile.id).token
    return {"Authorization": f"Bearer {token}"}


async def test_api_stays_responsive_during_a_slow_scan_of_500_files(app, client, db, media):
    scheduler = app.state.scheduler
    # A first (fast) scan creates a real film to report progress against.
    media.add("Dune (2021)/Dune (2021).mkv")
    scheduler.probe_fn = StubProbe()
    await scheduler.trigger("manual")
    await scheduler.wait_idle()
    with db.read_session() as s:
        film = s.query(m.Film).one()
        file_id = s.query(m.MediaFile).one().id
    headers = viewer(app, db)

    for i in range(500):
        media.add(f"Film {i:03d} ({1950 + i % 70})/Film {i:03d} ({1950 + i % 70}).mkv")
    probe = SlowProbe(delay=1.0)
    scheduler.probe_fn = probe
    holds = instrument_write_sessions(db)

    async def films() -> int:
        return (await client.get("/api/films", headers=headers)).status_code

    position = iter(range(100, 10_000))

    async def progress() -> int:
        body = {
            "item_type": "film",
            "item_id": film.id,
            "media_file_id": file_id,
            "position_seconds": float(next(position)),
            "duration_seconds": 5400.0,
        }
        return (await client.put("/api/progress", json=body, headers=headers)).status_code

    await scheduler.trigger("manual")
    samples = await measure(client, {"films": films, "progress": progress}, seconds=4.0)

    status = scheduler.status()
    assert status.state == "running"  # the whole window was during the scan
    assert probe.calls, "the scan reached the (slow) probe phase inside the window"
    # (``probe.lock_held_during_probe`` is meaningless here: the concurrent PUTs take the
    # process-wide write lock in worker threads. The AC's hold-time check below covers it: a
    # transaction held across a 1 s probe would show up as a hold >= 1 s.)
    for name, values in samples.items():
        assert len(values) >= 20, name
        assert p95(values) < P95_LIMIT_MS, (name, p95(values), max(values))
    assert holds, "the scan wrote to the database inside the window"
    assert max(holds) < 1.0, max(holds)


async def test_films_stay_responsive_during_background_keyframe_indexing(app, client, db):
    keyframes = app.state.keyframes
    ids = []
    with db.write_session() as s:
        for i in range(4):
            film = f.add_film(s, f"Film {i}", files=0)
            row = f.add_file(
                s, film_id=film.id, name=f"film{i}.ts", probe=f.probe_json(container="ts")
            )
            ids.append(row.id)
    headers = viewer(app, db)

    async def slow_ffprobe(proc: FakeProc) -> None:
        # Feeds packet rows in small chunks, like a long full-file read.
        for t in range(0, 300, 2):
            proc.stdout.feed_data(f"{t}.000000,{t}.000000,K__\n".encode())
            await asyncio.sleep(0.01)
        proc.finish(0)

    spawner = Spawner(slow_ffprobe)
    keyframes.spawn = spawner
    keyframes.read_rchar = lambda pid: None
    keyframes.container_reader = lambda info: None
    try:
        keyframes.enqueue(ids)
        await wait_until(lambda: spawner.active == 1, timeout=5)

        async def films() -> int:
            return (await client.get("/api/films", headers=headers)).status_code

        samples = await measure(client, {"films": films}, seconds=1.0)
        assert spawner.active == 1 or spawner.count > 1  # indexing still going on
        assert spawner.max_active == 1  # one file at a time
        values = samples["films"]
        assert len(values) >= 20
        assert p95(values) < P95_LIMIT_MS, (p95(values), max(values))
    finally:
        await keyframes.stop()
        await spawner.close()


def test_p95_helper():
    assert p95([float(i) for i in range(1, 101)]) == pytest.approx(95.05)
    assert os.environ.get("HOMETV_DATA_DIR") is None  # env isolation still applies here
