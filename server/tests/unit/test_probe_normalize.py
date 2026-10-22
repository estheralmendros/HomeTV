import json
from pathlib import Path

import pytest

from hometv.probe import AudioStream, ProbeResult, SubtitleStream, VideoStream, normalize

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures" / "ffprobe"


def load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def test_mkv_hevc_hdr10_with_dovi_side_data():
    r = normalize(load("mkv_hevc_hdr10_dovi.json"))
    assert r == ProbeResult(
        container="mkv",
        duration_seconds=9360.5,
        start_seconds=0.0,
        bitrate=20048000,
        video=VideoStream(
            index=0,
            codec="hevc",
            profile="Main 10",
            width=3840,
            height=2160,
            pix_fmt="yuv420p10le",
            frame_rate=23.976,
            hdr="dolby_vision",
        ),
        audio=[
            AudioStream(
                index=1,
                codec="eac3",
                channels=6,
                channel_layout="5.1(side)",
                language="eng",
                title="Surround",
                default=True,
            ),
            AudioStream(
                index=2,
                codec="ac3",
                channels=2,
                channel_layout="stereo",
                language="spa",
                title=None,
                default=False,
            ),
        ],
        subtitles=[
            SubtitleStream(
                index=3,
                codec="subrip",
                language="spa",
                title="Forzados",
                forced=True,
                hearing_impaired=False,
                default=False,
                kind="text",
            ),
        ],
    )


def test_mp4_h264_aac():
    r = normalize(load("mp4_h264_aac.json"))
    assert r.container == "mp4"
    assert r.duration_seconds == 8160.0
    assert r.bitrate == 3921568
    assert r.video == VideoStream(
        index=0,
        codec="h264",
        profile="High",
        width=1920,
        height=1080,
        pix_fmt="yuv420p",
        frame_rate=25.0,
        hdr=None,
    )
    # two-letter language tags are mapped to ISO 639-2
    assert r.audio == [
        AudioStream(
            index=1,
            codec="aac",
            channels=2,
            channel_layout="stereo",
            language="eng",
            title=None,
            default=True,
        )
    ]
    assert r.subtitles == []


def test_pgs_and_subrip_subtitle_kinds():
    r = normalize(load("mkv_pgs_subrip.json"))
    assert r.container == "mkv"  # h264 + dts is not webm
    assert r.video.frame_rate == 23.976  # avg_frame_rate 0/0 -> r_frame_rate
    assert [(s.index, s.codec, s.kind) for s in r.subtitles] == [
        (2, "hdmv_pgs_subtitle", "image"),
        (3, "subrip", "text"),
        (4, "dvd_subtitle", "image"),
        (5, "ass", "text"),
    ]
    sdh = r.subtitles[1]
    assert (sdh.language, sdh.title, sdh.hearing_impaired, sdh.default) == (
        "spa",
        "SDH",
        True,
        True,
    )
    assert r.subtitles[2].language == "und"
    assert r.subtitles[3].language == "fre"
    assert r.audio[0].channels == 8


def test_attached_pic_is_skipped_and_hlg_detected():
    r = normalize(load("mp4_attached_pic.json"))
    assert r.video.index == 1
    assert r.video.codec == "h264"
    assert r.video.hdr == "hlg"
    assert r.video.frame_rate == 29.97
    assert r.duration_seconds is None  # duration <= 0 stored as null
    assert r.bitrate is None


def test_webm_container_and_negative_start():
    r = normalize(load("webm_vp9_opus.json"))
    assert r.container == "webm"
    assert r.start_seconds == -0.007


def test_mpegts_start_time_and_audio_first():
    r = normalize(load("ts_h264_start10.json"))
    assert r.container == "ts"
    assert r.start_seconds == 10.033333
    assert r.video.index == 1
    assert r.video.frame_rate == 25.0
    assert r.audio[0].language == "und"
    assert r.audio[0].default is False


def test_avi_missing_dispositions_and_start():
    r = normalize(load("avi_xvid.json"))
    assert r.container == "avi"
    assert r.start_seconds == 0.0
    assert r.audio[0].default is False


@pytest.mark.parametrize(
    ("format_name", "vcodec", "acodec", "expected"),
    [
        ("matroska,webm", "vp8", "vorbis", "webm"),
        ("matroska,webm", "av1", "opus", "webm"),
        ("matroska,webm", "vp9", "aac", "mkv"),
        ("matroska,webm", "h264", "opus", "mkv"),
        ("mov,mp4,m4a,3gp,3g2,mj2", "h264", "aac", "mp4"),
        ("mpegts", "h264", "ac3", "ts"),
        ("avi", "mpeg4", "mp3", "avi"),
        ("asf", "wmv3", "wmav2", "asf"),
        ("mpeg", "mpeg2video", "mp2", "mpeg"),
    ],
)
def test_container_mapping(format_name, vcodec, acodec, expected):
    data = {
        "streams": [
            {"index": 0, "codec_type": "video", "codec_name": vcodec},
            {"index": 1, "codec_type": "audio", "codec_name": acodec},
        ],
        "format": {"format_name": format_name},
    }
    assert normalize(data).container == expected


def test_no_video_stream():
    data = {
        "streams": [{"index": 0, "codec_type": "audio", "codec_name": "aac"}],
        "format": {"format_name": "mov,mp4,m4a,3gp,3g2,mj2", "duration": "10"},
    }
    r = normalize(data)
    assert r.video is None
    assert len(r.audio) == 1


@pytest.mark.parametrize(
    ("transfer", "side_data", "expected"),
    [
        ("smpte2084", [], "hdr10"),
        ("arib-std-b67", [], "hlg"),
        ("bt709", [{"side_data_type": "DOVI configuration record"}], "dolby_vision"),
        ("bt709", [{"side_data_type": "Mastering display metadata"}], None),
        (None, None, None),
    ],
)
def test_hdr_detection(transfer, side_data, expected):
    stream = {"index": 0, "codec_type": "video", "codec_name": "hevc"}
    if transfer:
        stream["color_transfer"] = transfer
    if side_data is not None:
        stream["side_data_list"] = side_data
    r = normalize({"streams": [stream], "format": {"format_name": "matroska,webm"}})
    assert r.video.hdr == expected


def test_probe_result_json_round_trip():
    r = normalize(load("mkv_hevc_hdr10_dovi.json"))
    stored = json.loads(json.dumps(r.to_json()))
    assert ProbeResult.from_json(stored) == r
    assert stored["video"]["hdr"] == "dolby_vision"
