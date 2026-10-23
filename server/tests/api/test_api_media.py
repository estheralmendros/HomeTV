"""Media & playback API (PRD REST "Media & playback", §7 ACs). ffmpeg/ffprobe are stubbed."""

import asyncio
import time
from pathlib import Path

import pytest

from hometv.db import models as m
from hometv.playback.keyframes import encode_times
from tests import factories as f
from tests.hls_stub import PlanFfmpeg
from tests.stubs import FakeProc, Spawner

WEB = {
    "containers": ["mp4", "webm"],
    "video_codecs": ["h264", "vp9", "av1"],
    "audio_codecs": ["aac", "mp3", "opus", "flac", "vorbis"],
    "max_audio_channels": 6,
    "hls": True,
    "can_select_audio_track": False,
}
TV = {
    "containers": ["mp4", "mkv", "webm", "ts"],
    "video_codecs": ["h264", "hevc", "vp9"],
    "audio_codecs": ["aac", "ac3", "eac3", "mp3", "opus", "vorbis", "flac"],
    "max_audio_channels": 6,
    "hls": True,
    "can_select_audio_track": True,
}
CONTENT = bytes(range(256)) * 4  # 1024 bytes


@pytest.fixture
def media_dir(tmp_path: Path) -> Path:
    path = tmp_path / "media"
    path.mkdir()
    return path


@pytest.fixture
def make_file(db, media_dir):
    def factory(
        name: str = "film.mkv",
        *,
        container: str = "mkv",
        codec: str = "h264",
        duration: float | None = 60.0,
        audio: list | None = None,
        subtitles: list | None = None,
        keyframes: list[float] | None = None,
        content: bytes = CONTENT,
        missing: bool = False,
        probe_error: str | None = None,
        write: bool = True,
    ) -> int:
        path = media_dir / name
        if write:
            path.write_bytes(content)
        probe = f.probe_json(
            container=container, codec=codec, duration=duration, audio=audio, subtitles=subtitles
        )
        with db.write_session() as s:
            film = f.add_film(s, name, files=0)
            row = f.add_file(
                s,
                film_id=film.id,
                name=name,
                probe=probe,
                missing=missing,
                probe_error=probe_error,
                size=len(content),
            )
            row.path = str(path)
            if keyframes is not None:
                s.add(m.KeyframeIndex(media_file_id=row.id, source="mkv_cues",
                                      times=encode_times(keyframes)))  # fmt: skip
            return row.id

    return factory


@pytest.fixture
def ffmpeg(app):
    stub = PlanFfmpeg(duration=60.0)
    hls = app.state.hls
    hls.spawn = stub.spawner
    hls.segment_times_mode = "absolute"
    hls.wait_seconds = 2.0
    hls.poll_seconds = 0.01
    hls.kill_grace = 0.2
    hls.disk_free = lambda path: 10 * 1024**4
    app.state.keyframes.playback_wait = 0.2
    return stub


KF = [float(t) for t in range(0, 60, 6)]


async def play(client, user, file_id, caps=WEB, **body):
    return await client.post(
        f"/api/media-files/{file_id}/playback",
        json={"client_caps": caps, **body},
        headers=user.headers,
    )


# --- GET media file ---------------------------------------------------------------------------


async def test_get_media_file(client, user, make_file):
    fid = make_file()
    response = await client.get(f"/api/media-files/{fid}", headers=user.headers)
    assert response.status_code == 200
    body = response.json()
    assert body["id"] == fid
    assert body["file_name"] == "film.mkv"
    assert body["container"] == "mkv"


async def test_get_media_file_errors_and_auth(client, user, make_file):
    missing = make_file("gone.mkv", missing=True)
    assert (await client.get("/api/media-files/999", headers=user.headers)).json()["error"][
        "code"
    ] == "not_found"
    response = await client.get(f"/api/media-files/{missing}", headers=user.headers)
    assert (response.status_code, response.json()["error"]["code"]) == (404, "file_missing")
    fid = make_file()
    assert (await client.get(f"/api/media-files/{fid}")).status_code == 401
    # Bearer only: ?token= is not accepted here.
    assert (
        await client.get(f"/api/media-files/{fid}", params={"token": user.token})
    ).status_code == 401


# --- stream ------------------------------------------------------------------------------------


