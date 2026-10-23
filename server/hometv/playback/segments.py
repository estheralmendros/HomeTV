"""Segment plan, playlist text, run.csv verification and the fMP4 splitter (PRD §7). Pure.

Times are seconds relative to ``probe.start_seconds``. ffmpeg's outputs (``run.csv``, ``tfdt``)
are on the offset timeline (plan time + ``TS_OFFSET_SECONDS``); the offset is removed here
before every comparison with the plan.
"""

import bisect
import math
import mmap
import re
import struct
from dataclasses import dataclass, field

TS_OFFSET_SECONDS = 10  # T0: constant -output_ts_offset of every ffmpeg run (§7 HLS step 3)
MIN_TAIL_SECONDS = 0.5
MAX_REORDER_SECONDS = 0.5  # open-GOP leading pictures / B-frame reorder depth
LAST_SEGMENT_SLACK_SECONDS = 1.0


@dataclass(frozen=True)
class Segment:
    start: float
    duration: float

    @property
    def end(self) -> float:
        return self.start + self.duration


def plan_segments(keyframes: list[float], duration: float, seg: float) -> list[Segment]:
    """Boundaries start at 0; a keyframe becomes a boundary once it is >= ``seg`` after the
    previous one; the last segment ends at ``duration`` (a tail < 0.5 s is merged)."""
    bounds = [0.0]
    for t in sorted(keyframes):
        if t >= duration:
            break
        if t - bounds[-1] >= seg:
            bounds.append(t)
    if len(bounds) > 1 and duration - bounds[-1] < MIN_TAIL_SECONDS:
        bounds.pop()
    ends = [*bounds[1:], duration]
    return [Segment(start, end - start) for start, end in zip(bounds, ends, strict=True)]


def segment_index(plan: list[Segment], position: float) -> int:
    """Index of the segment containing ``position`` (clamped to the plan)."""
    starts = [s.start for s in plan]
    return max(0, min(len(plan) - 1, bisect.bisect_right(starts, position) - 1))


def max_segment_seconds(seg: float) -> float:
    return max(4 * seg, 30.0)


@dataclass(frozen=True)
class PlanCheck:
    ok: bool
    longest: float


def check_plan(plan: list[Segment], seg: float) -> PlanCheck:
    """Sparse-keyframe guard: every segment (the last included) <= max(4 x seg, 30 s)."""
    longest = max((s.duration for s in plan), default=0.0)
    return PlanCheck(round(longest, 6) <= max_segment_seconds(seg), longest)


def render_playlist(plan: list[Segment]) -> str:
    durations = [round(s.duration, 6) for s in plan]
    lines = [
        "#EXTM3U",
        "#EXT-X-VERSION:7",
        f"#EXT-X-TARGETDURATION:{math.ceil(max(durations, default=0.0))}",
        "#EXT-X-PLAYLIST-TYPE:VOD",
        "#EXT-X-INDEPENDENT-SEGMENTS",
        '#EXT-X-MAP:URI="init.mp4"',
    ]
    for k, d in enumerate(durations):
        lines += [f"#EXTINF:{d:.6f},", f"seg_{k}.m4s"]
    lines.append("#EXT-X-ENDLIST")
    return "\n".join(lines) + "\n"


# --- run.csv verification ------------------------------------------------------------------

_ROW_RE = re.compile(r"^raw_(\d+)\.mp4,(-?[0-9.]+),(-?[0-9.]+)$")


@dataclass(frozen=True)
class RunRow:
    k: int
    start: float  # offset timeline (as written by ffmpeg)
    end: float


def parse_run_row(line: str) -> RunRow | None:
    match = _ROW_RE.match(line.strip())
    if match is None:
        return None
    return RunRow(int(match.group(1)), float(match.group(2)), float(match.group(3)))


@dataclass(frozen=True)
class RowCheck:
    ok: bool
    reason: str | None = None
    seek_mismatch: bool = False


