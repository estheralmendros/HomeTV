"""PRD §7 playback decision table (17 rows x web/TV caps) + edge cases."""

from typing import Any

import pytest

from hometv.playback.decide import (
    ClientCaps,
    DirectPlay,
    HlsRemux,
    InvalidAudioStream,
    Unsupported,
    decide,
)
from hometv.probe import AudioStream, ProbeResult, VideoStream

WEB = ClientCaps(
    containers=["mp4", "webm"],
    video_codecs=["h264", "vp9", "av1"],
    audio_codecs=["aac", "mp3", "opus", "flac", "vorbis"],
    max_audio_channels=6,
    hls=True,
    can_select_audio_track=False,
)
TV = ClientCaps(
    containers=["mp4", "mkv", "webm", "ts"],
    video_codecs=["h264", "hevc", "vp9"],
    audio_codecs=["aac", "ac3", "eac3", "mp3", "opus", "vorbis", "flac"],
    max_audio_channels=6,
    hls=True,
    can_select_audio_track=True,
)


def _probe(
    container: str,
    video: str | None,
    audio: list[tuple[str, int]] | None = None,
    duration: float | None = 1200.0,
    default_index: int | None = None,
) -> ProbeResult:
    streams = []
    for i, (codec, channels) in enumerate(audio or []):
        streams.append(
            AudioStream(index=i + 1, codec=codec, channels=channels, default=default_index == i + 1)
        )
    return ProbeResult(
        container=container,
        duration_seconds=duration,
        video=VideoStream(index=0, codec=video) if video else None,
        audio=streams,
    )


D = DirectPlay


def H(action: str) -> Any:
    return ("hls", action)


def U(reason: str) -> Any:
    return ("unsupported", reason)


def _norm(result: Any) -> Any:
    if isinstance(result, DirectPlay):
        return D
    if isinstance(result, HlsRemux):
        return H(result.audio_action)
    assert isinstance(result, Unsupported)
    return U(result.reason)


# row: (probe, requested audio index, web caps override, web expected, tv expected)
ROWS: dict[int, tuple[ProbeResult | None, int | None, dict[str, Any], Any, Any]] = {
    1: (_probe("mp4", "h264", [("aac", 2)]), None, {}, D, D),
    2: (_probe("mkv", "h264", [("aac", 2)]), None, {}, H("copy"), D),
    3: (_probe("mkv", "h264", [("ac3", 6)]), None, {}, H("aac_5_1"), D),
    4: (_probe("mkv", "hevc", [("eac3", 6)]), None, {}, U("video_codec_unsupported"), D),
    5: (_probe("mkv", "h264", [("dts", 6)]), None, {}, H("aac_5_1"), H("aac_5_1")),
    6: (_probe("mkv", "h264", [("truehd", 8)]), None, {}, H("aac_5_1"), H("aac_5_1")),
    7: (_probe("mp4", "h264", [("aac", 8)]), None, {}, H("aac_5_1"), H("aac_5_1")),
    8: (_probe("webm", "vp9", [("opus", 2)]), None, {}, D, D),
    9: (
        _probe("avi", "mpeg4", [("mp3", 2)]),
        None,
        {},
        U("video_codec_unsupported"),
        U("video_codec_unsupported"),
    ),
    10: (_probe("mkv", "av1", [("opus", 2)]), None, {}, H("copy"), U("video_codec_unsupported")),
    11: (_probe("mp4", "h264", [("aac", 2), ("aac", 2)]), 2, {}, H("copy"), D),
    12: (_probe("mp4", "h264", []), None, {}, D, D),
    13: (_probe("ts", "h264", [("aac", 2)]), None, {}, H("copy"), D),
    14: (
        _probe("mkv", "vc1", [("ac3", 6)]),
        None,
        {},
        U("video_codec_unsupported"),
        U("video_codec_unsupported"),
    ),
    15: (_probe("mkv", "h264", [("aac", 2)]), None, {"hls": False}, U("hls_not_supported"), D),
    16: (None, None, {}, U("probe_failed"), U("probe_failed")),
    17: (
        _probe("mkv", "h264", [("aac", 2)], duration=None),
        None,
        {},
        U("duration_unknown"),
        D,
    ),
}


@pytest.mark.parametrize("row", sorted(ROWS))
def test_decision_table(row: int) -> None:
    probe, audio_index, web_overrides, web_expected, tv_expected = ROWS[row]
    web = WEB.model_copy(update=web_overrides)
    tv = TV.model_copy(update=web_overrides)
    assert _norm(decide(probe, web, audio_index)) == web_expected
    assert _norm(decide(probe, tv, audio_index)) == tv_expected


def test_video_codec_detail_message() -> None:
    result = decide(_probe("mkv", "hevc", [("eac3", 6)]), WEB, None)
    assert result == Unsupported(
        "video_codec_unsupported", "Video codec hevc is not supported by this client"
    )


def test_duration_unknown_detail() -> None:
    result = decide(_probe("mkv", "h264", [("aac", 2)], duration=None), WEB, None)
    assert isinstance(result, Unsupported)
    assert result.detail == "File duration is unknown; it can only be played directly"


def test_no_video_stream() -> None:
    assert _norm(decide(_probe("mkv", None, [("aac", 2)]), TV, None)) == U("no_video_stream")


def test_probe_with_error_marker_is_probe_failed() -> None:
    assert _norm(decide(None, TV, 1)) == U("probe_failed")


def test_invalid_audio_index_raises() -> None:
    with pytest.raises(InvalidAudioStream):
        decide(_probe("mp4", "h264", [("aac", 2)]), WEB, 7)
    with pytest.raises(InvalidAudioStream):
        decide(_probe("mp4", "h264", [("aac", 2)]), WEB, 0)  # the video stream


def test_max_audio_channels_boundary() -> None:
    six = _probe("mp4", "h264", [("aac", 6)])
    eight = _probe("mp4", "h264", [("aac", 8)])
    assert decide(six, WEB, None) == DirectPlay(1)
    assert _norm(decide(eight, WEB, None)) == H("aac_5_1")
    stereo_only = WEB.model_copy(update={"max_audio_channels": 2})
    assert _norm(decide(six, stereo_only, None)) == H("aac_stereo")


def test_default_flag_selects_audio() -> None:
    probe = _probe("mp4", "h264", [("aac", 2), ("aac", 2)], default_index=2)
    assert decide(probe, WEB, None) == DirectPlay(2)
    # Requesting the default-flagged track is still the default track.
    assert decide(probe, WEB, 2) == DirectPlay(2)
    # The first track is not the default here -> needs a remux on web.
    assert decide(probe, WEB, 1) == HlsRemux(1, "copy")


def test_unsupported_audio_codec_copy_vs_transcode() -> None:
    # vorbis is fine for the client but cannot be copied into fMP4 by our rule -> aac_stereo.
    probe = _probe("mkv", "h264", [("vorbis", 2)])
    assert decide(probe, WEB, None) == HlsRemux(1, "aac_stereo")
    # pcm in an mkv for a stereo source -> aac_stereo
    assert decide(_probe("mkv", "h264", [("pcm_s16le", 2)]), WEB, None) == HlsRemux(1, "aac_stereo")


def test_no_audio_hls_action_none() -> None:
    assert decide(_probe("mkv", "h264", []), WEB, None) == HlsRemux(None, "none")


def test_case_insensitive_matching() -> None:
    caps = WEB.model_copy(update={"containers": ["MP4"], "video_codecs": ["H264"]})
    assert decide(_probe("mp4", "h264", [("aac", 2)]), caps, None) == DirectPlay(1)
