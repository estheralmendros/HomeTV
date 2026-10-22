"""Playback decision (PRD §7 Decision). Pure: no I/O."""

from dataclasses import dataclass
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from hometv.probe import AudioStream, ProbeResult

AudioAction = Literal["copy", "aac_stereo", "aac_5_1", "none"]
UnsupportedReason = Literal[
    "probe_failed",
    "no_video_stream",
    "video_codec_unsupported",
    "hls_not_supported",
    "duration_unknown",
    "keyframes_too_sparse",
]
# Audio codecs that may be stream-copied into fMP4 (rule 9).
COPYABLE_AUDIO = frozenset({"aac", "mp3", "ac3", "eac3", "opus", "flac"})


class ClientCaps(BaseModel):
    """What the client can play natively. Names are matched case-insensitively."""

    model_config = ConfigDict(frozen=True)

    containers: list[str]
    video_codecs: list[str]
    audio_codecs: list[str]
    max_audio_channels: int = Field(ge=0)
    hls: bool
    can_select_audio_track: bool = False


@dataclass(frozen=True)
class DirectPlay:
    audio_stream_index: int | None


@dataclass(frozen=True)
class HlsRemux:
    audio_stream_index: int | None
    audio_action: AudioAction


@dataclass(frozen=True)
class Unsupported:
    reason: UnsupportedReason
    detail: str


Decision = DirectPlay | HlsRemux | Unsupported


class InvalidAudioStream(ValueError):
    """The requested audio stream index is not an audio stream of the file (API: 422)."""


def _lower(values: list[str]) -> set[str]:
    return {v.lower() for v in values}


def default_audio_stream(audio: list[AudioStream]) -> AudioStream | None:
    """The stream the file plays by default: default-flagged, else first."""
    for stream in audio:
        if stream.default:
            return stream
    return audio[0] if audio else None


def select_audio(probe: ProbeResult, audio_stream_index: int | None) -> AudioStream | None:
    """Rule 4. Raises ``InvalidAudioStream`` for an index that is not an audio stream."""
    if audio_stream_index is not None:
        for stream in probe.audio:
            if stream.index == audio_stream_index:
                return stream
        raise InvalidAudioStream(f"Audio stream {audio_stream_index} does not exist")
    return default_audio_stream(probe.audio)


def decide(probe: ProbeResult | None, caps: ClientCaps, audio_stream_index: int | None) -> Decision:
    if probe is None:
        return Unsupported("probe_failed", "The file could not be analysed")
    if probe.video is None:
        return Unsupported("no_video_stream", "The file has no video stream")
    video_codec = probe.video.codec.lower()
    if video_codec not in _lower(caps.video_codecs):
        return Unsupported(
            "video_codec_unsupported",
            f"Video codec {probe.video.codec} is not supported by this client",
        )
    audio = select_audio(probe, audio_stream_index)
    is_default_audio = audio is None or audio == default_audio_stream(probe.audio)
    channels = (audio.channels or 0) if audio else 0
    audio_ok = audio is None or (
        audio.codec.lower() in _lower(caps.audio_codecs) and channels <= caps.max_audio_channels
    )
    container_ok = probe.container.lower() in _lower(caps.containers)
    audio_index = audio.index if audio else None

    if container_ok and audio_ok and (is_default_audio or caps.can_select_audio_track):
        return DirectPlay(audio_index)
    if not caps.hls:
        return Unsupported(
            "hls_not_supported", "The file needs a streaming conversion this client cannot play"
        )
    if probe.duration_seconds is None:
        return Unsupported(
            "duration_unknown", "File duration is unknown; it can only be played directly"
        )
    if audio is None:
        return HlsRemux(None, "none")
    if audio_ok and audio.codec.lower() in COPYABLE_AUDIO:
        return HlsRemux(audio_index, "copy")
    if channels > 2 and caps.max_audio_channels >= 6:
        return HlsRemux(audio_index, "aac_5_1")
    return HlsRemux(audio_index, "aac_stereo")
