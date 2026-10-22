"""Service-test fixtures: a migrated Database without the app, media trees and a stub probe.

- ``mdb``: migrated ``Database`` in ``tmp_path`` (no app, no lifespan).
- ``media``: ``MediaTree`` rooted at ``tmp_path/media`` with ``films`` and ``shows`` roots;
  ``media.add(rel, content=b"")`` creates a file (parents included).
- ``stub_probe``: ``StubProbe`` (async ``probe_fn``) returning a canned ``ProbeResult`` keyed by
  file name; ``stub_probe.fail("name.mkv")`` makes that file raise ``ProbeError``.
"""

import os
from collections.abc import Callable, Iterator
from pathlib import Path
from typing import Any

import pytest

from hometv.config import Settings
from hometv.db.engine import Database, run_migrations, write_lock_held
from hometv.probe import AudioStream, ProbeError, ProbeResult, VideoStream


@pytest.fixture
def mdb(tmp_path: Path) -> Iterator[Database]:
    database = Database(tmp_path / "hometv.db")
    run_migrations(database)
    yield database
    database.dispose()


class MediaTree:
    def __init__(self, base: Path) -> None:
        self.base = base
        self.films = base / "films"
        self.shows = base / "shows"
        self.films.mkdir(parents=True)
        self.shows.mkdir(parents=True)

    def add(self, rel: str, content: bytes = b"x", root: Path | None = None) -> Path:
        path = (root or self.films) / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        return path

    def show(self, rel: str, content: bytes = b"x") -> Path:
        return self.add(rel, content, root=self.shows)

    def touch(self, path: Path, content: bytes = b"changed!") -> None:
        path.write_bytes(content)
        st = path.stat()
        os.utime(path, ns=(st.st_atime_ns, st.st_mtime_ns + 5_000_000_000))


@pytest.fixture
def media(tmp_path: Path) -> MediaTree:
    return MediaTree(tmp_path / "media")


def make_probe_result(container: str = "mkv", duration: float | None = 5400.0) -> ProbeResult:
    return ProbeResult(
        container=container,
        duration_seconds=duration,
        video=VideoStream(index=0, codec="h264", width=1920, height=1080),
        audio=[AudioStream(index=1, codec="aac", channels=2, default=True)],
    )


class StubProbe:
    def __init__(self) -> None:
        self.calls: list[str] = []
        self.failing: set[str] = set()
        self.results: dict[str, ProbeResult] = {}
        self.lock_held_during_probe = False

    def fail(self, *names: str) -> None:
        self.failing.update(names)

    def heal(self, *names: str) -> None:
        self.failing.difference_update(names)

    async def __call__(self, path: str) -> ProbeResult:
        name = os.path.basename(path)
        self.calls.append(name)
        if write_lock_held():
            self.lock_held_during_probe = True
        if name in self.failing:
            raise ProbeError("Invalid data found when processing input")
        if name in self.results:
            return self.results[name]
        container = name.rsplit(".", 1)[-1].lower()
        return make_probe_result({"m2ts": "ts"}.get(container, container))


@pytest.fixture
def stub_probe() -> StubProbe:
    return StubProbe()


@pytest.fixture
def media_settings(make_settings: Callable[..., Settings], media: MediaTree) -> Callable[..., Any]:
    def factory(**overrides: Any) -> Settings:
        base: dict[str, Any] = {
            "libraries": {"films": [str(media.films)], "shows": [str(media.shows)]},
        }
        base.update(overrides)
        return make_settings(**base)

    return factory
