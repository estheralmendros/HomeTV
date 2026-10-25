"""PUT /api/progress, POST /api/watched (PRD §9, REST "Watch state")."""

from sqlalchemy import delete, select

from hometv.db import models as m
from tests.factories import add_file, add_film, add_progress, add_show

PROGRESS_KEYS = {
    "position_seconds",
    "duration_seconds",
    "watched",
    "watched_at",
    "media_file_id",
    "updated_at",
}


def _film(db, **kw):
    with db.write_session() as s:
        film = add_film(s, "Dune", year=2021, files=0)
        f = add_file(s, film_id=film.id, duration=kw.get("duration", 10000.0))
        return film.id, f.id


async def _put(client, who, **body):
    return await client.put("/api/progress", json=body, headers=who.headers)


async def test_progress_roundtrip_and_watched_rule(client, db, user):
    film_id, file_id = _film(db)
    r = await _put(
        client,
        user,
        item_type="film",
        item_id=film_id,
        media_file_id=file_id,
        position_seconds=1234.5,
        duration_seconds=1.0,
    )
    assert r.status_code == 200
    data = r.json()
    assert set(data) == PROGRESS_KEYS
    # The probed duration wins over the request's.
    assert data["duration_seconds"] == 10000.0
    assert (data["position_seconds"], data["watched"], data["watched_at"]) == (1234.5, False, None)
    assert data["media_file_id"] == file_id
    assert data["updated_at"].endswith("Z")

    r = await _put(
        client,
        user,
        item_type="film",
        item_id=film_id,
        media_file_id=file_id,
        position_seconds=9000.0,
    )
    data = r.json()
    assert (data["position_seconds"], data["watched"]) == (0.0, True)
    assert data["watched_at"].endswith("Z")

    # A rewatch does not unset watched.
    r = await _put(
        client,
        user,
        item_type="film",
        item_id=film_id,
        media_file_id=file_id,
        position_seconds=100.0,
    )
    assert (r.json()["position_seconds"], r.json()["watched"]) == (100.0, True)


async def test_progress_is_per_profile(client, db, user, admin):
    film_id, file_id = _film(db)
    await _put(
        client,
        user,
        item_type="film",
        item_id=film_id,
        media_file_id=file_id,
        position_seconds=500.0,
    )
    with db.read_session() as s:
        rows = s.scalars(select(m.PlaybackProgress)).all()
    assert [(r.profile_id, r.position_seconds) for r in rows] == [(user.id, 500.0)]


async def test_duration_from_request_when_unknown_else_422(client, db, user):
    with db.write_session() as s:
        film = add_film(s, "Unknown", files=0)
        f = add_file(s, film_id=film.id, probe_error="Invalid data")
        film_id, file_id = film.id, f.id
    base = {
        "item_type": "film",
        "item_id": film_id,
        "media_file_id": file_id,
        "position_seconds": 10.0,
    }
    r = await client.put("/api/progress", json=base, headers=user.headers)
    assert r.status_code == 422 and r.json()["error"]["code"] == "validation_error"
    r = await client.put(
        "/api/progress", json={**base, "duration_seconds": 100.0}, headers=user.headers
    )
    assert r.status_code == 200 and r.json()["duration_seconds"] == 100.0


async def test_progress_errors(client, db, user):
    film_id, file_id = _film(db)
    with db.write_session() as s:
        other = add_film(s, "Other")
        other_file = s.scalar(select(m.MediaFile.id).where(m.MediaFile.film_id == other.id))
        _, eps = add_show(s, "Show", episodes={1: 1})
        episode_id = eps[(1, 1)].id
    base = {
        "item_type": "film",
        "item_id": film_id,
        "media_file_id": file_id,
        "position_seconds": 10.0,
    }

    async def code(**over):
        r = await client.put("/api/progress", json={**base, **over}, headers=user.headers)
        return r.status_code, r.json()["error"]["code"]

    assert await code(item_id=999) == (404, "not_found")
    assert await code(media_file_id=999) == (404, "not_found")
    assert await code(item_type="episode", item_id=999) == (404, "not_found")
    assert await code(media_file_id=other_file) == (422, "media_file_not_linked")
    assert await code(item_type="episode", item_id=episode_id) == (422, "media_file_not_linked")
    assert await code(position_seconds=-1) == (422, "validation_error")
    assert await code(item_type="season") == (422, "validation_error")
    body = dict(base)
    del body["media_file_id"]
    r = await client.put("/api/progress", json=body, headers=user.headers)
    assert r.status_code == 422 and r.json()["error"]["code"] == "validation_error"
    r = await client.put("/api/progress", json=base)
    assert r.status_code == 401


