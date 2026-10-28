"""Local TMDB artwork cache (PRD §5 Artwork cache) + the API Image object.

Files live at ``{data_dir}/artwork/{size}/{file_name}`` and are written atomically
(``.tmp`` + rename). Posters/backdrops are fetched eagerly by enrichment (``prefetch``);
stills and profiles lazily on first request (``ensure``). Concurrent misses for the same file
share one download.
"""

import asyncio
import logging
import os
import re
from pathlib import Path
from typing import Literal

from hometv.metadata.tmdb import TmdbClient, TmdbError

logger = logging.getLogger("hometv.artwork")

ARTWORK_SIZES = frozenset({"w185", "w300", "w342", "w780", "w1280", "h632"})
FILE_NAME_RE = re.compile(r"^[A-Za-z0-9_-]+\.(jpg|png|svg)$")
ImageKind = Literal["poster", "backdrop", "still", "profile"]
IMAGE_SIZES: dict[str, tuple[str, str]] = {
    "poster": ("w342", "w780"),
    "backdrop": ("w780", "w1280"),
    "still": ("w300", "w780"),
    "profile": ("w185", "h632"),  # h632: the actor screen photo, downloaded on demand
}
CONTENT_TYPES = {".jpg": "image/jpeg", ".png": "image/png", ".svg": "image/svg+xml"}


class ArtworkError(Exception):
    code = "artwork_error"


class InvalidArtworkSize(ArtworkError):
    code = "invalid_size"


class InvalidArtworkName(ArtworkError):
    code = "invalid_file_name"


class ArtworkNotFound(ArtworkError):
    code = "artwork_not_found"


def tmdb_file_name(path: str | None) -> str | None:
    """TMDB ``/abc.jpg`` -> ``abc.jpg`` (``None`` when absent or not a safe file name)."""
    if not path:
        return None
    name = path.lstrip("/")
    return name if FILE_NAME_RE.match(name) else None


def image(kind: ImageKind, file_name: str | None) -> dict[str, str] | None:
    """API Image object ``{"small", "large"}`` for a stored TMDB file name (or ``None``)."""
    if not file_name:
        return None
    small, large = IMAGE_SIZES[kind]
    return {
        "small": f"/api/artwork/{small}/{file_name}",
        "large": f"/api/artwork/{large}/{file_name}",
    }


def content_type(file_name: str) -> str:
    return CONTENT_TYPES.get(os.path.splitext(file_name)[1].lower(), "application/octet-stream")


def _write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    tmp.write_bytes(data)
    os.replace(tmp, path)


class ArtworkCache:
    def __init__(self, root: Path, client: TmdbClient | None) -> None:
        self.root = root
        self.client = client
        self._pending: dict[tuple[str, str], asyncio.Task[Path]] = {}

    @staticmethod
    def validate(size: str, file_name: str) -> None:
        if size not in ARTWORK_SIZES:
            raise InvalidArtworkSize(f"invalid artwork size {size!r}")
        if not FILE_NAME_RE.match(file_name):
            raise InvalidArtworkName(f"invalid artwork file name {file_name!r}")

    def path_for(self, size: str, file_name: str) -> Path:
        self.validate(size, file_name)
        return self.root / size / file_name

    async def ensure(self, size: str, file_name: str) -> Path:
        """Return the cached file, downloading it on a miss.

        Raises ``InvalidArtworkSize``/``InvalidArtworkName``, ``ArtworkNotFound`` (TMDB 404 or no
        TMDB key) and ``TmdbUnavailable``.
        """
        path = self.path_for(size, file_name)
        if await asyncio.to_thread(path.is_file):
            return path
        key = (size, file_name)
        task = self._pending.get(key)
        if task is None:
            task = asyncio.ensure_future(self._download(size, file_name, path))
            self._pending[key] = task
            task.add_done_callback(lambda _t: self._pending.pop(key, None))
        return await asyncio.shield(task)

    async def _download(self, size: str, file_name: str, path: Path) -> Path:
        if self.client is None:
            raise ArtworkNotFound("artwork not cached and TMDB is not configured")
        data = await self.client.get_image(size, file_name)
        if data is None:
            raise ArtworkNotFound(f"TMDB has no {size}/{file_name}")
        await asyncio.to_thread(_write_atomic, path, data)
        return path

    async def prefetch(self, poster: str | None = None, backdrop: str | None = None) -> None:
        """Eager download of poster (w342, w780) and backdrop (w780, w1280); failures logged."""
        jobs = [(s, poster) for s in IMAGE_SIZES["poster"] if poster]
        jobs += [(s, backdrop) for s in IMAGE_SIZES["backdrop"] if backdrop]
        results = await asyncio.gather(
            *(self.ensure(size, name) for size, name in jobs if name), return_exceptions=True
        )
        for (size, name), result in zip(jobs, results, strict=True):
            if isinstance(result, ArtworkError | TmdbError):
                logger.warning("artwork_prefetch_failed: %s/%s: %s", size, name, result)
            elif isinstance(result, BaseException):
                raise result
