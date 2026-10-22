"""Integration fixtures: clips generated with ffmpeg lavfi sources once per session (in parallel).

Every test in this package must be marked ``integration``; the root conftest skips them when
ffmpeg/ffprobe are missing. ``CLIPS`` maps a file name to its ffmpeg arguments (inputs
included). Inputs: ``testsrc2`` video + ``sine`` audio, 20 s, 1 s GOP unless noted (PRD Testing
Decisions). Extra files: ``h264_aac.es.srt`` (CP1252 sidecar of ``h264_aac.mp4``),
``h264_pgs.en.sup`` (PGS sidecar of ``h264_pgs.mkv``, which also embeds a PGS track) and
``h264_ac3_long.mkv`` (300 s, for the read-ahead throttle AC: its AAC 5.1 re-encode is slow
enough to observe a paused ffmpeg).
"""

import os
import shutil
import struct
import subprocess
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest

from tests.conftest import FFMPEG


def _video(duration: int = 20, size: str = "320x240") -> list[str]:
    return ["-f", "lavfi", "-i", f"testsrc2=size={size}:rate=25:duration={duration}"]


def _audio(duration: int = 20) -> list[str]:
    return ["-f", "lavfi", "-i", f"sine=frequency=440:duration={duration}"]


X264 = ["-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p"]
SRT_INPUT = "subs_input.srt"
SUP_INPUT = "subs_input.sup"

CLIPS: dict[str, list[str]] = {
    "h264_aac.mp4": [*_video(), *_audio(), *X264, "-g", "25", "-c:a", "aac"],
    "h264_ac3_51.mkv": [
        *_video(), *_audio(), "-i", "{dir}/" + SRT_INPUT,
        "-map", "0:v", "-map", "1:a", "-map", "1:a", "-map", "2:s",
        *X264, "-g", "25",
        "-c:a:0", "ac3", "-ac:a:0", "6", "-c:a:1", "aac", "-ac:a:1", "2",
        "-c:s", "srt", "-metadata:s:s:0", "language=spa",
    ],
    "h264_ac3_gop10.mkv": [
        *_video(60), *_audio(60), *X264,
        "-g", "250", "-keyint_min", "250", "-sc_threshold", "0", "-c:a", "ac3", "-ac", "6",
    ],
    "h264_bframes_editlist.mp4": [
        *_video(), *_audio(), *X264, "-g", "25", "-bf", "3", "-c:a", "aac",
    ],
    "h264_aac_start10.ts": [
        *_video(60), *_audio(60), *X264,
        "-g", "100", "-keyint_min", "100", "-sc_threshold", "0", "-c:a", "aac",
        "-output_ts_offset", "10",
    ],
    "h264_aac_coverart.mp4": [
        "-f", "lavfi", "-i", "color=c=red:s=64x64:d=0.04", *_video(), *_audio(),
        "-map", "0:v", "-map", "1:v", "-map", "2:a",
        "-c:v:0", "png", "-disposition:v:0", "attached_pic",
        "-c:v:1", "libx264", "-preset", "veryfast", "-pix_fmt:v:1", "yuv420p", "-g", "25",
        "-c:a", "aac",
    ],
    "h264_aac_opengop.mkv": [
        *_video(60), *_audio(60), *X264,
        "-x264-params", "open-gop=1:bframes=3", "-g", "96", "-c:a", "aac",
    ],
    "h264_aac_audiofirst.ts": [
        *_video(), *_audio(), "-map", "1:a", "-map", "0:v", *X264, "-g", "25", "-c:a", "aac",
    ],
    "h264_aac_audiotail.mp4": [*_video(20), *_audio(22), *X264, "-g", "25", "-c:a", "aac"],
    "hevc_eac3.mkv": [
        *_video(), *_audio(), "-c:v", "libx265", "-preset", "ultrafast",
        "-x265-params", "log-level=error", "-pix_fmt", "yuv420p", "-c:a", "eac3",
    ],
    "vp9_opus.webm": [
        *_video(), *_audio(), "-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8",
        "-g", "25", "-c:a", "libopus",
    ],
    "h264_pgs.mkv": [
        *_video(), "-i", "{dir}/" + SUP_INPUT, "-map", "0:v", "-map", "1:s",
        *X264, "-g", "25", "-c:s", "copy", "-metadata:s:s:0", "language=eng",
    ],
    "h264_ac3_long.mkv": [
        *_video(300, "160x120"), *_audio(300), "-c:v", "libx264", "-preset", "ultrafast",
        "-pix_fmt", "yuv420p", "-g", "50", "-c:a", "ac3", "-ac", "6",
    ],
}  # fmt: skip

