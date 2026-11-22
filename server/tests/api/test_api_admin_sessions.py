"""``GET /api/admin/sessions`` and ``DELETE /api/admin/sessions/{id}`` (FR-S7 / FR-S8)."""
# ruff: noqa: F401, F811  (fixtures imported from test_api_media)

import asyncio
from datetime import UTC, datetime

import pytest

from hometv import profiles as p
from hometv.admin import describe_sessions
from hometv.api.schemas import CpuLoad
from hometv.db import models as m
from hometv.playback.keyframes import encode_times
from hometv.sysload import read_cpu_load
from tests import factories as f
from tests.api.test_api_media import KF, ffmpeg, make_file, media_dir, play
from tests.stubs import capture_selects


@pytest.fixture
def cpu(app):
    app.state.cpu_load = lambda: CpuLoad(load_1m=1.5, load_5m=0.75, cores=4)


async def listing(client, admin):
    response = await client.get("/api/admin/sessions", headers=admin.admin_headers)
    assert response.status_code == 200, response.text
    return response.json()


@pytest.fixture
def make_episode_file(db, media_dir):
    """A linked episode file (hevc, so a web client gets a conversion) with a keyframe index."""

    def factory(numbers=(2,), show="Dark", name="Dark.S01E02.mkv") -> int:
        path = media_dir / name
        path.write_bytes(b"x" * 1024)
        with db.write_session() as s:
            _show, eps = f.add_show(s, show, episodes={1: 3}, with_files=False)
            row = f.add_file(
                s,
                episode_ids=[eps[(1, n)].id for n in numbers],
                name=name,
                probe=f.probe_json(container="mkv", codec="hevc", duration=60.0),
            )
            row.path = str(path)
            s.add(m.KeyframeIndex(media_file_id=row.id, source="mkv_cues", times=encode_times(KF)))
            return row.id

    return factory


async def test_lists_film_copy_and_episode_h264_sessions(
    client, app, admin, user, make_file, make_episode_file, ffmpeg, db, cpu
):
    fid = make_file("Film.Name.mkv", keyframes=KF)
    with db.write_session() as s:
        s.get(m.MediaFile, fid).film_id = f.add_film(s, "Dune", files=0).id
    eid = make_episode_file(numbers=(2, 3), name="Dark.S01E02-E03.mkv")
    app.state.hls.wall_clock = lambda: datetime(2030, 1, 2, 3, 4, 5, tzinfo=UTC)
    copy = (await play(client, user, fid)).json()
    conv = (await play(client, user, eid)).json()
    assert (copy["video_action"], conv["video_action"]) == ("copy", "h264")
    assert (await client.get(f"/api/hls/{conv['session_id']}/seg_1.m4s")).status_code == 200
    app.state.hls.sessions[conv["session_id"]].speed = 1.75

    body = await listing(client, admin)
    rows = {r["session_id"]: r for r in body["sessions"]}
    assert set(rows) == {copy["session_id"], conv["session_id"]}
    film = rows[copy["session_id"]]
    assert film["state"] in ("running", "throttled", "done")
    assert film == {
        "session_id": copy["session_id"],
        "profile_id": user.id,
        "profile_name": "Kids",
        "media_file_id": fid,
        "file_name": "Film.Name.mkv",
        "item_type": "film",
        "title": "Dune",
        "season_number": None,
        "episode_number": None,
        "last_episode_number": None,
        "video_action": "copy",
        "audio_action": copy["audio_action"],
        "state": film["state"],
        "started_at": "2030-01-02T03:04:05Z",
        "last_segment": None,
        "position_seconds": None,
        "duration_seconds": 60.0,
        "speed": None,
    }
    episode = rows[conv["session_id"]]
    assert (episode["item_type"], episode["title"]) == ("episode", "Dark")
    assert (episode["season_number"], episode["episode_number"]) == (1, 2)
    assert episode["last_episode_number"] == 3
    assert episode["file_name"] == "Dark.S01E02-E03.mkv"
    assert (episode["video_action"], episode["speed"]) == ("h264", 1.75)
    assert (episode["last_segment"], episode["position_seconds"]) == (1, 6.0)
    assert body["cpu"] == {"load_1m": 1.5, "load_5m": 0.75, "cores": 4}
    assert body["limits"] == {
        "max_sessions": app.state.settings.playback.max_sessions,
        "max_transcodes": app.state.settings.playback.max_transcodes,
    }
    assert (body["active_sessions"], body["active_transcodes"]) == (2, 1)


async def test_single_episode_has_no_last_episode_number(
    client, admin, user, make_episode_file, ffmpeg
):
    await play(client, user, make_episode_file())
    row = (await listing(client, admin))["sessions"][0]
    assert (row["episode_number"], row["last_episode_number"]) == (2, None)


async def test_deleted_profile_and_unlinked_file_keep_snapshot_fields(
    client, admin, user, make_file, ffmpeg, db
):
    fid = make_file("orphan.mkv", keyframes=KF)
    with db.write_session() as s:
        s.get(m.MediaFile, fid).film_id = None
    sid = (await play(client, user, fid)).json()["session_id"]
    p.delete_profile(db, user.id)
    row = (await listing(client, admin))["sessions"][0]
    assert row["session_id"] == sid
    assert (row["profile_name"], row["item_type"], row["title"]) == (None, None, None)
    assert (row["season_number"], row["episode_number"]) == (None, None)
    assert (row["file_name"], row["profile_id"]) == ("orphan.mkv", user.id)


