"""Real probe normalization, keyframe readers vs the ffprobe fallback, WebVTT conversion and a
full scan of the generated tree."""

import asyncio
import itertools
import shutil
import subprocess
from pathlib import Path

import pytest

from hometv.db import models as m
from hometv.playback.keyframes import FileInfo, KeyframeService, read_container_index
from hometv.probe import probe
from tests.integration.conftest import CLIPS, CP1252_SIDECAR, PGS_SIDECAR, WEB_CAPS

pytestmark = pytest.mark.integration


def subtitle_packet_times(path: Path) -> list[float]:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "s", "-show_entries", "packet=pts_time",
         "-of", "csv=p=0", str(path)],
        check=True, capture_output=True, text=True,
    ).stdout  # fmt: skip
    return [float(line) for line in out.split()]


EXPECTED = {
    # name: (container, video index, video codec, audio codecs, start ~)
    "h264_aac.mp4": ("mp4", 0, "h264", ["aac"], 0.0),
    "h264_ac3_51.mkv": ("mkv", 0, "h264", ["ac3", "aac"], 0.0),
    "h264_ac3_gop10.mkv": ("mkv", 0, "h264", ["ac3"], 0.0),
    "h264_bframes_editlist.mp4": ("mp4", 0, "h264", ["aac"], 0.0),
    "h264_aac_start10.ts": ("ts", 0, "h264", ["aac"], 11.4),
    "h264_aac_coverart.mp4": ("mp4", 1, "h264", ["aac"], 0.0),
    "h264_aac_opengop.mkv": ("mkv", 0, "h264", ["aac"], 0.0),
    "h264_aac_audiofirst.ts": ("ts", 1, "h264", ["aac"], 1.4),
    "h264_aac_audiotail.mp4": ("mp4", 0, "h264", ["aac"], 0.0),
    "hevc_eac3.mkv": ("mkv", 0, "hevc", ["eac3"], 0.0),
    "vp9_opus.webm": ("webm", 0, "vp9", ["opus"], 0.0),
}


@pytest.mark.parametrize("name", sorted(EXPECTED))
async def test_real_probe_normalization(clips_dir, ffprobe_bin, name):
    container, video_index, codec, audio, start = EXPECTED[name]
    result = await probe(clips_dir / name, ffprobe_path=ffprobe_bin)
    assert result.container == container
    assert result.video is not None
    assert result.video.index == video_index
    assert result.video.codec == codec
    assert [a.codec for a in result.audio] == audio
    assert result.start_seconds == pytest.approx(start, abs=0.1)
    assert result.duration_seconds is not None
    if name == "h264_ac3_51.mkv":
        assert result.audio[0].channels == 6
        assert [(s.codec, s.kind, s.language) for s in result.subtitles] == [
            ("subrip", "text", "spa")
        ]
    if name == "h264_aac_audiotail.mp4":
        assert result.duration_seconds == pytest.approx(22.0, abs=0.1)


def _service(settings, db=None) -> KeyframeService:
    svc = KeyframeService(db, settings, container_reader=lambda info: None)
    svc.sample_interval = 0.2
    return svc


async def _info(clips_dir: Path, name: str, ffprobe_bin: str) -> FileInfo:
    result = await probe(clips_dir / name, ffprobe_path=ffprobe_bin)
    path = clips_dir / name
    return FileInfo(1, str(path), path.stat().st_size, 1, result.container,
                    result.video.index, result.start_seconds)  # fmt: skip


@pytest.mark.parametrize(
    "name",
    [
        "h264_ac3_51.mkv",
        "h264_ac3_gop10.mkv",
        "h264_aac_opengop.mkv",
        "vp9_opus.webm",
        "h264_bframes_editlist.mp4",
        "h264_aac_coverart.mp4",
        "h264_aac.mp4",
    ],
)
async def test_container_reader_matches_ffprobe_fallback(
    clips_dir, ffprobe_bin, make_settings, name
):
    """The Cues/stss reader returns the ffprobe packet-scan keyframes (+-1 ms); the fallback
    argv uses the bare stream index (cover art: stream 1, never the picture)."""
    settings = make_settings(ffmpeg={"ffprobe_path": ffprobe_bin})
    info = await _info(clips_dir, name, ffprobe_bin)
    found = await asyncio.to_thread(read_container_index, info)
    assert found is not None
    source, reader_times = found
    assert source == ("mp4_stss" if name.endswith(".mp4") else "mkv_cues")
    svc = _service(settings)
    argvs = []
    real_spawn = svc.spawn

    async def spawn(argv, **kwargs):
        argvs.append(list(argv))
        return await real_spawn(argv, **kwargs)

    svc.spawn = spawn
    build = svc._start(info)
    svc._store = lambda *a: True  # no DB in this test
    fallback = await svc._ffprobe_times(build)
    build.task.cancel()
    rel_reader = [t - info.start_seconds for t in reader_times]
    rel_fallback = [t - info.start_seconds for t in fallback]
    assert len(rel_reader) == len(rel_fallback)
    for got, want in zip(rel_reader, rel_fallback, strict=True):
        assert got == pytest.approx(want, abs=0.001)
    argv = argvs[0]
    assert argv[argv.index("-select_streams") + 1] == str(info.video_index)
    assert not argv[argv.index("-select_streams") + 1].startswith("0:")
    assert "packet=pts_time,dts_time,flags" in argv
    if name == "h264_aac_coverart.mp4":
        assert info.video_index == 1
        assert len(rel_fallback) == 20  # 1 s GOP over 20 s, not the single cover-art packet


