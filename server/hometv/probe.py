"""ffprobe wrapper and normalization to ProbeResult.

- ``normalize(ffprobe_json)`` is pure.
- ``probe(path)`` runs ffprobe through an injectable ``runner`` (default ``run_subprocess``,
  ``asyncio.create_subprocess_exec`` with an argument list, never a shell, with a timeout).
"""

import asyncio
import contextlib
import json
from collections.abc import Awaitable, Callable, Sequence
from os import PathLike
from typing import Any, Literal

from babelfish import Error as BabelfishError
from babelfish import Language
from pydantic import BaseModel, ConfigDict

PROBE_TIMEOUT_SECONDS = 60.0
TEXT_SUBTITLE_CODECS = frozenset({"subrip", "srt", "ass", "ssa", "mov_text", "webvtt", "text"})
_WEBM_VIDEO = frozenset({"vp8", "vp9", "av1"})
_WEBM_AUDIO = frozenset({"opus", "vorbis"})

HdrKind = Literal["hdr10", "hlg", "dolby_vision"]

# (args, timeout_seconds) -> (returncode, stdout, stderr)
Runner = Callable[[Sequence[str], float], Awaitable[tuple[int, bytes, bytes]]]


class ProbeError(Exception):
    """ffprobe failed (non-zero exit, timeout, missing binary or unusable output)."""


class _Model(BaseModel):
    model_config = ConfigDict(frozen=True)


class VideoStream(_Model):
    index: int
    codec: str
    profile: str | None = None
    width: int | None = None
    height: int | None = None
    pix_fmt: str | None = None
    frame_rate: float | None = None
    hdr: HdrKind | None = None


class AudioStream(_Model):
    index: int
    codec: str
    channels: int | None = None
    channel_layout: str | None = None
    language: str = "und"
    title: str | None = None
    default: bool = False


class SubtitleStream(_Model):
    index: int
    codec: str
    language: str = "und"
    title: str | None = None
    forced: bool = False
    hearing_impaired: bool = False
    default: bool = False
    kind: Literal["text", "image"]


class ProbeResult(_Model):
    container: str
    duration_seconds: float | None = None
    start_seconds: float = 0.0
    bitrate: int | None = None
    video: VideoStream | None = None
    audio: list[AudioStream] = []
    subtitles: list[SubtitleStream] = []

    def to_json(self) -> dict[str, Any]:
        """Shape stored in ``media_file.probe``."""
        return self.model_dump(mode="json")

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> "ProbeResult":
        return cls.model_validate(data)


# --- Normalization (pure) ------------------------------------------------------------------


def normalize(data: dict[str, Any]) -> ProbeResult:
    fmt = data.get("format")
    if not isinstance(fmt, dict):
        raise ProbeError("invalid ffprobe output: missing format")
    streams = [s for s in data.get("streams") or [] if isinstance(s, dict)]

    videos = [
        s for s in streams if s.get("codec_type") == "video" and not _disposition(s, "attached_pic")
    ]
    audios = [s for s in streams if s.get("codec_type") == "audio"]
    subs = [s for s in streams if s.get("codec_type") == "subtitle"]

    duration = _float(fmt.get("duration"))
    return ProbeResult(
        container=_container(str(fmt.get("format_name") or ""), videos, audios),
        duration_seconds=duration if duration is not None and duration > 0 else None,
        start_seconds=_float(fmt.get("start_time")) or 0.0,
        bitrate=_int(fmt.get("bit_rate")),
        video=_video(videos[0]) if videos else None,
        audio=[_audio(s) for s in audios],
        subtitles=[_subtitle(s) for s in subs],
    )


def _container(format_name: str, videos: list[dict], audios: list[dict]) -> str:
    tokens = [t.strip() for t in format_name.split(",") if t.strip()]
    if "matroska" in format_name:
        vcodecs = {_codec(s) for s in videos}
        acodecs = {_codec(s) for s in audios}
        if vcodecs <= _WEBM_VIDEO and acodecs <= _WEBM_AUDIO:
            return "webm"
        return "mkv"
    if "mp4" in tokens or (tokens and tokens[0] == "mov"):
        return "mp4"
    if "avi" in tokens:
        return "avi"
    if "mpegts" in tokens:
        return "ts"
    return tokens[0] if tokens else "unknown"


def _video(s: dict[str, Any]) -> VideoStream:
    return VideoStream(
        index=int(s["index"]),
        codec=_codec(s),
        profile=s.get("profile"),
        width=_int(s.get("width")),
        height=_int(s.get("height")),
        pix_fmt=s.get("pix_fmt"),
        frame_rate=_frame_rate(s.get("avg_frame_rate")) or _frame_rate(s.get("r_frame_rate")),
        hdr=_hdr(s),
    )


