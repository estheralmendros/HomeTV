"""Video conversion arguments (pure)."""

import pytest

from hometv.playback.transcode import (
    EncoderSettings,
    parse_progress,
    split_progress,
    target_size,
    video_args,
    video_filter,
)
from hometv.probe import VideoStream
from hometv.text import parse_bitrate

ENC = EncoderSettings(preset="ultrafast", crf=23, maxrate_bps=8_000_000)
SCALE_EXPR = (
    "scale='min(iw,1920)':'min(ih,1080)':force_original_aspect_ratio=decrease:force_divisible_by=2"
)
TONEMAP = (
    "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0,"
    "zscale=t=bt709:m=bt709:r=tv,format=yuv420p"
)


def stream(width=1920, height=1080, hdr=None, fps=23.976) -> VideoStream:
    return VideoStream(index=0, codec="av1", width=width, height=height, hdr=hdr, frame_rate=fps)


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("8M", 8_000_000),
        ("8m", 8_000_000),
        ("800k", 800_000),
        ("800K", 800_000),
        ("8000000", 8_000_000),
        ("1.5M", 1_500_000),
        (" 8M ", 8_000_000),
    ],
)
def test_parse_bitrate(value, expected):
    assert parse_bitrate(value) == expected


@pytest.mark.parametrize("value", ["fast", "", "0", "0k", "-1M", "M", "8G", "1e3"])
def test_parse_bitrate_rejects(value):
    with pytest.raises(ValueError):
        parse_bitrate(value)


@pytest.mark.parametrize(
    ("width", "height", "expected"),
    [
        (3840, 2160, (1920, 1080)),
        (1920, 800, None),
        (1440, 1080, None),
        (1920, 1080, None),
        (4096, 1716, (1920, 804)),
        (720, 576, None),
        (1920, 2160, (960, 1080)),
        (3841, 2161, (1918, 1080)),  # odd results are rounded down to even
        (2560, 1440, (1920, 1080)),
        (None, 1080, None),
        (1920, None, None),
        (None, None, None),
    ],
)
def test_target_size(width, height, expected):
    assert target_size(width, height) == expected


def test_video_filter_sdr():
    assert video_filter(stream()) == "format=yuv420p"
    assert video_filter(stream(3840, 2160)) == "scale=1920:1080,format=yuv420p"
    assert video_filter(stream(None, None)) == f"{SCALE_EXPR},format=yuv420p"


@pytest.mark.parametrize("hdr", ["hdr10", "hlg", "dolby_vision"])
def test_video_filter_hdr_tonemaps(hdr):
    assert video_filter(stream(hdr=hdr)) == TONEMAP
    assert video_filter(stream(3840, 2160, hdr=hdr)) == f"scale=1920:1080,{TONEMAP}"
    assert video_filter(stream(None, None, hdr=hdr)) == f"{SCALE_EXPR},{TONEMAP}"


def test_video_args_sdr_exact():
    assert video_args(stream(), ENC) == [
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "23",
        "-maxrate", "8000000", "-bufsize", "16000000",
        "-profile:v", "high", "-pix_fmt", "yuv420p",
        "-vf", "format=yuv420p",
        "-fps_mode", "passthrough", "-force_key_frames", "source",
        "-sc_threshold", "0", "-g", "240",
    ]  # fmt: skip


def test_video_args_hdr_adds_color_flags_and_gop():
    args = video_args(stream(3840, 2160, hdr="hdr10", fps=25.0), ENC)
    assert args[args.index("-vf") + 1] == f"scale=1920:1080,{TONEMAP}"
    assert args[args.index("-g") + 1] == "250"
    assert args[-6:] == [
        "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
    ]  # fmt: skip
    assert "-tag:v" not in args and "-threads" not in args


def test_video_args_unknown_frame_rate_uses_240():
    args = video_args(stream(fps=None), EncoderSettings("veryfast", 18, 800_000))
    assert args[args.index("-g") + 1] == "240"
    assert args[args.index("-preset") + 1] == "veryfast"
    assert args[args.index("-crf") + 1] == "18"
    assert args[args.index("-bufsize") + 1] == "1600000"


PROGRESS = (
    "frame=10\nout_time_us=1000000\nspeed=1.1x\nprogress=continue\n"
    "frame=20\nout_time_us=2500000\nprogress=continue\n"
)


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("", []),
        (PROGRESS, [1.0, 2.5]),
        (PROGRESS + "frame=30\nout_time_us=4000000\n", [1.0, 2.5]),  # partial trailing block
        (PROGRESS + "frame=30\nout_time_us=4000000\nprogress=end\n", [1.0, 2.5, 4.0]),
        ("out_time_us=N/A\nprogress=continue\nout_time_us=500000\nprogress=continue\n", [0.5]),
        ("out_time_us=-250000\nprogress=continue\n", [-0.25]),
        ("frame=1\nprogress=continue\n", []),  # a block without out_time_us
    ],
)
def test_parse_progress(text, expected):
    assert parse_progress(text) == expected


def test_split_progress_keeps_the_partial_block():
    done, partial = split_progress(PROGRESS + "frame=30\nout_time_us=40")
    assert (done, partial) == (PROGRESS, "frame=30\nout_time_us=40")
    assert split_progress("out_time_us=1\n") == ("", "out_time_us=1\n")
    assert split_progress(PROGRESS) == (PROGRESS, "")