async def test_stream_full_and_ranges(client, user, make_file):
    fid = make_file("film.mp4", container="mp4")
    url = f"/api/media-files/{fid}/stream"
    full = await client.get(url, headers=user.headers)
    assert full.status_code == 200
    assert full.content == CONTENT
    assert full.headers["content-length"] == "1024"
    assert full.headers["accept-ranges"] == "bytes"
    assert full.headers["content-type"] == "video/mp4"
    for header, (start, end) in {
        "bytes=0-99": (0, 99),
        "bytes=100-": (100, 1023),
        "bytes=-100": (924, 1023),
    }.items():
        response = await client.get(url, headers={**user.headers, "Range": header})
        assert response.status_code == 206
        assert response.headers["content-range"] == f"bytes {start}-{end}/1024"
        assert response.headers["content-length"] == str(end - start + 1)
        assert response.content == CONTENT[start : end + 1]
    bad = await client.get(url, headers={**user.headers, "Range": "bytes=5000-"})
    assert bad.status_code == 416
    assert bad.headers["content-range"] == "bytes */1024"
    head = await client.head(url, headers=user.headers)
    assert head.status_code == 200
    assert head.content == b""
    assert head.headers["content-length"] == "1024"
    head = await client.head(url, headers={**user.headers, "Range": "bytes=0-9"})
    assert head.status_code == 206
    assert head.headers["content-length"] == "10"


async def test_stream_content_type_by_container(client, user, make_file):
    for name, container, mime in [
        ("a.mkv", "mkv", "video/x-matroska"),
        ("b.webm", "webm", "video/webm"),
        ("c.ts", "ts", "video/mp2t"),
        ("d.avi", "avi", "video/x-msvideo"),
        ("e.wmv", "asf", "application/octet-stream"),
    ]:
        fid = make_file(name, container=container)
        response = await client.head(f"/api/media-files/{fid}/stream", headers=user.headers)
        assert response.headers["content-type"] == mime


async def test_stream_file_missing(client, user, make_file):
    on_disk_gone = make_file("x.mkv", write=False)
    response = await client.get(f"/api/media-files/{on_disk_gone}/stream", headers=user.headers)
    assert (response.status_code, response.json()["error"]["code"]) == (404, "file_missing")
    absent = make_file("y.mkv", missing=True)
    response = await client.get(f"/api/media-files/{absent}/stream", headers=user.headers)
    assert response.json()["error"]["code"] == "file_missing"


async def test_media_endpoints_accept_query_token(client, user, make_file, settings):
    """?token= acceptance matrix for stream and subtitles (header wins when both)."""
    vtt = Path(settings.data_dir) / "sub.vtt"
    vtt.write_bytes(b"WEBVTT\n")
    fid = make_file()
    with client._transport.app.state.db.write_session() as s:  # type: ignore[attr-defined]
        sub = m.SubtitleFile(
            media_file_id=fid, path=str(vtt), language="spa", format="vtt", kind="text",
            size_bytes=7, mtime_ns=1,
        )  # fmt: skip
        s.add(sub)
        s.flush()
        sub_id = sub.id
    for path in (
        f"/api/media-files/{fid}/stream",
        f"/api/media-files/{fid}/subtitles/ext-{sub_id}.vtt",
    ):
        for kwargs in (
            {},
            {"headers": {"Authorization": "Bearer bad"}},
            {"params": {"token": "x"}},
        ):
            response = await client.get(path, **kwargs)
            assert response.status_code == 401
            assert response.json()["error"]["code"] == "unauthorized"
        assert (await client.get(path, params={"token": user.token})).status_code == 200
        both = await client.get(
            path, params={"token": user.token}, headers={"Authorization": "Bearer bad"}
        )
        assert both.status_code == 401
        both = await client.get(path, params={"token": "bad"}, headers=user.headers)
        assert both.status_code == 200


async def test_playback_rejects_query_token(client, user, make_file):
    fid = make_file()
    response = await client.post(
        f"/api/media-files/{fid}/playback", params={"token": user.token}, json={"client_caps": TV}
    )
    assert response.status_code == 401


# --- subtitles ---------------------------------------------------------------------------------


