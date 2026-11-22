"""Video conversion arguments for an HLS session. Pure: no I/O.

The converted video keeps the source's frames, timestamps and keyframes
(``-fps_mode passthrough``, ``-force_key_frames source``), so the segment plan, the segment
checks and the restart rules of the remux path apply unchanged.
"""

import re
from dataclasses import dataclass

from hometv.probe import VideoStream

MAX_WIDTH = 1920
MAX_HEIGHT = 1080
TONEMAP_HDR = frozenset({"hdr10", "hlg", "dolby_vision"})
DEFAULT_GOP = 240

_SCALE_EXPR = (
    f"scale='min(iw,{MAX_WIDTH})':'min(ih,{MAX_HEIGHT})'"
    ":force_original_aspect_ratio=decrease:force_divisible_by=2"
)
_TONEMAP = (
    "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0,"
    "zscale=t=bt709:m=bt709:r=tv,format=yuv420p"
)


@dataclass(frozen=True)
class EncoderSettings:
    preset: str
    crf: int
    maxrate_bps: int


_BLOCK_END = re.compile(r"^progress=(?:continue|end)[ \t]*\r?\n", re.MULTILINE)
_OUT_TIME = re.compile(r"^out_time_us=(-?\d+)[ \t]*$", re.MULTILINE)


def split_progress(text: str) -> tuple[str, str]:
    """``(complete blocks, partial trailing block)`` of ffmpeg ``-progress`` output."""
    end = 0
    for match in _BLOCK_END.finditer(text):
        end = match.end()
    return text[:end], text[end:]


def parse_progress(text: str) -> list[float]:
    """``out_time_us`` (seconds) of each complete ``progress=continue|end`` block in ``text``.

    A trailing partial block is ignored (see ``split_progress``); a block without a numeric
    ``out_time_us`` (``N/A`` before the first output) yields nothing."""
    values: list[float] = []
    start = 0
    for match in _BLOCK_END.finditer(text):  # only complete blocks end in a match
        found = _OUT_TIME.findall(text[start : match.start()])
        if found:
            values.append(int(found[-1]) / 1_000_000)
        start = match.end()
    return values


def target_size(width: int | None, height: int | None) -> tuple[int, int] | None:
    """The size inside 1920x1080 (same aspect, even sides, never upscaled), or ``None``."""
    if not width or not height or (width <= MAX_WIDTH and height <= MAX_HEIGHT):
        return None
    if width * MAX_HEIGHT > height * MAX_WIDTH:  # width-bound
        new_w, new_h = MAX_WIDTH, height * MAX_WIDTH // width
    else:
        new_w, new_h = width * MAX_HEIGHT // height, MAX_HEIGHT
    return new_w - new_w % 2, new_h - new_h % 2


def video_filter(video: VideoStream) -> str:
    """The ``-vf`` chain: downscale first, then SDR conversion or HDR tone-mapping."""
    steps: list[str] = []
    if video.width is None or video.height is None:
        steps.append(_SCALE_EXPR)
    else:
        size = target_size(video.width, video.height)
        if size is not None:
            steps.append(f"scale={size[0]}:{size[1]}")
    steps.append(_TONEMAP if video.hdr in TONEMAP_HDR else "format=yuv420p")
    return ",".join(steps)


def video_args(video: VideoStream, enc: EncoderSettings) -> list[str]:
    gop = round(10 * video.frame_rate) if video.frame_rate else DEFAULT_GOP
    args = [
        "-c:v", "libx264", "-preset", enc.preset, "-crf", str(enc.crf),
        "-maxrate", str(enc.maxrate_bps), "-bufsize", str(2 * enc.maxrate_bps),
        "-profile:v", "high", "-pix_fmt", "yuv420p",
        "-vf", video_filter(video),
        "-fps_mode", "passthrough", "-force_key_frames", "source",
        "-sc_threshold", "0", "-g", str(gop),
    ]  # fmt: skip
    if video.hdr in TONEMAP_HDR:
        args += ["-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709"]
    return args
