"""Direct-play byte-range streaming.

``parse_range`` is pure. ``file_chunks`` reads the file read-only in 1 MiB chunks in a worker
thread; Starlette's ``StreamingResponse`` stops iterating when the client disconnects.
"""

import asyncio
import re
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import BinaryIO

CHUNK_SIZE = 1024 * 1024
CONTENT_TYPES = {
    "mkv": "video/x-matroska",
    "mp4": "video/mp4",
    "webm": "video/webm",
    "avi": "video/x-msvideo",
    "mov": "video/quicktime",
    "ts": "video/mp2t",
    "m2ts": "video/mp2t",
}
_RANGE_RE = re.compile(r"^\s*(\d*)\s*-\s*(\d*)\s*$")


def content_type(container: str | None) -> str:
    return CONTENT_TYPES.get((container or "").lower(), "application/octet-stream")


class RangeNotSatisfiable(Exception):
    pass


@dataclass(frozen=True)
class ByteRange:
    start: int
    end: int  # inclusive

    @property
    def length(self) -> int:
        return self.end - self.start + 1

    def content_range(self, size: int) -> str:
        return f"bytes {self.start}-{self.end}/{size}"


def parse_range(header: str | None, size: int) -> ByteRange | None:
    """``None`` -> serve the whole file (no/unsupported header). Multiple ranges -> the first
    only. Raises ``RangeNotSatisfiable`` (416)."""
    if not header:
        return None
    unit, _, spec = header.partition("=")
    if unit.strip().lower() != "bytes" or not spec:
        return None
    first = spec.split(",")[0]
    match = _RANGE_RE.match(first)
    if match is None:
        return None
    start_s, end_s = match.groups()
    if not start_s and not end_s:
        return None
    if not start_s:  # suffix: last n bytes
        n = int(end_s)
        if n == 0 or size == 0:
            raise RangeNotSatisfiable
        return ByteRange(max(0, size - n), size - 1)
    start = int(start_s)
    end = int(end_s) if end_s else size - 1
    if start >= size or end < start:
        raise RangeNotSatisfiable
    return ByteRange(start, min(end, size - 1))


async def file_chunks(path: str, start: int, length: int) -> AsyncIterator[bytes]:
    fh = await asyncio.to_thread(open, path, "rb")
    try:
        await asyncio.to_thread(fh.seek, start)
    except BaseException:
        await asyncio.to_thread(fh.close)
        raise
    async for chunk in open_file_chunks(fh, length):
        yield chunk


async def open_file_chunks(fh: BinaryIO, length: int) -> AsyncIterator[bytes]:
    """Stream ``length`` bytes from an already open file (from its position), then close it."""
    try:
        remaining = length
        while remaining > 0:
            chunk = await asyncio.to_thread(fh.read, min(CHUNK_SIZE, remaining))
            if not chunk:
                break
            remaining -= len(chunk)
            yield chunk
    finally:
        await asyncio.to_thread(fh.close)
