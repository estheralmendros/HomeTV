"""Real inotify: Linux only; needs no ffmpeg (marker ``inotify`` keeps it
out of the ffmpeg auto-skip).

Run: ``uv run pytest -m integration tests/integration/test_inotify_backend.py``.
"""

import hashlib
import os
import sys
import threading
from pathlib import Path

import pytest

from hometv.changes.backend import WatchEvent
from tests.stubs import wait_until

pytestmark = [
    pytest.mark.integration,
    pytest.mark.inotify,
    pytest.mark.skipif(sys.platform != "linux", reason="inotify requires Linux"),
]


class Collector:
    def __init__(self) -> None:
        self.events: list[WatchEvent] = []
        self.lock = threading.Lock()

    def __call__(self, batch: list[WatchEvent]) -> None:
        with self.lock:
            self.events.extend(batch)

    def has(self, kind: str, path: Path | str) -> bool:
        with self.lock:
            return any(e.kind == kind and e.path == str(path) for e in self.events)

    def kinds(self) -> list[str]:
        with self.lock:
            return [e.kind for e in self.events]


def _tree_digest(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        st = path.lstat()
        digest.update(f"{path}:{st.st_mode}:{st.st_size}:{st.st_mtime_ns}".encode())
        if path.is_file():
            digest.update(path.read_bytes())
    return digest.hexdigest()


@pytest.fixture
def backend():
    from hometv.changes.inotify import InotifyBackend

    instance = InotifyBackend(read_sleep=0)
    yield instance
    instance.close()


async def test_file_events_recursion_and_root_gone(backend, tmp_path: Path):
    root = tmp_path / "series"
    (root / "Show").mkdir(parents=True)
    events = Collector()
    backend.start(events)
    assert backend.add_tree(str(root)) == 2

    video = root / "Show" / "e1.mkv"
    video.write_bytes(b"12345")
    await wait_until(lambda: events.has("closed_write", video))
    sizes = [e.size for e in events.events if e.path == str(video) and e.kind == "closed_write"]
    assert 5 in sizes

    moved = root / "e1.mkv"
    os.rename(video, moved)
    await wait_until(lambda: events.has("moved_from", video) and events.has("moved_to", moved))
    moved.unlink()
    await wait_until(lambda: events.has("deleted", moved))

    new_dir = root / "New Show" / "T1"
    new_dir.mkdir(parents=True)
    await wait_until(lambda: events.has("created", root / "New Show"))
    inner = new_dir / "x.mkv"
    await wait_until(lambda: str(new_dir) in backend._path_wds)
    inner.write_bytes(b"x")
    await wait_until(lambda: events.has("closed_write", inner))

    inner.unlink()
    for directory in (new_dir, root / "New Show", root / "Show", root):
        directory.rmdir()
    await wait_until(lambda: events.has("root_gone", root))
    assert backend.is_alive()


async def test_symlinked_root_reports_paths_under_the_link(backend, tmp_path: Path):
    real = tmp_path / "real"
    (real / "A").mkdir(parents=True)
    link = tmp_path / "link"
    link.symlink_to(real, target_is_directory=True)
    events = Collector()
    backend.start(events)
    backend.add_tree(str(link))
    (real / "A" / "Dune.mkv").write_bytes(b"x")
    await wait_until(lambda: events.has("closed_write", link / "A" / "Dune.mkv"))
    assert not any(e.path.startswith(str(real)) for e in events.events)


async def test_watching_never_writes_to_the_tree(backend, tmp_path: Path):
    root = tmp_path / "ro"
    (root / "A" / "B").mkdir(parents=True)
    (root / "A" / "Dune.mkv").write_bytes(b"dune")
    (root / "A" / "B" / "x.srt").write_bytes(b"sub")
    before = _tree_digest(root)
    backend.start(Collector())
    backend.add_tree(str(root))
    backend.remove(str(root))
    backend.close()
    assert _tree_digest(root) == before


async def test_renamed_directory_keeps_reporting_under_the_new_name(backend, tmp_path: Path):
    root = tmp_path / "series"
    (root / "Old Show" / "T1").mkdir(parents=True)
    events = Collector()
    backend.start(events)
    assert backend.add_tree(str(root)) == 3

    new = root / "New Show"
    os.rename(root / "Old Show", new)
    await wait_until(
        lambda: events.has("moved_from", root / "Old Show") and events.has("moved_to", new)
    )
    # Drops before arms: the kept wds now map to the new name (the old one is gone).
    await wait_until(lambda: str(new / "T1") in backend._path_wds)
    with backend._map_lock:
        watched = set(backend._path_wds)
    assert str(new) in watched
    assert not any("Old Show" in path for path in watched)

    video = new / "T1" / "e1.mkv"
    video.write_bytes(b"123")
    await wait_until(lambda: events.has("closed_write", video))
    assert not any("Old Show" in e.path for e in events.events if e.kind == "closed_write")
    assert backend.is_alive()
