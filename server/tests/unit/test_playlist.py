"""HLS playlist text from segment plans."""

from hometv.playback.segments import Segment, plan_segments, render_playlist


def test_single_segment():
    text = render_playlist([Segment(0.0, 20.0)])
    assert text == (
        "#EXTM3U\n"
        "#EXT-X-VERSION:7\n"
        "#EXT-X-TARGETDURATION:20\n"
        "#EXT-X-PLAYLIST-TYPE:VOD\n"
        "#EXT-X-INDEPENDENT-SEGMENTS\n"
        '#EXT-X-MAP:URI="init.mp4"\n'
        "#EXTINF:20.000000,\n"
        "seg_0.m4s\n"
        "#EXT-X-ENDLIST\n"
    )


def test_uniform_plan():
    text = render_playlist(plan_segments([float(t) for t in range(0, 24, 6)], 24.0, 6))
    lines = text.splitlines()
    assert lines.count("#EXTINF:6.000000,") == 4
    assert [ln for ln in lines if ln.startswith("seg_")] == [f"seg_{k}.m4s" for k in range(4)]
    assert "#EXT-X-TARGETDURATION:6" in lines


def test_irregular_gop_target_duration_is_ceil_of_max():
    plan = plan_segments([0.0, 2.0, 11.0, 14.0], 26.2, 6)
    lines = render_playlist(plan).splitlines()
    assert "#EXTINF:11.000000," in lines
    assert "#EXTINF:15.200000," in lines
    assert "#EXT-X-TARGETDURATION:16" in lines
    assert lines[-1] == "#EXT-X-ENDLIST"


def test_six_decimal_durations():
    lines = render_playlist([Segment(0.0, 6.006), Segment(6.006, 3.333333333)]).splitlines()
    assert "#EXTINF:6.006000," in lines
    assert "#EXTINF:3.333333," in lines
    assert "#EXT-X-TARGETDURATION:7" in lines
