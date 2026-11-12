"""plan_segments / check_plan / run.csv verification / split_fmp4."""

from pathlib import Path

import pytest

from hometv.playback.segments import (
    TS_OFFSET_SECONDS,
    Fmp4Error,
    Segment,
    check_plan,
    has_edit_list,
    init_is_complete,
    max_segment_seconds,
    normalize_init,
    parse_run_row,
    plan_segments,
    segment_index,
    split_fmp4,
    verify_row,
    verify_tfdt,
)
from tests import fmp4_builder as fb

FIXTURES = Path(__file__).parent.parent / "fixtures" / "fmp4"
T0 = TS_OFFSET_SECONDS
FRAME = 0.04


def starts(plan: list[Segment]) -> list[float]:
    return [round(s.start, 6) for s in plan]


def durations(plan: list[Segment]) -> list[float]:
    return [round(s.duration, 6) for s in plan]


# --- plan_segments -----------------------------------------------------------------------


def test_uniform_one_second_gop():
    plan = plan_segments([float(t) for t in range(20)], 20.0, 6)
    assert starts(plan) == [0, 6, 12, 18]
    assert durations(plan) == [6, 6, 6, 2]


def test_uniform_ten_second_gop():
    plan = plan_segments([0.0, 10.0, 20.0, 30.0, 40.0, 50.0], 60.0, 6)
    assert starts(plan) == [0, 10, 20, 30, 40, 50]
    assert durations(plan) == [10] * 6


def test_irregular_gop():
    # GOPs of 2, 9, 3, 12 s
    plan = plan_segments([0.0, 2.0, 11.0, 14.0], 26.0, 6)
    assert starts(plan) == [0, 11]
    assert durations(plan) == [11, 15]


def test_single_keyframe():
    plan = plan_segments([0.0], 20.0, 6)
    assert starts(plan) == [0]
    assert durations(plan) == [20]


def test_tiny_tail_is_merged():
    plan = plan_segments([0.0, 6.0, 12.0], 12.3, 6)
    assert starts(plan) == [0, 6]
    assert durations(plan) == [6, 6.3]


def test_duration_not_on_a_keyframe():
    plan = plan_segments([0.0, 6.0, 12.0, 18.0], 21.7, 6)
    assert durations(plan) == [6, 6, 6, 3.7]


def test_keyframes_after_duration_and_unsorted_input_are_ignored():
    plan = plan_segments([12.0, 0.0, 6.0, 30.0], 14.0, 6)
    assert starts(plan) == [0, 6, 12]
    assert durations(plan) == [6, 6, 2]


def test_first_keyframe_after_zero_still_starts_at_zero():
    plan = plan_segments([0.023, 4.023, 8.023, 12.023], 16.0, 6)
    assert starts(plan) == [0, 8.023]


def test_segment_index_lookup():
    plan = plan_segments([0.0, 10.0, 20.0], 30.0, 6)
    assert segment_index(plan, 0) == 0
    assert segment_index(plan, 9.99) == 0
    assert segment_index(plan, 10.0) == 1
    assert segment_index(plan, 29.0) == 2
    assert segment_index(plan, 500.0) == 2
    assert segment_index(plan, -3) == 0


# --- check_plan --------------------------------------------------------------------------


def test_check_plan_sparse_keyframes_fail():
    result = check_plan(plan_segments([0.0, 300.0], 600.0, 6), 6)
    assert not result.ok
    assert result.longest == pytest.approx(300.0)


def test_check_plan_single_keyframe_20s_passes():
    assert check_plan(plan_segments([0.0], 20.0, 6), 6).ok


def test_check_plan_30s_gap_boundary():
    assert check_plan(plan_segments([0.0, 30.0], 60.0, 6), 6).ok
    assert not check_plan(plan_segments([0.0, 30.1], 60.0, 6), 6).ok


