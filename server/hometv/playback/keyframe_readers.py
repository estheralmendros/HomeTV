"""Pure keyframe-index readers (PRD §7 Keyframe index, sources 1-2) + ffprobe row parsing.

Every reader works over a ``read(offset, size) -> bytes`` callable, reads at most
``MAX_READ_BYTES`` in total, and returns presentation times in seconds on the container's
own timeline (the caller subtracts ``probe.start_seconds``). ``IndexUnavailable`` means "use
the next source" (no index in the file, an unsupported layout, or a read budget overrun).
"""

import struct
from collections.abc import Callable, Iterable

Read = Callable[[int, int], bytes]
MAX_READ_BYTES = 64 * 1024 * 1024
_MAX_ELEMENT_BYTES = 32 * 1024 * 1024


class IndexUnavailable(Exception):
    """The container index cannot be used; fall back to the next source."""


class BudgetRead:
    """Wraps ``read`` and enforces the total read budget."""

    def __init__(self, read: Read, size: int, budget: int = MAX_READ_BYTES) -> None:
        self._read = read
        self.size = size
        self.budget = budget
        self.total = 0

    def __call__(self, offset: int, size: int) -> bytes:
        size = max(0, min(size, self.size - offset))
        if size == 0:
            return b""
        self.total += size
        if self.total > self.budget:
            raise IndexUnavailable("read budget exceeded")
        return self._read(offset, size)


# --- Matroska / WebM -------------------------------------------------------------------------

EBML_ID = 0x1A45DFA3
SEGMENT = 0x18538067
SEEK_HEAD = 0x114D9B74
SEEK = 0x4DBB
SEEK_ID = 0x53AB
SEEK_POSITION = 0x53AC
INFO = 0x1549A966
TIMESTAMP_SCALE = 0x2AD7B1
TRACKS = 0x1654AE6B
TRACK_ENTRY = 0xAE
TRACK_NUMBER = 0xD7
TRACK_TYPE = 0x83
CUES = 0x1C53BB6B
CUE_POINT = 0xBB
CUE_TIME = 0xB3
CUE_TRACK_POSITIONS = 0xB7
CUE_TRACK = 0xF7
CLUSTER = 0x1F43B675
UNKNOWN_SIZE = -1


def _read_vint(data: bytes, pos: int, keep_marker: bool) -> tuple[int, int]:
    """Return ``(value, length)``; ``value`` is ``UNKNOWN_SIZE`` for an all-ones size."""
    if pos >= len(data):
        raise IndexUnavailable("truncated EBML")
    first = data[pos]
    length = 1
    mask = 0x80
    while length <= 8 and not first & mask:
        mask >>= 1
        length += 1
    if length > 8 or pos + length > len(data):
        raise IndexUnavailable("invalid EBML vint")
    value = first if keep_marker else first & (mask - 1)
    all_ones = (first & (mask - 1)) == mask - 1
    for b in data[pos + 1 : pos + length]:
        value = (value << 8) | b
        all_ones = all_ones and b == 0xFF
    if not keep_marker and all_ones:
        return UNKNOWN_SIZE, length
    return value, length


def _element_header(data: bytes, pos: int) -> tuple[int, int, int]:
    """``(element_id, size, header_length)`` at ``pos``."""
    element_id, id_len = _read_vint(data, pos, keep_marker=True)
    size, size_len = _read_vint(data, pos + id_len, keep_marker=False)
    return element_id, size, id_len + size_len


def _children(data: bytes) -> Iterable[tuple[int, bytes]]:
    pos = 0
    while pos < len(data):
        element_id, size, header = _element_header(data, pos)
        if size == UNKNOWN_SIZE or pos + header + size > len(data):
            raise IndexUnavailable("invalid EBML child")
        yield element_id, data[pos + header : pos + header + size]
        pos += header + size


def _uint(data: bytes) -> int:
    return int.from_bytes(data, "big") if data else 0


def _read_element(read: BudgetRead, offset: int) -> tuple[int, int, int]:
    """Header of the element at ``offset``: ``(id, size, header_length)``."""
    head = read(offset, 12)
    if len(head) < 2:
        raise IndexUnavailable("truncated element header")
    return _element_header(head, 0)


def _element_body(read: BudgetRead, offset: int, expected: int) -> bytes:
    element_id, size, header = _read_element(read, offset)
    if element_id != expected or size == UNKNOWN_SIZE or size > _MAX_ELEMENT_BYTES:
        raise IndexUnavailable(f"unexpected element at {offset}")
    return read(offset + header, size)