def verify_row(
    row: RunRow,
    plan: list[Segment],
    *,
    first_of_run: bool,
    duration: float,
    frame: float,
    expected_start: float | None = None,
) -> RowCheck:
    """Check a ``run.csv`` row against the plan (PRD §7 HLS step 4, Rev 4/5 rules).

    ``expected_start`` overrides ``plan[k].start`` (segment 0 starts at the first keyframe,
    which may be slightly after 0). The first row of a run whose start is exactly 0.0 (the
    segment muxer's initial value, not a packet time) skips the start check; the video
    ``tfdt`` check covers it."""
    k = row.k
    n = len(plan)
    if k >= n:
        return RowCheck(False, f"segment {k} is beyond the plan ({n} segments)")
    planned = plan[k].start if expected_start is None else expected_start
    seek_mismatch = False
    if not (first_of_run and row.start == 0.0):
        start = row.start - TS_OFFSET_SECONDS
        if start > planned + frame:
            return RowCheck(False, f"segment {k} starts at {start:.6f}, planned {planned:.6f}")
        if start < planned - frame:
            if not first_of_run:
                return RowCheck(False, f"segment {k} starts at {start:.6f}, planned {planned:.6f}")
            seek_mismatch = True
    end = row.end - TS_OFFSET_SECONDS
    if k < n - 1:
        next_start = plan[k + 1].start
        if end > next_start + frame:
            return RowCheck(False, f"segment {k} ends at {end:.6f}, after cut {next_start:.6f}")
        if end < next_start - MAX_REORDER_SECONDS:
            return RowCheck(False, f"segment {k} ends at {end:.6f}, before cut {next_start:.6f}")
    elif end > duration + LAST_SEGMENT_SLACK_SECONDS:
        return RowCheck(False, f"last segment ends at {end:.6f}, duration {duration:.6f}")
    return RowCheck(True, None, seek_mismatch)


# --- fMP4 box walking ----------------------------------------------------------------------


class Fmp4Error(ValueError):
    """Not a fragmented MP4 the splitter understands."""


# What the fMP4 helpers read: ``bytes`` or a read-only memory map of ``raw_k.mp4``.
Buffer = bytes | mmap.mmap


def _boxes(data: Buffer, start: int = 0, end: int | None = None):
    """Yield ``(type, box_start, payload_start, box_end)`` for the boxes in ``data[start:end]``."""
    end = len(data) if end is None else end
    pos = start
    while pos + 8 <= end:
        size, kind = struct.unpack_from(">I4s", data, pos)
        header = 8
        if size == 1:
            if pos + 16 > end:
                raise Fmp4Error("truncated largesize box")
            (size,) = struct.unpack_from(">Q", data, pos + 8)
            header = 16
        elif size == 0:
            size = end - pos
        if size < header or pos + size > end:
            raise Fmp4Error(f"invalid box size for {kind!r} at {pos}")
        yield kind, pos, pos + header, pos + size
        pos += size


def _child(data: Buffer, start: int, end: int, kind: bytes) -> tuple[int, int] | None:
    for k, _bs, ps, be in _boxes(data, start, end):
        if k == kind:
            return ps, be
    return None


@dataclass(frozen=True)
class TrackInfo:
    track_id: int
    handler: str
    timescale: int


def parse_init(init: bytes) -> dict[int, TrackInfo]:
    moov = _child(init, 0, len(init), b"moov")
    if moov is None:
        raise Fmp4Error("no moov box")
    tracks: dict[int, TrackInfo] = {}
    for kind, _bs, ps, be in _boxes(init, *moov):
        if kind != b"trak":
            continue
        tkhd = _child(init, ps, be, b"tkhd")
        mdia = _child(init, ps, be, b"mdia")
        if tkhd is None or mdia is None:
            continue
        version = init[tkhd[0]]
        track_id = struct.unpack_from(">I", init, tkhd[0] + (20 if version == 1 else 12))[0]
        mdhd = _child(init, *mdia, b"mdhd")
        hdlr = _child(init, *mdia, b"hdlr")
        if mdhd is None or hdlr is None:
            continue
        version = init[mdhd[0]]
        timescale = struct.unpack_from(">I", init, mdhd[0] + (20 if version == 1 else 12))[0]
        handler = init[hdlr[0] + 8 : hdlr[0] + 12].decode("latin-1")
        tracks[track_id] = TrackInfo(track_id, handler, timescale)
    return tracks