@pytest.mark.parametrize(
    ("name", "gop"), [("h264_aac_start10.ts", 4.0), ("h264_aac_audiofirst.ts", 1.0)]
)
async def test_ts_uses_the_ffprobe_fallback(clips_dir, ffprobe_bin, make_settings, name, gop):
    settings = make_settings(ffmpeg={"ffprobe_path": ffprobe_bin})
    info = await _info(clips_dir, name, ffprobe_bin)
    assert await asyncio.to_thread(read_container_index, info) is None
    svc = _service(settings)
    build = svc._start(info)
    times = await svc._ffprobe_times(build)
    build.task.cancel()
    rel = [t - info.start_seconds for t in times]
    assert rel[0] == pytest.approx(0.0, abs=0.1)
    diffs = {round(b - a, 2) for a, b in itertools.pairwise(rel)}
    assert diffs == {gop}


async def test_webvtt_embedded_and_cp1252_sidecar(make_media_app, clips_dir):
    async with make_media_app() as media:
        client, headers = media.client, media.headers
        fid = media.ids["h264_ac3_51.mkv"]
        detail = (await client.get(f"/api/media-files/{fid}", headers=headers)).json()
        track = next(t for t in detail["subtitle_tracks"] if t["source"] == "embedded")
        assert track["url"] == f"/api/media-files/{fid}/subtitles/emb-3.vtt"
        response = await client.get(track["url"], headers=headers)
        assert response.status_code == 200
        text = response.content.decode("utf-8")
        assert text.startswith("WEBVTT")
        assert "¿qué tal?" in text
        # WebVTT short form (mm:ss.ttt) at the source packet time: 1.000 s as written, but
        # ffmpeg 5.1's matroska muxer shifts the whole clip by the AC3 priming (1.023 s).
        start = subtitle_packet_times(clips_dir / "h264_ac3_51.mkv")[0]
        assert f"00:{start:06.3f} --> 00:{start + 2:06.3f}" in text
        assert abs(start - 1.0) < 0.05
        cache = media.app.state.settings.subtitles_dir / str(fid) / "emb-3.vtt"
        assert cache.is_file()

        sid = media.ids["h264_aac.mp4"]
        detail = (await client.get(f"/api/media-files/{sid}", headers=headers)).json()
        external = [t for t in detail["subtitle_tracks"] if t["source"] == "external"]
        assert len(external) == 1 and external[0]["language"] == "spa"
        response = await client.get(
            external[0]["url"], params={"token": headers["Authorization"][7:]}
        )
        assert response.status_code == 200
        assert response.headers["content-type"] == "text/vtt; charset=utf-8"
        text = response.content.decode("utf-8")  # valid UTF-8
        assert "Café con leche" in text
        assert (clips_dir / CP1252_SIDECAR).read_bytes().decode("cp1252").count("é") == 1


async def test_image_subtitle_tracks_are_422(make_media_app):
    """Image tracks -> 422 (a real PGS track, embedded and as a .sup sidecar)."""
    async with make_media_app() as media:
        client, headers = media.client, media.headers
        fid = media.ids["h264_pgs.mkv"]
        detail = (await client.get(f"/api/media-files/{fid}", headers=headers)).json()
        tracks = {t["source"]: t for t in detail["subtitle_tracks"]}
        assert set(tracks) == {"embedded", "external"}
        assert tracks["embedded"]["codec"] == "hdmv_pgs_subtitle"
        for track in tracks.values():
            assert track["kind"] == "image"
            assert track["url"] is None  # not offered as WebVTT
            url = f"/api/media-files/{fid}/subtitles/{track['id']}.vtt"
            response = await client.get(url, headers=headers)
            assert response.status_code == 422
            assert response.json()["error"]["code"] == "subtitle_not_text"
        assert not (media.app.state.settings.subtitles_dir / str(fid)).exists()


async def test_prefetch_extracts_embedded_tracks_on_playback(make_media_app):
    async with make_media_app() as media:
        fid = media.ids["h264_ac3_51.mkv"]
        response = await media.play("h264_ac3_51.mkv", WEB_CAPS)
        assert response.json()["mode"] == "hls"
        cache = media.app.state.settings.subtitles_dir / str(fid) / "emb-3.vtt"
        for _ in range(100):
            if cache.is_file():
                break
            await asyncio.sleep(0.05)
        assert cache.is_file()


async def test_full_scan_of_the_generated_tree(make_media_app, library_dir):
    async with make_media_app() as media:
        db = media.app.state.db
        with db.read_session() as s:
            rows = {Path(r.path).name: r for r in s.query(m.MediaFile)}
            sidecars = s.query(m.SubtitleFile).all()
        videos = {n for n in CLIPS if n != "h264_ac3_long.mkv"}
        assert set(rows) == videos
        assert all(r.probe_error is None for r in rows.values())
        assert rows["h264_aac_coverart.mp4"].probe["video"]["index"] == 1
        assert rows["hevc_eac3.mkv"].video_codec == "hevc"
        assert sorted(Path(sc.path).name for sc in sidecars) == [CP1252_SIDECAR, PGS_SIDECAR]
        # Background indexer: TS/MKV/WebM files get an index without any playback.
        keyframes = media.app.state.keyframes
        expected = {n for n in videos if n.endswith((".ts", ".mkv", ".webm"))}
        for _ in range(200):
            with db.read_session() as s:
                indexed = {
                    Path(s.get(m.MediaFile, k.media_file_id).path).name: k.source
                    for k in s.query(m.KeyframeIndex)
                }
            if expected <= set(indexed):
                break
            await asyncio.sleep(0.05)
        assert expected <= set(indexed)
        assert indexed["h264_aac_start10.ts"] == "ffprobe"
        assert indexed["h264_ac3_gop10.mkv"] == "mkv_cues"
        assert indexed["vp9_opus.webm"] == "mkv_cues"
        assert not keyframes.failures
        assert shutil.which("ffprobe")