def mkv_cue_times(read: Read, size: int, video_index: int) -> list[float]:
    """Keyframe times from the ``Cues`` of the track at stream position ``video_index``."""
    r = BudgetRead(read, size)
    element_id, ebml_size, header = _read_element(r, 0)
    if element_id != EBML_ID or ebml_size == UNKNOWN_SIZE:
        raise IndexUnavailable("not an EBML file")
    pos = header + ebml_size
    element_id, _seg_size, header = _read_element(r, pos)
    if element_id != SEGMENT:
        raise IndexUnavailable("no Segment")
    seg_data = pos + header

    positions: dict[int, int] = {}
    bodies: dict[int, bytes] = {}
    pos = seg_data
    while pos < size:
        element_id, el_size, header = _read_element(r, pos)
        if element_id == CLUSTER and CUES in positions:
            break
        if element_id in (SEEK_HEAD, INFO, TRACKS, CUES) and element_id not in bodies:
            if el_size == UNKNOWN_SIZE or el_size > _MAX_ELEMENT_BYTES:
                raise IndexUnavailable("oversized header element")
            bodies[element_id] = r(pos + header, el_size)
            if element_id == SEEK_HEAD:
                for seek_kind, seek in _children(bodies[SEEK_HEAD]):
                    if seek_kind != SEEK:
                        continue
                    fields = dict(_children(seek))
                    target = _uint(fields.get(SEEK_ID, b""))
                    if SEEK_POSITION in fields:
                        positions.setdefault(target, seg_data + _uint(fields[SEEK_POSITION]))
        if el_size == UNKNOWN_SIZE:
            break
        pos += header + el_size
        if CUES in bodies and INFO in bodies and TRACKS in bodies:
            break

    for kind in (INFO, TRACKS, CUES):
        if kind not in bodies and kind in positions:
            bodies[kind] = _element_body(r, positions[kind], kind)
    if CUES not in bodies or TRACKS not in bodies:
        raise IndexUnavailable("no Cues")

    scale = 1_000_000
    for kind, value in _children(bodies.get(INFO, b"")):
        if kind == TIMESTAMP_SCALE:
            scale = _uint(value)
    entries = [dict(_children(v)) for k, v in _children(bodies[TRACKS]) if k == TRACK_ENTRY]
    if video_index >= len(entries) or _uint(entries[video_index].get(TRACK_TYPE, b"")) != 1:
        raise IndexUnavailable("video track not found")
    track_number = _uint(entries[video_index].get(TRACK_NUMBER, b""))

    times: set[float] = set()
    for kind, cue in _children(bodies[CUES]):
        if kind != CUE_POINT:
            continue
        cue_time: int | None = None
        tracks: set[int] = set()
        for child, value in _children(cue):
            if child == CUE_TIME:
                cue_time = _uint(value)
            elif child == CUE_TRACK_POSITIONS:
                for pos_kind, pos_value in _children(value):
                    if pos_kind == CUE_TRACK:
                        tracks.add(_uint(pos_value))
        if cue_time is not None and track_number in tracks:
            times.add(cue_time * scale / 1e9)
    if not times:
        raise IndexUnavailable("no cues for the video track")
    return sorted(times)


# --- MP4 / MOV ---------------------------------------------------------------------------------


def _boxes(data: bytes, start: int = 0, end: int | None = None):
    end = len(data) if end is None else end
    pos = start
    while pos + 8 <= end:
        size, kind = struct.unpack_from(">I4s", data, pos)
        header = 8
        if size == 1:
            (size,) = struct.unpack_from(">Q", data, pos + 8)
            header = 16
        elif size == 0:
            size = end - pos
        if size < header or pos + size > end:
            raise IndexUnavailable(f"invalid box {kind!r}")
        yield kind, pos + header, pos + size
        pos += size


def _child(data: bytes, start: int, end: int, kind: bytes) -> tuple[int, int] | None:
    for k, ps, be in _boxes(data, start, end):
        if k == kind:
            return ps, be
    return None


def _find_moov(r: BudgetRead) -> bytes:
    pos = 0
    while pos + 8 <= r.size:
        head = r(pos, 16)
        size, kind = struct.unpack_from(">I4s", head, 0)
        header = 8
        if size == 1:
            (size,) = struct.unpack_from(">Q", head, 8)
            header = 16
        elif size == 0:
            size = r.size - pos
        if size < header:
            raise IndexUnavailable("invalid top-level box")
        if kind == b"moov":
            if size > _MAX_ELEMENT_BYTES:
                raise IndexUnavailable("moov too large")
            return r(pos + header, size - header)
        pos += size
    raise IndexUnavailable("no moov")


def _table(data: bytes, box: tuple[int, int] | None, row: str) -> tuple[int, list[tuple]]:
    """``(version, rows)`` of a full box with a u32 entry count followed by ``row`` entries."""
    if box is None:
        return 0, []
    start, _end = box
    version = data[start]
    count = struct.unpack_from(">I", data, start + 4)[0]
    width = struct.calcsize(row)
    return version, [struct.unpack_from(row, data, start + 8 + i * width) for i in range(count)]