def has_edit_list(init: bytes) -> bool:
    moov = _child(init, 0, len(init), b"moov")
    if moov is None:
        return False
    for kind, _bs, ps, be in _boxes(init, *moov):
        if kind == b"trak" and _child(init, ps, be, b"edts") is not None:
            return True
    return False


@dataclass(frozen=True)
class Fmp4Split:
    """``init`` is materialized (small); the media is ``media_ranges``, ``(start, end)`` byte
    offsets of the ``moof``/``mdat`` boxes in the source buffer, so a large segment is never
    copied in memory (``_finalize`` writes the ranges from a memory map)."""

    init: bytes
    media_ranges: tuple[tuple[int, int], ...]
    tracks: dict[int, TrackInfo]
    first_tfdt: dict[int, int] = field(default_factory=dict)
    first_cto: dict[int, int] = field(default_factory=dict)

    @property
    def media_size(self) -> int:
        return sum(end - start for start, end in self.media_ranges)

    def _start(self, handler: str, with_cto: bool) -> float | None:
        for track_id, info in self.tracks.items():
            if info.handler == handler and track_id in self.first_tfdt and info.timescale:
                ticks = self.first_tfdt[track_id]
                if with_cto:
                    ticks += self.first_cto.get(track_id, 0)
                return ticks / info.timescale
        return None

    def video_start(self) -> float | None:
        """First video sample presentation time (``tfdt`` + composition offset), offset
        timeline, seconds."""
        return self._start("vide", True)

    def audio_start(self) -> float | None:
        """Audio ``tfdt`` (offset timeline, seconds)."""
        return self._start("soun", False)


def _parse_traf(data: Buffer, start: int, end: int) -> tuple[int, int | None, int | None]:
    tfhd = _child(data, start, end, b"tfhd")
    if tfhd is None:
        raise Fmp4Error("traf without tfhd")
    track_id = struct.unpack_from(">I", data, tfhd[0] + 4)[0]
    tfdt_value: int | None = None
    tfdt = _child(data, start, end, b"tfdt")
    if tfdt is not None:
        version = data[tfdt[0]]
        fmt = ">Q" if version == 1 else ">I"
        tfdt_value = struct.unpack_from(fmt, data, tfdt[0] + 4)[0]
    cto: int | None = None
    trun = _child(data, start, end, b"trun")
    if trun is not None:
        version = data[trun[0]]
        flags = int.from_bytes(data[trun[0] + 1 : trun[0] + 4], "big")
        count = struct.unpack_from(">I", data, trun[0] + 4)[0]
        pos = trun[0] + 8
        if flags & 0x000001:
            pos += 4
        if flags & 0x000004:
            pos += 4
        if count and flags & 0x000800:
            for bit in (0x000100, 0x000200, 0x000400):
                if flags & bit:
                    pos += 4
            cto = struct.unpack_from(">i" if version == 1 else ">I", data, pos)[0]
    return track_id, tfdt_value, cto


def split_fmp4(data: Buffer) -> Fmp4Split:
    """Split a segment-muxer ``raw_k.mp4`` into its init section (everything up to the end of
    ``moov``) and its ``moof``/``mdat`` pairs (a trailing ``mfra`` is dropped); read each
    track's first ``tfdt`` and first-sample composition offset. ``data`` may be ``bytes`` or
    an ``mmap`` (only the init and box headers are read)."""
    init_end: int | None = None
    media: list[tuple[int, int]] = []
    first_tfdt: dict[int, int] = {}
    first_cto: dict[int, int] = {}
    for kind, bs, ps, be in _boxes(data):
        if kind == b"moov":
            init_end = be
        elif kind == b"moof":
            if init_end is None:
                raise Fmp4Error("moof before moov")
            for child, _cbs, cps, cbe in _boxes(data, ps, be):
                if child != b"traf":
                    continue
                track_id, tfdt, cto = _parse_traf(data, cps, cbe)
                if tfdt is not None and track_id not in first_tfdt:
                    first_tfdt[track_id] = tfdt
                    if cto is not None:
                        first_cto[track_id] = cto
            _extend(media, bs, be)
        elif kind == b"mdat" and init_end is not None and media:
            _extend(media, bs, be)
    if init_end is None:
        raise Fmp4Error("no moov box")
    if not media:
        raise Fmp4Error("no movie fragment")
    init = bytes(data[:init_end])
    return Fmp4Split(init, tuple(media), parse_init(init), first_tfdt, first_cto)