async def test_counts_ignore_failed_sessions(
    client, app, admin, user, make_episode_file, make_file, ffmpeg
):
    app.state.settings.playback.max_transcodes = 2
    await play(client, user, make_file(keyframes=KF))
    eid = make_episode_file()
    await play(client, user, eid)
    failed = (await play(client, user, eid)).json()["session_id"]
    hls = app.state.hls
    async with hls.sessions[failed].lock:
        await hls._fail(hls.sessions[failed], "boom")
    body = await listing(client, admin)
    assert len(body["sessions"]) == 3
    assert (body["active_sessions"], body["active_transcodes"]) == (2, 1)
    assert [r["state"] for r in body["sessions"] if r["session_id"] == failed] == ["failed"]
    assert (body["active_sessions"], body["active_transcodes"]) == tuple(hls.counts())


async def test_sessions_ordered_by_start_then_id(client, app, admin, user, make_file, ffmpeg):
    fid = make_file(keyframes=KF)
    app.state.hls.wall_clock = lambda: datetime(2030, 1, 1, tzinfo=UTC)
    ids = [(await play(client, user, fid)).json()["session_id"] for _ in range(3)]
    assert [r["session_id"] for r in (await listing(client, admin))["sessions"]] == sorted(ids)


async def test_empty_list_and_cpu_none(client, app, admin):
    app.state.cpu_load = lambda: None
    assert await listing(client, admin) == {
        "sessions": [],
        "active_sessions": 0,
        "active_transcodes": 0,
        "cpu": None,
        "limits": {
            "max_sessions": app.state.settings.playback.max_sessions,
            "max_transcodes": app.state.settings.playback.max_transcodes,
        },
    }


async def test_default_cpu_seam_is_read_cpu_load(app):
    assert app.state.cpu_load is read_cpu_load


@pytest.mark.parametrize("count", [1, 4])
async def test_listing_runs_at_most_three_selects(
    client, app, admin, user, make_episode_file, ffmpeg, db, count
):
    app.state.settings.playback.max_sessions = 10
    app.state.settings.playback.max_transcodes = 10
    for i in range(count):
        await play(client, user, make_episode_file(show=f"Show {i}", name=f"e{i}.mkv"))
    snapshot = app.state.hls.snapshot()
    with capture_selects(db) as selects:
        rows = await asyncio.to_thread(describe_sessions, db, snapshot)
    assert len(rows) == count
    assert len(selects) <= 3, [s for s, _ in selects]
    assert len((await listing(client, admin))["sessions"]) == count


# --- stop -------------------------------------------------------------------------------------


async def test_admin_stop_kills_ffmpeg_removes_dir_and_answers_410(
    client, app, admin, user, make_file, ffmpeg
):
    fid = make_file(keyframes=KF)
    sid = (await play(client, user, fid)).json()["session_id"]
    session = app.state.hls.sessions[sid]
    response = await client.delete(f"/api/admin/sessions/{sid}", headers=admin.admin_headers)
    assert response.status_code == 204
    assert not session.dir.exists()
    assert ffmpeg.spawner.procs[0].returncode is not None
    assert (await listing(client, admin))["sessions"] == []
    again = await client.delete(f"/api/admin/sessions/{sid}", headers=admin.admin_headers)
    assert (again.status_code, again.json()["error"]["code"]) == (404, "session_not_found")

    for method, path in [
        ("GET", f"/api/hls/{sid}/index.m3u8"),
        ("GET", f"/api/hls/{sid}/init.mp4"),
        ("GET", f"/api/hls/{sid}/seg_3.m4s"),
        ("POST", f"/api/hls/{sid}/keepalive"),
    ]:
        r = await client.request(method, path)
        assert (r.status_code, r.json()["error"]["code"]) == (410, "session_stopped"), path
        assert r.json()["error"]["details"] == {}
    unknown = await client.get("/api/hls/" + "0" * 32 + "/index.m3u8")
    assert (unknown.status_code, unknown.json()["error"]["code"]) == (404, "session_not_found")
    assert (await client.delete(f"/api/hls/{sid}")).status_code == 204
    assert (await play(client, user, fid)).status_code == 200  # same file plays again


async def test_unknown_session_delete_is_404(client, admin):
    r = await client.delete("/api/admin/sessions/nope", headers=admin.admin_headers)
    assert (r.status_code, r.json()["error"]["code"]) == (404, "session_not_found")


@pytest.mark.parametrize("state", ["failed", "done"])
async def test_admin_can_stop_failed_and_done_sessions(
    client, app, admin, user, make_file, ffmpeg, state
):
    sid = (await play(client, user, make_file(keyframes=KF))).json()["session_id"]
    hls = app.state.hls
    session = hls.sessions[sid]
    if state == "failed":
        async with session.lock:
            await hls._fail(session, "boom")
    else:
        session.state = "done"
    assert [r["state"] for r in (await listing(client, admin))["sessions"]] == [state]
    r = await client.delete(f"/api/admin/sessions/{sid}", headers=admin.admin_headers)
    assert r.status_code == 204
    assert (await listing(client, admin))["sessions"] == []
    gone = await client.get(f"/api/hls/{sid}/index.m3u8")
    assert (gone.status_code, gone.json()["error"]["code"]) == (410, "session_stopped")


async def test_waiting_segment_request_gets_410_on_admin_stop(
    client, app, admin, user, make_file, ffmpeg
):
    ffmpeg.interval = 30.0  # nothing is produced: the segment request waits
    app.state.hls.wait_seconds = 10.0
    sid = (await play(client, user, make_file(keyframes=KF))).json()["session_id"]
    waiting = asyncio.create_task(client.get(f"/api/hls/{sid}/seg_0.m4s"))
    await asyncio.sleep(0.3)
    assert not waiting.done()
    await client.delete(f"/api/admin/sessions/{sid}", headers=admin.admin_headers)
    r = await asyncio.wait_for(waiting, 5)
    assert (r.status_code, r.json()["error"]["code"]) == (410, "session_stopped")