def test_check_plan_last_segment_of_several_is_unbounded():
    # Audio/subtitles running past the video's last keyframe. Real files: a 44.4 s tail, and
    # a 97.9 s one (video ends 3.3 s after its last keyframe at 8771.339; the format duration
    # 8869.248 comes from the subtitle tracks).
    keyframes = [float(t) for t in range(0, 5608, 10)]
    assert check_plan(plan_segments(keyframes, 5652.3, 6), 6).ok
    keyframes = [*(float(t) for t in range(0, 8770, 10)), 8771.339]
    result = check_plan(plan_segments(keyframes, 8869.248, 6), 6)
    assert result.ok
    assert result.longest == pytest.approx(8869.248 - keyframes[-1])
    assert check_plan(plan_segments([0.0, 20.0], 600.0, 6), 6).ok


def test_check_plan_tail_allowance_only_for_the_last_segment():
    assert not check_plan(plan_segments([0.0, 31.0, 40.0], 50.0, 6), 6).ok


def test_check_plan_single_segment_keeps_the_normal_bound():
    assert check_plan(plan_segments([0.0], 30.0, 6), 6).ok
    assert not check_plan(plan_segments([0.0], 31.0, 6), 6).ok


def test_check_plan_bound_with_larger_segment_setting():
    assert max_segment_seconds(6) == 30
    assert max_segment_seconds(10) == 40
    assert check_plan(plan_segments([0.0, 40.0], 80.0, 10), 10).ok
    assert not check_plan(plan_segments([0.0, 40.5], 80.0, 10), 10).ok


# --- run.csv verification ----------------------------------------------------------------

PLAN = plan_segments([0.0, 10.0, 20.0, 30.0], 40.0, 6)  # 4 x 10 s


def row(k: int, start: float, end: float) -> str:
    return f"raw_{k}.mp4,{start + T0:.6f},{end + T0:.6f}"


def check(line: str, *, first: bool = False, duration: float = 40.0):
    return verify_row(parse_run_row(line), PLAN, first_of_run=first, duration=duration, frame=FRAME)


def test_parse_run_row():
    parsed = parse_run_row("raw_12.mp4,20.000000,30.500000")
    assert (parsed.k, parsed.start, parsed.end) == (12, 20.0, 30.5)
    assert parse_run_row("garbage") is None
    assert parse_run_row("") is None


def test_exact_row_accepted():
    assert check(row(1, 10.0, 20.0)).ok


def test_open_gop_end_three_frames_short_accepted():
    assert check(row(1, 10.0, 20.0 - 3 * FRAME)).ok


def test_merged_segments_rejected():
    result = check(row(1, 10.0, 30.0))
    assert not result.ok
    assert result.reason


def test_extra_cut_one_second_short_rejected():
    assert not check(row(1, 10.0, 19.0)).ok


def test_non_first_row_starting_two_frames_late_rejected():
    assert not check(row(1, 10.0 + 2 * FRAME, 20.0)).ok


def test_non_first_row_starting_early_rejected():
    assert not check(row(2, 20.0 - 2 * FRAME, 30.0)).ok


def test_first_of_run_starting_early_is_a_seek_mismatch():
    result = check(row(2, 19.0, 30.0), first=True)
    assert result.ok
    assert result.seek_mismatch


def test_first_of_run_starting_late_rejected():
    assert not check(row(2, 21.0, 30.0), first=True).ok


def test_first_of_run_with_ffmpeg_initial_zero_start_skips_the_start_check():
    # ffmpeg's segment muxer prints 0.000000 as start_time for the first segment of a run.
    result = check(f"raw_2.mp4,0.000000,{30.0 + T0:.6f}", first=True)
    assert result.ok
    assert not result.seek_mismatch


def test_last_segment_ending_early_accepted():
    assert check(row(3, 30.0, 38.0)).ok


def test_last_segment_past_duration_rejected():
    assert check(row(3, 30.0, 41.0)).ok
    assert not check(row(3, 30.0, 41.5)).ok


def test_row_beyond_plan_rejected():
    assert not check(row(4, 40.0, 41.0)).ok


# --- split_fmp4 / tfdt ---------------------------------------------------------------------


