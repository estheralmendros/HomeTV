"""MediaFile ``label`` (REST API Reference, Shared shapes): pure formatting from the probe."""

import pytest

from hometv.catalog import media_label
from tests.factories import probe_json


def _audio(codec: str, channels: int | None, default: bool = False, index: int = 1) -> dict:
    return {
        "index": index,
        "codec": codec,
        "channels": channels,
        "channel_layout": None,
        "language": "eng",
        "title": None,
        "default": default,
    }


@pytest.mark.parametrize(
    ("probe", "expected"),
    [
        (
            probe_json(
                codec="hevc", width=3840, height=2160, hdr="hdr10", audio=[_audio("eac3", 6, True)]
            ),
            "2160p HEVC HDR10 · EAC3 5.1",
        ),
        (probe_json(), "1080p H264 · AAC 2.0"),
        (probe_json(hdr="dolby_vision", height=2160), "2160p H264 DV · AAC 2.0"),
        (probe_json(hdr="hlg"), "1080p H264 HLG · AAC 2.0"),
        (probe_json(audio=[_audio("truehd", 8)]), "1080p H264 · TRUEHD 7.1"),
        (probe_json(audio=[_audio("mp3", 1)]), "1080p H264 · MP3 1.0"),
        # Default-flagged track wins, else the first.
        (
            probe_json(audio=[_audio("ac3", 6, index=1), _audio("aac", 2, True, index=2)]),
            "1080p H264 · AAC 2.0",
        ),
        (
            probe_json(audio=[_audio("ac3", 6, index=1), _audio("aac", 2, index=2)]),
            "1080p H264 · AC3 5.1",
        ),
        (probe_json(audio=[]), "1080p H264"),
        (probe_json(audio=[_audio("dts", None)]), "1080p H264 · DTS"),
        (probe_json(height=None, width=None), "H264 · AAC 2.0"),
        ({**probe_json(), "video": None}, "AAC 2.0"),
        ({**probe_json(audio=[]), "video": None}, "Desconocido"),
        (None, "Desconocido"),
    ],
)
def test_media_label(probe, expected):
    assert media_label(probe) == expected