def _audio(s: dict[str, Any]) -> AudioStream:
    return AudioStream(
        index=int(s["index"]),
        codec=_codec(s),
        channels=_int(s.get("channels")),
        channel_layout=s.get("channel_layout"),
        language=_language(_tag(s, "language")),
        title=_tag(s, "title"),
        default=_disposition(s, "default"),
    )


def _subtitle(s: dict[str, Any]) -> SubtitleStream:
    codec = _codec(s)
    return SubtitleStream(
        index=int(s["index"]),
        codec=codec,
        language=_language(_tag(s, "language")),
        title=_tag(s, "title"),
        forced=_disposition(s, "forced"),
        hearing_impaired=_disposition(s, "hearing_impaired"),
        default=_disposition(s, "default"),
        kind="text" if codec in TEXT_SUBTITLE_CODECS else "image",
    )


def _hdr(s: dict[str, Any]) -> HdrKind | None:
    for side in s.get("side_data_list") or []:
        if isinstance(side, dict) and "dovi" in str(side.get("side_data_type", "")).lower():
            return "dolby_vision"
    transfer = s.get("color_transfer")
    if transfer == "smpte2084":
        return "hdr10"
    if transfer == "arib-std-b67":
        return "hlg"
    return None


def _codec(s: dict[str, Any]) -> str:
    return str(s.get("codec_name") or "unknown")


def _disposition(s: dict[str, Any], key: str) -> bool:
    disposition = s.get("disposition") or {}
    return bool(disposition.get(key, 0))


def _tag(s: dict[str, Any], key: str) -> str | None:
    for k, v in (s.get("tags") or {}).items():
        if k.lower() == key and isinstance(v, str) and v.strip():
            return v.strip()
    return None


def _language(value: str | None) -> str:
    """ISO 639-2 code, or ``und``."""
    if not value:
        return "und"
    code = value.strip().lower()
    if len(code) == 3 and code.isalpha():
        return code
    try:
        lang = Language.fromietf(value.strip())
    except (BabelfishError, ValueError, KeyError):
        return "und"
    try:
        return lang.alpha3b
    except BabelfishError:
        return lang.alpha3


def _float(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _int(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _frame_rate(value: Any) -> float | None:
    if not isinstance(value, str) or "/" not in value:
        return None
    num, _, den = value.partition("/")
    try:
        n, d = float(num), float(den)
    except ValueError:
        return None
    if n <= 0 or d <= 0:
        return None
    return round(n / d, 3)


# --- Subprocess ----------------------------------------------------------------------------


async def run_subprocess(args: Sequence[str], timeout: float) -> tuple[int, bytes, bytes]:
    """Run ``args`` (no shell), draining stdout/stderr; kill the process on timeout.

    Raises ``TimeoutError`` on timeout and ``FileNotFoundError`` if the binary is missing.
    """
    proc = await asyncio.create_subprocess_exec(
        *args,
        stdin=asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    try:
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout)
    except BaseException:
        with contextlib.suppress(ProcessLookupError):
            proc.kill()
        with contextlib.suppress(Exception):
            await proc.wait()
        raise
    assert proc.returncode is not None
    return proc.returncode, stdout, stderr


async def probe(
    path: str | PathLike[str],
    *,
    ffprobe_path: str = "ffprobe",
    timeout: float = PROBE_TIMEOUT_SECONDS,
    runner: Runner | None = None,
) -> ProbeResult:
    run = runner or run_subprocess
    args = [
        ffprobe_path,
        "-v",
        "error",
        "-print_format",
        "json",
        "-show_format",
        "-show_streams",
        str(path),
    ]
    try:
        code, stdout, stderr = await run(args, timeout)
    except TimeoutError as exc:
        raise ProbeError(f"ffprobe timed out after {timeout:g}s") from exc
    except FileNotFoundError as exc:
        raise ProbeError(f"ffprobe not found: {ffprobe_path}") from exc
    except OSError as exc:
        raise ProbeError(f"ffprobe could not be started: {exc}") from exc
    if code != 0:
        lines = [ln for ln in stderr.decode("utf-8", "replace").splitlines() if ln.strip()]
        raise ProbeError(lines[-1].strip() if lines else f"ffprobe exited with code {code}")
    try:
        data = json.loads(stdout.decode("utf-8", "replace"))
    except json.JSONDecodeError as exc:
        raise ProbeError("invalid ffprobe output: not JSON") from exc
    if not isinstance(data, dict):
        raise ProbeError("invalid ffprobe output: not an object")
    return normalize(data)