async def test_subtitle_route(client, user, make_file, settings):
    subtitles = [
        {"index": 2, "codec": "subrip", "language": "spa", "title": None, "forced": False,
         "hearing_impaired": False, "default": False, "kind": "text"},
        {"index": 3, "codec": "hdmv_pgs_subtitle", "language": "eng", "title": None,
         "forced": False, "hearing_impaired": False, "default": False, "kind": "image"},
    ]  # fmt: skip
    fid = make_file(subtitles=subtitles)
    cached = settings.subtitles_dir / str(fid) / "emb-2.vtt"
    cached.parent.mkdir(parents=True)
    cached.write_text("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHola\n")
    base = f"/api/media-files/{fid}/subtitles"
    ok = await client.get(f"{base}/emb-2.vtt", headers=user.headers)
    assert ok.status_code == 200
    assert ok.headers["content-type"] == "text/vtt; charset=utf-8"
    assert "Hola" in ok.text
    image = await client.get(f"{base}/emb-3.vtt", headers=user.headers)
    assert (image.status_code, image.json()["error"]["code"]) == (422, "subtitle_not_text")
    for track in ("emb-9.vtt", "ext-1.vtt", "emb-2.srt"):
        response = await client.get(f"{base}/{track}", headers=user.headers)
        assert (response.status_code, response.json()["error"]["code"]) == (
            404,
            "subtitle_not_found",
        )
    response = await client.get("/api/media-files/999/subtitles/emb-2.vtt", headers=user.headers)
    assert response.json()["error"]["code"] == "not_found"


# --- playback decisions ------------------------------------------------------------------------


async def test_playback_direct(client, user, make_file, app):
    fid = make_file("film.mp4", container="mp4")
    response = await play(client, user, fid)
    assert response.status_code == 200
    assert response.json() == {
        "mode": "direct",
        "url": f"/api/media-files/{fid}/stream",
        "audio_stream_index": 1,
        "duration_seconds": 60.0,
        "subtitle_tracks": [],
    }


async def test_playback_unsupported(client, user, make_file):
    fid = make_file(codec="hevc")
    response = await play(client, user, fid)
    assert response.json() == {
        "mode": "unsupported",
        "reason": "video_codec_unsupported",
        "detail": "Video codec hevc is not supported by this client",
    }
    failed = make_file("bad.mkv", probe_error="Invalid data")
    assert (await play(client, user, failed)).json()["reason"] == "probe_failed"


async def test_playback_errors(client, user, make_file):
    assert (await play(client, user, 999)).json()["error"]["code"] == "not_found"
    gone = make_file("gone.mkv", missing=True)
    assert (await play(client, user, gone)).json()["error"]["code"] == "file_missing"
    not_on_disk = make_file("nodisk.mkv", write=False)
    response = await play(client, user, not_on_disk, caps=TV)
    assert (response.status_code, response.json()["error"]["code"]) == (404, "file_missing")
    fid = make_file("a.mp4", container="mp4")
    response = await play(client, user, fid, audio_stream_index=9)
    assert (response.status_code, response.json()["error"]["code"]) == (
        422,
        "invalid_audio_stream",
    )
    response = await client.post(
        f"/api/media-files/{fid}/playback", json={"client_caps": {}}, headers=user.headers
    )
    assert response.json()["error"]["code"] == "validation_error"
    response = await play(client, user, fid, start_position_seconds=-1)
    assert response.status_code == 422


async def test_keyframes_too_sparse(client, user, make_file, ffmpeg, app):
    fid = make_file(duration=600.0, keyframes=[0.0, 300.0])
    response = await play(client, user, fid)
    assert response.status_code == 200
    body = response.json()
    assert body["mode"] == "unsupported"
    assert body["reason"] == "keyframes_too_sparse"
    assert "300.0 s" in body["detail"]
    assert not app.state.hls.sessions
    assert ffmpeg.count == 0
    assert (await play(client, user, fid, caps=TV)).json()["mode"] == "direct"