async def test_multi_part_film_rule(client, db, user):
    with db.write_session() as s:
        film = add_film(s, "Kill Bill", files=0)
        part1 = add_file(s, film_id=film.id, part_number=1, duration=3000.0)
        part2 = add_file(s, film_id=film.id, part_number=2, duration=2000.0)
        full = add_file(s, film_id=film.id, duration=5000.0)
        ids = film.id, part1.id, part2.id, full.id
    film_id, p1, p2, full_id = ids

    async def put(file_id, pos):
        r = await _put(
            client,
            user,
            item_type="film",
            item_id=film_id,
            media_file_id=file_id,
            position_seconds=pos,
        )
        assert r.status_code == 200
        return r.json()

    data = await put(p1, 2850.0)  # 95 % of Part 1
    assert (data["watched"], data["position_seconds"], data["media_file_id"]) == (False, 2850.0, p1)
    home = (await client.get("/api/home", headers=user.headers)).json()
    assert [x["id"] for x in home["continue_watching"]] == [film_id]

    data = await put(p2, 1800.0)  # 90 % of Part 2 (last part)
    assert (data["watched"], data["position_seconds"]) == (True, 0.0)

    await client.post(
        "/api/watched",
        json={"target": "film", "id": film_id, "watched": False},
        headers=user.headers,
    )
    data = await put(full_id, 4750.0)  # 95 % of a standalone version
    assert data["watched"] is True

    # Only Part 1 present (Part 2 deleted and purged): Part 1 is the last part.
    await client.post(
        "/api/watched",
        json={"target": "film", "id": film_id, "watched": False},
        headers=user.headers,
    )
    with db.write_session() as s:
        s.execute(delete(m.MediaFile).where(m.MediaFile.id.in_([p2, full_id])))
    data = await put(p1, 2700.0)
    assert data["watched"] is True


async def test_missing_part_is_not_the_last_part(client, db, user):
    with db.write_session() as s:
        film = add_film(s, "Kill Bill", files=0)
        part1 = add_file(s, film_id=film.id, part_number=1, duration=3000.0)
        add_file(s, film_id=film.id, part_number=2, duration=2000.0, missing=True)
        film_id, p1 = film.id, part1.id
    r = await _put(
        client, user, item_type="film", item_id=film_id, media_file_id=p1, position_seconds=2900.0
    )
    assert r.json()["watched"] is True  # Part 2 is hidden (missing): Part 1 is the last present


async def test_media_file_set_null_when_file_removed(client, db, user):
    film_id, file_id = _film(db)
    with db.write_session() as s:
        add_file(s, film_id=film_id)  # keeps the film visible
    await _put(
        client,
        user,
        item_type="film",
        item_id=film_id,
        media_file_id=file_id,
        position_seconds=600.0,
    )
    with db.write_session() as s:
        s.execute(delete(m.MediaFile).where(m.MediaFile.id == file_id))
    detail = (await client.get(f"/api/films/{film_id}", headers=user.headers)).json()
    assert detail["progress"]["media_file_id"] is None
    assert detail["progress"]["position_seconds"] == 600.0


async def test_multi_episode_file_marks_all_linked_episodes(client, db, user):
    with db.write_session() as s:
        _show, eps = add_show(s, "Friends", episodes={1: 3}, with_files=False)
        e1, e2, e3 = eps[(1, 1)].id, eps[(1, 2)].id, eps[(1, 3)].id
        both = add_file(s, episode_ids=[e1, e2], duration=2600.0).id
        add_file(s, episode_ids=[e3])
    r = await _put(
        client, user, item_type="episode", item_id=e1, media_file_id=both, position_seconds=1000.0
    )
    with db.read_session() as s:
        assert (
            s.scalar(select(m.PlaybackProgress).where(m.PlaybackProgress.episode_id == e2)) is None
        )
    r = await _put(
        client, user, item_type="episode", item_id=e1, media_file_id=both, position_seconds=2400.0
    )
    assert r.json()["watched"] is True
    with db.read_session() as s:
        rows = {r.episode_id: r for r in s.scalars(select(m.PlaybackProgress))}
    assert set(rows) == {e1, e2}
    assert rows[e2].watched and rows[e2].position_seconds == 0.0


