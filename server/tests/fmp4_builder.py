"""Minimal fragmented-MP4 writer for tests (stub ffmpeg output): ftyp + moov (one video and
optionally one audio trak, no elst) + one moof/mdat pair per segment + a trailing mfra."""

import struct

VIDEO_TRACK = 1
AUDIO_TRACK = 2


def box(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I4s", 8 + len(payload), kind) + payload


def full_box(kind: bytes, version: int, flags: int, payload: bytes) -> bytes:
    return box(kind, struct.pack(">I", (version << 24) | flags) + payload)


def _trak(track_id: int, handler: bytes, timescale: int) -> bytes:
    tkhd = full_box(b"tkhd", 0, 3, struct.pack(">III", 0, 0, track_id) + b"\0" * 68)
    mdhd = full_box(b"mdhd", 0, 0, struct.pack(">IIII", 0, 0, timescale, 0) + b"\0" * 4)
    hdlr = full_box(b"hdlr", 0, 0, struct.pack(">I4s", 0, handler) + b"\0" * 12 + b"x\0")
    return box(b"trak", tkhd + box(b"mdia", mdhd + hdlr + box(b"minf", b"")))


def init_segment(video_timescale: int = 12800, audio_timescale: int | None = 48000) -> bytes:
    ftyp = box(b"ftyp", b"isom\0\0\x02\0isomiso6mp41")
    traks = _trak(VIDEO_TRACK, b"vide", video_timescale)
    if audio_timescale:
        traks += _trak(AUDIO_TRACK, b"soun", audio_timescale)
    mvhd = full_box(b"mvhd", 0, 0, b"\0" * 96)
    return ftyp + box(b"moov", mvhd + traks)


def _traf(track_id: int, tfdt: int, cto: int | None) -> bytes:
    tfhd = full_box(b"tfhd", 0, 0x020000, struct.pack(">I", track_id))
    tfdt_box = full_box(b"tfdt", 1, 0, struct.pack(">Q", tfdt))
    flags = 0x000001 | 0x000100 | 0x000200
    sample = struct.pack(">II", 512, 100)
    if cto is not None:
        flags |= 0x000800
        sample += struct.pack(">i", cto)
    trun = full_box(b"trun", 1, flags, struct.pack(">Ii", 1, 0) + sample)
    return box(b"traf", tfhd + tfdt_box + trun)


def media_segment(
    start_seconds: float,
    *,
    video_timescale: int = 12800,
    audio_timescale: int | None = 48000,
    video_cto: int = 0,
    mdat_size: int = 100,
) -> bytes:
    video_tfdt = round(start_seconds * video_timescale) - video_cto
    trafs = _traf(VIDEO_TRACK, video_tfdt, video_cto)
    if audio_timescale:
        trafs += _traf(AUDIO_TRACK, round(start_seconds * audio_timescale), None)
    moof = box(b"moof", full_box(b"mfhd", 0, 0, struct.pack(">I", 1)) + trafs)
    return moof + box(b"mdat", b"\0" * mdat_size)


def raw_segment(start_seconds: float, **kwargs) -> bytes:
    """A complete ``raw_k.mp4`` as the segment muxer writes it."""
    video_ts = kwargs.get("video_timescale", 12800)
    audio_ts = kwargs.get("audio_timescale", 48000)
    return (
        init_segment(video_ts, audio_ts)
        + media_segment(start_seconds, **kwargs)
        + box(b"mfra", b"\0" * 8)
    )