SUBTITLE_TEXT = (
    "1\n00:00:01,000 --> 00:00:03,000\nHola, ¿qué tal?\n\n2\n00:00:05,000 --> 00:00:07,000\nAdiós\n"
)
CP1252_SIDECAR = "h264_aac.es.srt"
CP1252_TEXT = "1\n00:00:01,000 --> 00:00:02,500\nCafé con leche\n"
PGS_SIDECAR = "h264_pgs.en.sup"


def pgs_bytes() -> bytes:
    """A minimal valid PGS (.sup) stream: a 16x16 bitmap shown at 1 s, cleared at 3 s
    (lavfi cannot produce bitmap subtitles, and ffmpeg cannot encode text to bitmap)."""

    def seg(pts: float, kind: int, payload: bytes) -> bytes:
        return b"PG" + struct.pack(">IIBH", int(pts * 90000), 0, kind, len(payload)) + payload

    def pcs(number: int, state: int, objects: bytes) -> bytes:
        count = len(objects) // 8
        return struct.pack(">HHBHBBBB", 320, 240, 0x10, number, state, 0, 0, count) + objects

    window = struct.pack(">BBHHHH", 1, 0, 0, 0, 16, 16)
    palette = bytes([0, 0, 0, 16, 128, 128, 0, 1, 235, 128, 128, 255])
    rle = (bytes([0x00, 0x80 | 16, 1]) + b"\x00\x00") * 16  # 16 lines of 16 pixels, color 1
    obj = struct.pack(">HBB", 0, 0, 0xC0) + (len(rle) + 4).to_bytes(3, "big")
    obj += struct.pack(">HH", 16, 16) + rle
    show = pcs(0, 0x80, struct.pack(">HBBHH", 0, 0, 0, 10, 10))
    out = seg(1.0, 0x16, show) + seg(1.0, 0x17, window) + seg(1.0, 0x14, palette)
    out += seg(1.0, 0x15, obj) + seg(1.0, 0x80, b"")
    out += seg(3.0, 0x16, pcs(1, 0x00, b"")) + seg(3.0, 0x17, window) + seg(3.0, 0x80, b"")
    return out


def _cover_art_first(path: Path) -> None:
    """Move ``udta`` (with the ``covr`` cover art) before the first ``trak`` inside ``moov``,
    so ffprobe lists the attached picture as stream 0 (the mp4 muxer always writes it last).
    The moov keeps its size and position, so chunk offsets stay valid."""
    data = bytearray(path.read_bytes())

    def boxes(start: int, end: int):
        pos = start
        while pos < end:
            size, kind = struct.unpack_from(">I4s", data, pos)
            yield kind, pos, pos + size
            pos += size

    for kind, start, end in boxes(0, len(data)):
        if kind == b"moov":
            kids = list(boxes(start + 8, end))
            order = [k for k in kids if k[0] == b"mvhd"] + [k for k in kids if k[0] == b"udta"]
            order += [k for k in kids if k[0] not in (b"mvhd", b"udta")]
            data[start + 8 : end] = b"".join(bytes(data[a:b]) for _k, a, b in order)
    path.write_bytes(bytes(data))