def _extend(ranges: list[tuple[int, int]], start: int, end: int) -> None:
    """Append a box range, merging it with the previous one when contiguous."""
    if ranges and ranges[-1][1] == start:
        ranges[-1] = (ranges[-1][0], end)
    else:
        ranges.append((start, end))


def verify_tfdt(split: Fmp4Split, *, expected_start: float, frame: float) -> bool:
    """Requirement (a): video ``tfdt`` + first composition offset == start + T0 (+-1 frame)."""
    video = split.video_start()
    if video is None:
        return False
    return abs((video - TS_OFFSET_SECONDS) - expected_start) <= frame + 1e-6


def _descriptor(data: bytes | bytearray, pos: int) -> tuple[int, int, int]:
    """MPEG-4 descriptor at ``pos``: ``(tag, payload_start, payload_end)``."""
    tag = data[pos]
    pos += 1
    size = 0
    for _ in range(4):
        byte = data[pos]
        pos += 1
        size = (size << 7) | (byte & 0x7F)
        if not byte & 0x80:
            break
    return tag, pos, pos + size


def _esds_has_decoder_specific_info(data: bytes, start: int, end: int) -> bool:
    try:
        tag, ps, _pe = _descriptor(data, start + 4)  # skip version/flags
        if tag != 0x03:
            return False
        flags = data[ps + 2]
        pos = ps + 3
        if flags & 0x80:
            pos += 2
        if flags & 0x40:
            pos += 1 + data[pos]
        if flags & 0x20:
            pos += 2
        tag, ds, de = _descriptor(data, pos)
        if tag != 0x04:
            return False
        pos = ds + 13
        while pos < de:
            tag, cs, ce = _descriptor(data, pos)
            if tag == 0x05:
                return ce > cs
            pos = ce
    except IndexError:
        return False
    return False


def init_is_complete(init: bytes) -> bool:
    """False when an ``mp4a`` sample entry has an ``esds`` without its DecoderSpecificInfo
    (AudioSpecificConfig). The segment muxer writes the first segment's ``moov`` before
    ``aac_adtstoasc`` has seen a packet, so ADTS sources (MPEG-TS) start with such an init."""
    pos = 0
    while True:
        pos = init.find(b"esds", pos)
        if pos < 4:
            return True
        size = struct.unpack_from(">I", init, pos - 4)[0]
        if not _esds_has_decoder_specific_info(init, pos + 4, pos - 4 + size):
            return False
        pos += 4


def normalize_init(init: bytes) -> bytes:
    """``init`` with the informational bitrate fields zeroed (``esds`` DecoderConfigDescriptor
    max/avg bitrate and ``btrt``): for a stream-copied ADTS track ffmpeg estimates them from
    the packets after each run's seek, so they legitimately differ between runs."""
    data = bytearray(init)
    pos = 0
    while (pos := data.find(b"btrt", pos)) >= 4:
        size = struct.unpack_from(">I", data, pos - 4)[0]
        data[pos + 4 : pos - 4 + size] = bytes(size - 8)
        pos += 4
    pos = 0
    while (pos := data.find(b"esds", pos)) >= 4:
        try:
            tag, ps, _pe = _descriptor(data, pos + 8)
            if tag == 0x03:
                flags = data[ps + 2]
                cursor = ps + 3 + (2 if flags & 0x80 else 0)
                if flags & 0x40:
                    cursor += 1 + data[cursor]
                cursor += 2 if flags & 0x20 else 0
                tag, ds, _de = _descriptor(data, cursor)
                if tag == 0x04:
                    data[ds + 5 : ds + 13] = bytes(8)
        except IndexError:
            pass
        pos += 4
    return bytes(data)