async def test_playback_hls_session_end_to_end(client, user, make_file, ffmpeg, app):
    ac3 = {"index": 1, "codec": "ac3", "channels": 6, "channel_layout": "5.1",
           "language": "spa", "title": None, "default": True}  # fmt: skip
    fid = make_file(keyframes=KF, audio=[ac3])
    response = await play(client, user, fid)
    assert response.status_code == 200
    body = response.json()
    sid = body["session_id"]
    assert body == {
        "mode": "hls",
        "url": f"/api/hls/{sid}/index.m3u8",
        "session_id": sid,
        "audio_stream_index": 1,
        "audio_action": "aac_5_1",
        "duration_seconds": 60.0,
        "subtitle_tracks": [],
    }
    playlist = await client.get(body["url"])
    assert playlist.status_code == 200
    assert playlist.headers["content-type"] == "application/vnd.apple.mpegurl"
    assert playlist.text.count("seg_") == 10
    init = await client.get(f"/api/hls/{sid}/init.mp4")
    assert init.status_code == 200
    assert init.headers["cache-control"] == "no-cache"
    assert init.headers["content-type"] == "video/mp4"
    for k in range(10):
        seg = await client.get(f"/api/hls/{sid}/seg_{k}.m4s")
        assert seg.status_code == 200
        assert seg.content[4:8] == b"moof"
        assert seg.headers["content-length"] == str(len(seg.content))
        assert seg.headers["cache-control"] == "no-cache"
        assert seg.headers["content-type"] == "video/mp4"
    missing = await client.get(f"/api/hls/{sid}/seg_10.m4s")
    assert missing.json()["error"]["code"] == "segment_not_found"
    assert (await client.post(f"/api/hls/{sid}/keepalive")).status_code == 204
    assert (await client.delete(f"/api/hls/{sid}")).status_code == 204
    assert (await client.delete(f"/api/hls/{sid}")).status_code == 204
    gone = await client.get(body["url"])
    assert (gone.status_code, gone.json()["error"]["code"]) == (404, "session_not_found")
    keep = await client.post(f"/api/hls/{sid}/keepalive")
    assert (keep.status_code, keep.json()["error"]["code"]) == (404, "session_not_found")


async def test_keepalive_updates_last_access_and_failed_is_500(
    client, user, make_file, ffmpeg, app
):
    fid = make_file(keyframes=KF)
    sid = (await play(client, user, fid)).json()["session_id"]
    session = app.state.hls.sessions[sid]
    session.last_access -= 100
    before = session.last_access
    assert (await client.post(f"/api/hls/{sid}/keepalive")).status_code == 204
    assert session.last_access > before
    async with session.lock:
        await app.state.hls._fail(session, "boom")
    response = await client.post(f"/api/hls/{sid}/keepalive")
    assert (response.status_code, response.json()["error"]["code"]) == (500, "transcoder_failed")
    assert (await client.get(f"/api/hls/{sid}/seg_0.m4s")).status_code == 500


async def test_too_many_sessions_and_previous_session(client, user, make_file, ffmpeg, app):
    fid = make_file(keyframes=KF)
    sids = [(await play(client, user, fid)).json()["session_id"] for _ in range(3)]
    response = await play(client, user, fid)
    assert (response.status_code, response.json()["error"]["code"]) == (503, "too_many_sessions")
    response = await play(client, user, fid, previous_session_id=sids[0])
    assert response.json()["mode"] == "hls"
    assert (await client.get(f"/api/hls/{sids[0]}/index.m3u8")).status_code == 404


async def test_hls_disk_full(client, user, make_file, ffmpeg, app):
    app.state.hls.disk_free = lambda path: 100 * 1024**2
    fid = make_file(keyframes=KF)
    response = await play(client, user, fid)
    assert (response.status_code, response.json()["error"]["code"]) == (503, "hls_disk_full")


