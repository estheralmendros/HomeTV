"""Keyframe readers on stored header fixtures, ffprobe row parsing, and the `preparing` estimate."""

import base64
import json
import struct
import zlib
from pathlib import Path

import pytest

from hometv.playback.keyframe_readers import (
    IndexUnavailable,
    mkv_cue_times,
    mp4_sync_times,
    parse_packet_row,
    parse_packet_rows,
)
from hometv.playback.keyframes import MB, RateSample, estimate_seconds

FIXTURES = Path(__file__).parent.parent / "fixtures" / "keyframes"
GB = 1000 * MB


def load(name: str):
    fixture = json.loads((FIXTURES / name).read_text())
    chunks = [(o, zlib.decompress(base64.b64decode(b))) for o, b in fixture["chunks"]]

    def read(offset: int, size: int) -> bytes:
        for start, data in chunks:
            if start <= offset and offset + size <= start + len(data):
                return data[offset - start : offset - start + size]
        raise AssertionError(f"read outside the stored ranges: {offset}+{size}")

    return fixture, read


@pytest.mark.parametrize(
    ("name", "reader"),
    [
        ("mkv_cues_mkv.json", mkv_cue_times),
        ("mkv_audio_first_mkv.json", mkv_cue_times),
        ("vp9_opus_webm.json", mkv_cue_times),
        ("bframes_editlist_mp4.json", mp4_sync_times),
    ],
)
def test_reader_matches_ffprobe_packet_scan(name, reader):
    fixture, read = load(name)
    times = reader(read, fixture["size"], fixture["video_index"])
    relative = [t - fixture["start_seconds"] for t in times]
    assert len(relative) == len(fixture["ffprobe_times"])
    for got, want in zip(relative, fixture["ffprobe_times"], strict=True):
        assert got == pytest.approx(want, abs=0.001)


def test_mp4_fixture_has_bframes_and_an_edit_list():
    fixture, _read = load("bframes_editlist_mp4.json")
    blob = b"".join(zlib.decompress(base64.b64decode(b)) for _o, b in fixture["chunks"])
    assert b"ctts" in blob
    assert b"elst" in blob


def test_mkv_wrong_stream_index_is_unavailable():
    fixture, read = load("mkv_audio_first_mkv.json")
    with pytest.raises(IndexUnavailable):
        mkv_cue_times(read, fixture["size"], 0)  # stream 0 is the audio track


def test_mp4_cover_art_offset_selects_the_single_video_trak():
    """With a cover art exposed as stream 0, probe.video.index (1) is past the video trak's
    ordinal (0); the only video trak is used."""
    fixture, read = load("bframes_editlist_mp4.json")
    assert mp4_sync_times(read, fixture["size"], 1) == mp4_sync_times(read, fixture["size"], 0)


def test_non_container_data_is_unavailable():
    data = b"\x47" + b"\0" * 1000  # an MPEG-TS sync byte

    def read(offset: int, size: int) -> bytes:
        return data[offset : offset + size]

    with pytest.raises(IndexUnavailable):
        mkv_cue_times(read, len(data), 0)
    with pytest.raises(IndexUnavailable):
        mp4_sync_times(read, len(data), 0)


def test_fragmented_mp4_is_unavailable():
    def box(kind: bytes, payload: bytes) -> bytes:
        return struct.pack(">I4s", 8 + len(payload), kind) + payload

    data = box(b"ftyp", b"isom" * 2) + box(b"moov", box(b"mvex", b"")) + box(b"moof", b"")

    def read(offset: int, size: int) -> bytes:
        return data[offset : offset + size]

    with pytest.raises(IndexUnavailable):
        mp4_sync_times(read, len(data), 0)


def test_read_budget_is_enforced():
    fixture, read = load("mkv_cues_mkv.json")
    calls = []

    def counting(offset: int, size: int) -> bytes:
        calls.append(size)
        return read(offset, size)

    mkv_cue_times(counting, fixture["size"], 0)
    assert sum(calls) <= 64 * 1024 * 1024


# --- ffprobe packet rows -----------------------------------------------------------------


def test_packet_rows_keep_only_keyframes():
    rows = ["0.000000,-0.080000,K__", "0.040000,0.000000,___", "1.000000,0.920000,K_D"]
    assert parse_packet_rows(rows) == [0.0, 1.0]


def test_packet_row_pts_na_uses_dts():
    assert parse_packet_row("N/A,4.000000,K__") == 4.0
    assert parse_packet_row("N/A,N/A,K__") is None
    assert parse_packet_row("") is None
    assert parse_packet_row("garbage") is None


def test_packet_rows_sorted_and_deduplicated():
    assert parse_packet_rows(["2.0,2.0,K", "1.0,N/A,K", "2.0,1.9,K"]) == [1.0, 2.0]


# --- estimated_seconds -------------------------------------------------------------------


def test_estimate_before_samples_uses_50mbps():
    assert estimate_seconds(10 * GB, [], started=0.0, now=0.5, proc_io=True) == 200


def test_estimate_from_measured_rate():
    samples = [RateSample(0.0, 0), RateSample(10.0, 1 * GB)]
    assert estimate_seconds(10 * GB, samples, started=0.0, now=10.0, proc_io=True) == 90


def test_estimate_uses_cumulative_rate_when_window_is_flat():
    # 2 GB read in the first 5 s, then flat for 15 s: windowed rate 0, cumulative 100 MB/s.
    samples = [RateSample(t, min(2 * GB, int(t * 400 * MB))) for t in range(0, 21)]
    estimate = estimate_seconds(10 * GB, samples, started=0.0, now=20.0, proc_io=True)
    assert estimate == 80  # 8 GB / (2 GB / 20 s)


def test_estimate_floor_of_one_mbps_and_clamp():
    samples = [RateSample(0.0, 0), RateSample(100_000.0, 10 * MB)]
    assert estimate_seconds(100 * GB, samples, started=0.0, now=100_000.0, proc_io=True) == 86_400
    samples = [RateSample(0.0, 0), RateSample(5000.0, 1 * GB)]
    # cumulative 0.2 MB/s -> floor 1 MB/s -> 1 GB / 1 MB/s = 1000 s
    assert estimate_seconds(2 * GB, samples, started=0.0, now=5000.0, proc_io=True) == 1000


def test_estimate_when_already_read_more_than_size():
    samples = [RateSample(0.0, 0), RateSample(2.0, 11 * GB)]
    assert estimate_seconds(10 * GB, samples, started=0.0, now=2.0, proc_io=True) == 1


def test_estimate_without_proc_io():
    assert estimate_seconds(10 * GB, [], started=0.0, now=100.0, proc_io=False) == 100


def test_estimate_windowed_rate_over_last_ten_seconds():
    # slow start then fast: window (last 10 s) 200 MB/s > cumulative
    samples = [RateSample(float(t), 0 if t <= 10 else (t - 10) * 200 * MB) for t in range(0, 21)]
    estimate = estimate_seconds(10 * GB, samples, started=0.0, now=20.0, proc_io=True)
    assert estimate == 40  # (10 GB - 2 GB) / 200 MB/s