def _generate(ffmpeg: str, out: Path, name: str, args: list[str]) -> None:
    cmd = [ffmpeg, "-hide_banner", "-loglevel", "error", "-y"]
    cmd += [a.replace("{dir}", str(out)) for a in args]
    subprocess.run([*cmd, str(out / name)], check=True, timeout=300)
    if name == "h264_aac_coverart.mp4":
        _cover_art_first(out / name)


@pytest.fixture(scope="session")
def clips_dir(tmp_path_factory: pytest.TempPathFactory) -> Path:
    ffmpeg = shutil.which(FFMPEG)
    if ffmpeg is None:
        pytest.skip("ffmpeg not installed")
    out = tmp_path_factory.mktemp("clips")
    inputs = tmp_path_factory.mktemp("clip-inputs")
    (inputs / SRT_INPUT).write_text(SUBTITLE_TEXT, encoding="utf-8")
    (inputs / SUP_INPUT).write_bytes(pgs_bytes())
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = [
            pool.submit(
                _generate, ffmpeg, out, name, [a.replace("{dir}", str(inputs)) for a in args]
            )
            for name, args in CLIPS.items()
        ]
        for future in futures:
            future.result()
    (out / CP1252_SIDECAR).write_bytes(CP1252_TEXT.encode("cp1252"))
    (out / PGS_SIDECAR).write_bytes(pgs_bytes())
    return out


@pytest.fixture(scope="session")
def library_dir(clips_dir: Path, tmp_path_factory: pytest.TempPathFactory) -> Path:
    """A films root with every short clip (the 300 s clip excluded) plus the sidecar."""
    root = tmp_path_factory.mktemp("library")
    for entry in clips_dir.iterdir():
        if entry.name != "h264_ac3_long.mkv":
            link_or_copy(entry, root / entry.name)
    return root


def link_or_copy(src: Path, dst: Path) -> None:
    """Hard link (the scanner skips symlinks pointing outside the media roots)."""
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


class MediaApp:
    """A running app whose films root is the generated library, scanned with real ffprobe."""

    def __init__(self, app, client, ids: dict[str, int], headers: dict[str, str]) -> None:
        self.app = app
        self.client = client
        self.ids = ids
        self.headers = headers

    @property
    def hls(self):
        return self.app.state.hls

    async def play(self, name: str, caps: dict, **body):
        return await self.client.post(
            f"/api/media-files/{self.ids[name]}/playback",
            json={"client_caps": caps, **body},
            headers=self.headers,
        )


WEB_CAPS = {
    "containers": ["mp4", "webm"],
    "video_codecs": ["h264", "vp9", "av1"],
    "audio_codecs": ["aac", "mp3", "opus", "flac", "vorbis"],
    "max_audio_channels": 6,
    "hls": True,
    "can_select_audio_track": False,
}
FORCE_HLS_CAPS = {**WEB_CAPS, "containers": ["webm"]}


@pytest.fixture
def make_media_app(make_settings, library_dir, clips_dir):
    """``async with make_media_app(**overrides) as media:`` (scan done, indexes built)."""
    import contextlib

    import httpx

    from hometv import profiles as p
    from hometv.db import models as m
    from hometv.main import create_app
    from hometv.scanner import Scanner

    @contextlib.asynccontextmanager
    async def factory(root: Path | None = None, **overrides):
        settings = make_settings(
            libraries={"films": [str(root or library_dir)]},
            ffmpeg={"ffmpeg_path": shutil.which(FFMPEG), "ffprobe_path": shutil.which("ffprobe")},
            **overrides,
        )
        app = create_app(settings)
        async with app.router.lifespan_context(app):
            db = app.state.db
            await Scanner(db, app.state.settings, keyframe_hook=app.state.keyframes.enqueue).run()
            with db.read_session() as s:
                ids = {Path(r.path).name: r.id for r in s.query(m.MediaFile)}
            profile = p.create_profile(
                db, name="Tester", avatar_id="avatar-01", is_admin=False, pin=None
            )
            token = app.state.tokens.issue_profile_token(profile.id).token
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
                yield MediaApp(app, client, ids, {"Authorization": f"Bearer {token}"})

    return factory