def test_split_real_fixture():
    data = (FIXTURES / "raw_1_bframes.mp4").read_bytes()
    split = split_fmp4(data)
    assert split.init.startswith(data[:8])
    assert split.init[4:8] == b"ftyp"
    media = b"".join(data[a:b] for a, b in split.media_ranges)
    assert media[4:8] == b"moof"
    assert split.media_size == len(media)
    assert len(split.init) + split.media_size < len(data)  # mfra dropped
    assert b"mfra" not in media
    assert split.video_start() == pytest.approx(11.0, abs=0.001)
    audio = split.audio_start()
    assert audio is not None
    assert 11.0 - 0.08 - 0.03 <= audio <= 11.0
    assert not has_edit_list(split.init)


def test_split_reads_a_memory_map_without_copying_the_media(tmp_path):
    """``_finalize`` maps ``raw_k.mp4``: only the small init is materialized, the media is
    returned as byte ranges of the source."""
    import mmap

    path = tmp_path / "raw.mp4"
    path.write_bytes(fb.raw_segment(25.0, video_cto=1024))
    with open(path, "rb") as fh, mmap.mmap(fh.fileno(), 0, access=mmap.ACCESS_READ) as mm:
        split = split_fmp4(mm)
        assert split.video_start() == pytest.approx(25.0)
        assert isinstance(split.init, bytes) and split.init == fb.init_segment()
        first = split.media_ranges[0][0]
        assert first == len(split.init) and mm[first + 4 : first + 8] == b"moof"
        assert len(split.media_ranges) == 1  # contiguous moof + mdat merged into one range
        end = split.media_ranges[0][1]
        assert mm[end + 4 : end + 8] == b"mfra"  # dropped


def test_split_synthetic_with_composition_offset():
    data = fb.raw_segment(25.0, video_cto=1024)
    split = split_fmp4(data)
    assert split.video_start() == pytest.approx(25.0)
    assert split.audio_start() == pytest.approx(25.0)
    assert split.init == fb.init_segment()


def test_split_rejects_garbage():
    with pytest.raises(Fmp4Error):
        split_fmp4(b"\0\0\0\x10junkjunkjunkjunk")
    with pytest.raises(Fmp4Error):
        split_fmp4(fb.init_segment())  # no fragment


def test_verify_tfdt():
    split = split_fmp4(fb.raw_segment(20.0 + T0))
    assert verify_tfdt(split, expected_start=20.0, frame=FRAME)
    assert verify_tfdt(split, expected_start=20.0 + FRAME * 0.9, frame=FRAME)
    assert not verify_tfdt(split, expected_start=21.0, frame=FRAME)
    relative = split_fmp4(fb.raw_segment(0.05))  # a relative tfdt (missing frag_discont)
    assert not verify_tfdt(relative, expected_start=20.0, frame=FRAME)


def test_init_completeness_of_adts_sources():
    """MPEG-TS (ADTS) source: the first segment of a run has an esds without the
    AudioSpecificConfig; later segments carry it."""
    first = (FIXTURES / "init_ts_first_segment.mp4").read_bytes()
    later = (FIXTURES / "init_ts_later_segment.mp4").read_bytes()
    assert not init_is_complete(first)
    assert init_is_complete(later)
    assert init_is_complete(split_fmp4((FIXTURES / "raw_1_bframes.mp4").read_bytes()).init)
    assert init_is_complete(fb.init_segment())  # no audio esds at all


def test_normalize_init_ignores_bitrate_fields_only():
    later = (FIXTURES / "init_ts_later_segment.mp4").read_bytes()
    i = later.find(b"esds")
    tweaked = bytearray(later)
    # DecoderConfigDescriptor avgBitrate lives inside esds; flip a byte of it via normalize diff
    normalized = normalize_init(later)
    diff = [j for j in range(len(later)) if later[j] != normalized[j]]
    assert diff and all(j > i for j in diff)
    tweaked[diff[0]] ^= 0xFF
    assert normalize_init(bytes(tweaked)) == normalized
    tweaked[40] ^= 0xFF  # anything else still differs
    assert normalize_init(bytes(tweaked)) != normalized