def mp4_sync_times(read: Read, size: int, video_index: int) -> list[float]:
    """Keyframe presentation times of the ``trak`` at stream position ``video_index``: ``stss``
    mapped through ``stts`` (+ ``ctts``), minus the ``elst`` media time, in ``mdhd`` units."""
    moov = _find_moov(BudgetRead(read, size))
    if _child(moov, 0, len(moov), b"mvex") is not None:
        raise IndexUnavailable("fragmented MP4")
    mvhd = _child(moov, 0, len(moov), b"mvhd")
    movie_timescale = 1000
    if mvhd is not None:
        version = moov[mvhd[0]]
        movie_timescale = struct.unpack_from(">I", moov, mvhd[0] + (20 if version == 1 else 12))[0]
    traks = [(ps, be) for kind, ps, be in _boxes(moov) if kind == b"trak"]

    def is_video(trak: tuple[int, int]) -> bool:
        mdia_box = _child(moov, *trak, b"mdia")
        hdlr_box = _child(moov, *mdia_box, b"hdlr") if mdia_box else None
        return hdlr_box is not None and moov[hdlr_box[0] + 8 : hdlr_box[0] + 12] == b"vide"

    # Stream index == trak ordinal, except when a cover art (``covr``, not a trak) is exposed
    # as an earlier stream: then the single video trak is the one meant.
    videos = [t for t in traks if is_video(t)]
    if video_index < len(traks) and is_video(traks[video_index]):
        t_start, t_end = traks[video_index]
    elif len(videos) == 1:
        t_start, t_end = videos[0]
    else:
        raise IndexUnavailable("video trak not found")
    mdia = _child(moov, t_start, t_end, b"mdia")
    assert mdia is not None
    mdhd = _child(moov, *mdia, b"mdhd")
    if mdhd is None:
        raise IndexUnavailable("no mdhd")
    version = moov[mdhd[0]]
    timescale = struct.unpack_from(">I", moov, mdhd[0] + (20 if version == 1 else 12))[0]
    minf = _child(moov, *mdia, b"minf")
    stbl = _child(moov, *minf, b"stbl") if minf else None
    if stbl is None or not timescale:
        raise IndexUnavailable("no stbl")

    _, stts = _table(moov, _child(moov, *stbl, b"stts"), ">II")
    _, ctts = _table(moov, _child(moov, *stbl, b"ctts"), ">Ii")
    stss_box = _child(moov, *stbl, b"stss")
    _, stss_rows = _table(moov, stss_box, ">I")
    sample_count = sum(count for count, _delta in stts)
    if sample_count == 0:
        raise IndexUnavailable("no samples (fragmented source)")
    sync = sorted({n for (n,) in stss_rows}) if stss_box else list(range(1, sample_count + 1))

    media_time = 0
    empty_offset = 0.0
    edts = _child(moov, t_start, t_end, b"edts")
    elst = _child(moov, *edts, b"elst") if edts else None
    if elst is not None:
        e_version = moov[elst[0]]
        _, edits = _table(moov, elst, ">QqI" if e_version == 1 else ">IiI")
        for duration, mtime, _rate in edits:
            if mtime == -1:
                empty_offset += duration / movie_timescale
                continue
            media_time = mtime
            break

    times: list[float] = []
    stts_iter = iter(stts)
    ctts_iter = iter(ctts)
    run_left, delta = 0, 0
    c_left, c_off = 0, 0
    dts = 0
    sample = 1
    for target in sync:
        if target > sample_count:
            break
        while sample <= target:
            if run_left == 0:
                run_left, delta = next(stts_iter)
            if ctts and c_left == 0:
                c_left, c_off = next(ctts_iter, (1 << 62, 0))
            if sample == target:
                pts = dts + (c_off if ctts else 0) - media_time
                times.append(pts / timescale + empty_offset)
            dts += delta
            run_left -= 1
            if ctts:
                c_left -= 1
            sample += 1
    if not times:
        raise IndexUnavailable("no sync samples")
    return sorted(set(times))


# --- ffprobe packet rows (fallback source) -------------------------------------------------


def parse_packet_row(line: str) -> float | None:
    """One ``pts_time,dts_time,flags`` row -> keyframe time, else None (non-key / no time)."""
    parts = line.strip().split(",")
    if len(parts) < 3 or "K" not in parts[2]:
        return None
    for value in (parts[0], parts[1]):
        if value and value != "N/A":
            try:
                return float(value)
            except ValueError:
                return None
    return None


def parse_packet_rows(lines: Iterable[str]) -> list[float]:
    times = {t for t in (parse_packet_row(line) for line in lines) if t is not None}
    return sorted(times)