async def test_previous_session_is_stopped_whatever_the_outcome(
    client, user, profile_factory, make_file, ffmpeg, app
):
    hls = app.state.hls
    fid = make_file(keyframes=KF)
    direct = make_file("d.mp4", container="mp4")
    unsupported = make_file("u.mkv", codec="vc1")
    gone = make_file("g.mkv", missing=True)
    slow = make_file("slow.ts", container="ts")  # no index -> 503 preparing

    async def never(proc: FakeProc) -> None:
        await asyncio.sleep(3600)

    app.state.keyframes.spawn = Spawner(never)

    async def new_session() -> str:
        return (await play(client, user, fid)).json()["session_id"]

    async def check_stopped(sid: str) -> None:
        assert (await client.get(f"/api/hls/{sid}/index.m3u8")).status_code == 404
        assert sid not in hls.sessions

    for target, kwargs, status in [
        (direct, {}, 200),
        (unsupported, {}, 200),
        (slow, {}, 503),
        (gone, {}, 404),
    ]:
        sid = await new_session()
        proc = ffmpeg.spawner.procs[-1]
        response = await play(client, user, target, previous_session_id=sid, **kwargs)
        assert response.status_code == status
        await check_stopped(sid)
        assert proc.returncode is not None

    # 503 hls_disk_full
    sid = await new_session()
    hls.disk_free = lambda path: 1
    assert (await play(client, user, fid, previous_session_id=sid)).status_code == 503
    await check_stopped(sid)
    hls.disk_free = lambda path: 10 * 1024**4

    # 503 too_many_sessions: the old one is stopped first, so it frees a slot -> fill all 3.
    others = [await new_session() for _ in range(3)]
    other_user = profile_factory("Otro")
    response = await play(client, other_user, fid, previous_session_id=others[0])
    assert response.json()["error"]["code"] == "too_many_sessions"  # foreign id ignored
    assert (await client.get(f"/api/hls/{others[0]}/index.m3u8")).status_code == 200
    assert (await play(client, user, fid, previous_session_id="f" * 32)).status_code == 503
    response = await play(client, user, fid, previous_session_id=others[1])
    assert response.json()["mode"] == "hls"
    await check_stopped(others[1])


async def test_preparing_then_success(client, user, make_file, ffmpeg, app):
    gate = asyncio.Event()

    async def slow(proc: FakeProc) -> None:
        await gate.wait()
        proc.stdout.feed_data(b"".join(f"{t},{t},K__\n".encode() for t in KF))
        proc.finish(0)

    spawner = Spawner(slow)
    app.state.keyframes.spawn = spawner
    fid = make_file("film.ts", container="ts", content=CONTENT * 10)
    response = await play(client, user, fid)
    assert response.status_code == 503
    assert response.headers["retry-after"] == "5"
    error = response.json()["error"]
    assert error["code"] == "preparing"
    assert error["details"]["retry_after_seconds"] == 5
    assert 1 <= error["details"]["estimated_seconds"] <= 86_400
    gate.set()
    await asyncio.sleep(0.05)
    ok = await play(client, user, fid)
    assert ok.json()["mode"] == "hls"
    assert spawner.count == 1


async def test_remembered_index_failure_is_500_at_once(client, user, make_file, ffmpeg, app):
    async def crash(proc: FakeProc) -> None:
        proc.finish(1)

    spawner = Spawner(crash)
    app.state.keyframes.spawn = spawner
    fid = make_file("film.ts", container="ts")
    for _ in range(2):
        response = await play(client, user, fid)
        assert response.status_code == 500
        assert response.json()["error"]["code"] == "transcoder_failed"
        assert (
            response.json()["error"]["details"]["detail"] == "keyframe_index_failed: ffprobe_error"
        )
    assert spawner.count == 1


async def test_subtitles_prefetched_on_playback(client, user, make_file, app):
    started = []
    app.state.subtitles.prefetch = lambda src: started.append(src.media_file_id)
    fid = make_file("film.mp4", container="mp4")
    await play(client, user, fid)
    assert started == [fid]


# --- latency (G4, in-process) ------------------------------------------------------------------


async def test_g4_latency_targets(client, user, make_file, ffmpeg, app):
    direct = make_file("film.mp4", container="mp4", content=CONTENT * 4096)
    remux = make_file(keyframes=KF)
    timings = {}
    t = time.perf_counter()
    response = await play(client, user, direct)
    timings["playback_direct"] = time.perf_counter() - t
    assert response.status_code == 200
    t = time.perf_counter()
    async with client.stream(
        "GET", f"/api/media-files/{direct}/stream", headers={**user.headers, "Range": "bytes=0-"}
    ) as stream:
        async for _chunk in stream.aiter_bytes():
            timings["first_byte"] = time.perf_counter() - t
            break
    t = time.perf_counter()
    response = await play(client, user, remux)
    timings["playback_hls"] = time.perf_counter() - t
    assert response.json()["mode"] == "hls"
    print("G4 " + " ".join(f"{k}={v * 1000:.1f}ms" for k, v in timings.items()))
    assert timings["playback_direct"] < 0.2
    assert timings["playback_hls"] < 0.2
    assert timings["first_byte"] < 0.3