async def test_mark_watched_targets(client, db, user):
    with db.write_session() as s:
        film = add_film(s, "Dune")
        show, eps = add_show(s, "Office", episodes={0: 1, 1: 2, 2: 3})
        add_file(s, film_id=None, episode_ids=[], name="x.mkv")  # unrelated
        hidden = add_show(s, "Hidden", episodes={1: 1}, with_files=False)[1][(1, 1)]
        add_file(s, episode_ids=[hidden.id], missing=True)
        season2 = s.scalar(
            select(m.Season.id).where(m.Season.show_id == show.id, m.Season.season_number == 2)
        )
        file_id = s.scalar(select(m.MediaFile.id).where(m.MediaFile.film_id == film.id))
        add_progress(s, user.id, film_id=film.id, position=700.0, media_file_id=file_id)
        film_id, show_id, ep = film.id, show.id, eps[(1, 1)].id

    async def mark(target, id_, watched):
        r = await client.post(
            "/api/watched",
            json={"target": target, "id": id_, "watched": watched},
            headers=user.headers,
        )
        assert r.status_code == 200, r.text
        return r.json()["updated"]

    assert await mark("film", film_id, True) == 1
    detail = (await client.get(f"/api/films/{film_id}", headers=user.headers)).json()
    assert detail["progress"]["watched"] is True
    assert detail["progress"]["position_seconds"] == 0.0
    assert detail["progress"]["media_file_id"] == file_id  # manual marks keep the version
    assert await mark("film", film_id, False) == 1
    detail = (await client.get(f"/api/films/{film_id}", headers=user.headers)).json()
    assert detail["progress"]["watched"] is False and detail["progress"]["watched_at"] is None
    assert await mark("film", film_id, False) == 0  # nothing changed

    assert await mark("season", season2, True) == 3
    assert await mark("show", show_id, True) == 6  # all library episodes, specials included
    assert await mark("episode", ep, False) == 1
    assert await mark("show", show_id, False) == 5

    for target in ("film", "episode", "season", "show"):
        r = await client.post(
            "/api/watched",
            json={"target": target, "id": 999, "watched": True},
            headers=user.headers,
        )
        assert r.status_code == 404 and r.json()["error"]["code"] == "not_found"


async def test_mark_watched_many_episode_show_keeps_one_row_per_episode(client, db, user, admin):
    with db.write_session() as s:
        show, eps = add_show(s, "Long", episodes={1: 60, 2: 60, 3: 60})
        ids = sorted(e.id for e in eps.values())
        in_progress = ids[::7]
        for ep_id in in_progress:
            file_id = s.scalar(
                select(m.MediaFileEpisode.media_file_id).where(
                    m.MediaFileEpisode.episode_id == ep_id
                )
            )
            add_progress(s, user.id, episode_id=ep_id, position=300.0, media_file_id=file_id)
        add_progress(s, admin.id, episode_id=ids[0], position=100.0)  # another profile's row
        show_id = show.id

    async def mark(watched):
        r = await client.post(
            "/api/watched",
            json={"target": "show", "id": show_id, "watched": watched},
            headers=user.headers,
        )
        assert r.status_code == 200, r.text
        return r.json()["updated"]

    assert await mark(True) == 180
    with db.read_session() as s:
        rows = s.scalars(
            select(m.PlaybackProgress).where(m.PlaybackProgress.profile_id == user.id)
        ).all()
        assert sorted(r.episode_id for r in rows) == ids  # existing rows reused, no duplicates
        assert all(r.watched and r.position_seconds == 0.0 for r in rows)
        kept = {r.episode_id: r.media_file_id for r in rows if r.media_file_id is not None}
        assert sorted(kept) == in_progress  # manual marks keep the version
        other = s.scalar(
            select(m.PlaybackProgress).where(m.PlaybackProgress.profile_id == admin.id)
        )
        assert other is not None and not other.watched and other.position_seconds == 100.0
    assert await mark(False) == 180
    assert await mark(False) == 0
