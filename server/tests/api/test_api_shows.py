"""GET /api/shows, /api/shows/{id}, /api/shows/{id}/seasons/{n}, /api/episodes/{id}."""

from datetime import timedelta

import pytest
from sqlalchemy import select

from hometv.db import models as m
from tests.factories import T0, add_episode, add_file, add_progress, add_show

SHOW_SUMMARY_KEYS = {
    "type",
    "id",
    "title",
    "year",
    "poster",
    "backdrop",
    "overview",
    "match_status",
    "added_at",
    "season_count",
    "episode_count",
    "unwatched_episode_count",
}
EPISODE_SUMMARY_KEYS = {
    "type",
    "id",
    "show_id",
    "show_title",
    "season_id",
    "season_number",
    "episode_number",
    "title",
    "overview",
    "still",
    "air_date",
    "runtime_minutes",
    "duration_seconds",
    "show_poster",
    "show_backdrop",
    "progress",
}


def _seed(db, profile_id):
    """Office: S0 (1 special), S1 (3 eps), S2 (2 eps); S1E3 file missing (hidden)."""
    with db.write_session() as s:
        show, eps = add_show(
            s,
            "The Office",
            year=2005,
            episodes={0: 1, 1: 2, 2: 2},
            poster="op.jpg",
            backdrop="ob.jpg",
            overview="Oficina",
            genres=((35, "Comedia"),),
            tmdb_id=2316,
            status="Ended",
        )
        hidden = add_episode(s, show, 1, 3, with_file=False)
        add_file(s, episode_ids=[hidden.id], missing=True)
        eps[(1, 1)].still_path = "still.jpg"
        eps[(1, 1)].overview = "Piloto"
        eps[(1, 1)].runtime_minutes = 22
        add_progress(s, profile_id, episode_id=eps[(1, 1)].id, watched=True)
        return show.id, {k: v.id for k, v in eps.items()}, hidden.id


async def _get(client, who, path):
    r = await client.get(path, headers=who.headers)
    assert r.status_code == 200, r.text
    return r.json()


async def test_show_list_summary_counts(client, db, user):
    show_id, _eps, _ = _seed(db, user.id)
    with db.write_session() as s:
        add_show(s, "Alf", year=None, episodes={1: 1})
    page = await _get(client, user, "/api/shows")
    assert page["total"] == 2
    assert [x["title"] for x in page["items"]] == ["Alf", "The Office"]
    office = page["items"][1]
    assert set(office) == SHOW_SUMMARY_KEYS
    assert office == {
        "type": "show",
        "id": show_id,
        "title": "The Office",
        "year": 2005,
        "poster": {"small": "/api/artwork/w342/op.jpg", "large": "/api/artwork/w780/op.jpg"},
        "backdrop": {"small": "/api/artwork/w780/ob.jpg", "large": "/api/artwork/w1280/ob.jpg"},
        "overview": "Oficina",
        "match_status": "matched",
        "added_at": "2026-01-01T00:00:00Z",
        "season_count": 2,  # specials excluded
        "episode_count": 4,  # specials and hidden episodes excluded
        "unwatched_episode_count": 3,
    }


async def test_show_list_filters_and_sort_added(client, db, user):
    with db.write_session() as s:
        add_show(
            s,
            "Los Serrano",
            year=2003,
            content_updated_at=T0 + timedelta(days=2),
            genres=((35, "Comedia"),),
        )
        add_show(
            s, "Lost", year=2004, content_updated_at=T0 + timedelta(days=1), genres=((18, "Drama"),)
        )
        add_show(s, "Érase una vez", year=None, content_updated_at=T0)
    titles = lambda page: [x["title"] for x in page["items"]]  # noqa: E731
    assert titles(await _get(client, user, "/api/shows")) == [
        "Érase una vez",
        "Lost",
        "Los Serrano",
    ]  # "Los Serrano" sorts as "serrano"
    assert titles(await _get(client, user, "/api/shows?sort=added&order=desc")) == [
        "Los Serrano",
        "Lost",
        "Érase una vez",
    ]
    assert titles(await _get(client, user, "/api/shows?q=erase")) == ["Érase una vez"]
    assert titles(await _get(client, user, "/api/shows?genre=18")) == ["Lost"]
    assert titles(await _get(client, user, "/api/shows?year_from=2004")) == ["Lost"]


async def test_show_with_only_missing_files_is_hidden(client, db, user):
    with db.write_session() as s:
        show, _ = add_show(s, "Gone", episodes={1: 1}, with_files=False)
        ep = s.scalar(select(m.Episode).where(m.Episode.show_id == show.id))
        add_file(s, episode_ids=[ep.id], missing=True)
        show_id, ep_id = show.id, ep.id
    assert (await _get(client, user, "/api/shows"))["total"] == 0
    for path in (
        f"/api/shows/{show_id}",
        f"/api/shows/{show_id}/seasons/1",
        f"/api/episodes/{ep_id}",
    ):
        r = await client.get(path, headers=user.headers)
        assert r.status_code == 404 and r.json()["error"]["code"] == "not_found", path


async def test_show_detail(client, db, user):
    show_id, eps, _ = _seed(db, user.id)
    d = await _get(client, user, f"/api/shows/{show_id}")
    assert set(d) == {
        "type",
        "id",
        "title",
        "original_title",
        "year",
        "first_air_date",
        "status",
        "overview",
        "vote_average",
        "genres",
        "poster",
        "backdrop",
        "match_status",
        "tmdb_id",
        "cast",
        "seasons",
        "next_episode",
        "unwatched_episode_count",
        "added_at",
    }
    assert (d["status"], d["tmdb_id"], d["genres"]) == (
        "Ended",
        2316,
        [{"id": 35, "name": "Comedia"}],
    )
    assert [
        (x["season_number"], x["episode_count"], x["unwatched_episode_count"]) for x in d["seasons"]
    ] == [(1, 2, 1), (2, 2, 2), (0, 1, 1)]  # specials last
    assert set(d["seasons"][0]) == {
        "id",
        "season_number",
        "name",
        "poster",
        "air_date",
        "episode_count",
        "unwatched_episode_count",
    }
    assert d["seasons"][0]["name"] == "Temporada 1"
    assert d["unwatched_episode_count"] == 3
    nxt = d["next_episode"]
    assert set(nxt) == EPISODE_SUMMARY_KEYS
    assert (nxt["id"], nxt["season_number"], nxt["episode_number"]) == (eps[(1, 2)], 1, 2)
    assert nxt["show_backdrop"] == d["backdrop"] and nxt["show_poster"] == d["poster"]


async def test_show_next_episode_rules(client, db, user):
    with db.write_session() as s:
        show, eps = add_show(s, "Lost", episodes={1: 3})
        show_id = show.id
        ids = {k: v.id for k, v in eps.items()}
    nxt = lambda d: d["next_episode"] and d["next_episode"]["id"]  # noqa: E731
    # Nothing watched: first unwatched.
    assert nxt(await _get(client, user, f"/api/shows/{show_id}")) == ids[(1, 1)]
    # In-progress wins over Next Up.
    with db.write_session() as s:
        add_progress(s, user.id, episode_id=ids[(1, 2)], watched=True)
        add_progress(s, user.id, episode_id=ids[(1, 1)], position=300.0)
    assert nxt(await _get(client, user, f"/api/shows/{show_id}")) == ids[(1, 1)]
    with db.write_session() as s:
        s.execute(
            m.PlaybackProgress.__table__.delete().where(
                m.PlaybackProgress.episode_id == ids[(1, 1)]
            )
        )
    assert nxt(await _get(client, user, f"/api/shows/{show_id}")) == ids[(1, 3)]  # Next Up
    with db.write_session() as s:
        add_progress(s, user.id, episode_id=ids[(1, 3)], watched=True)
    # Next Up has nothing after S1E3 -> first unwatched (S1E1).
    assert nxt(await _get(client, user, f"/api/shows/{show_id}")) == ids[(1, 1)]
    with db.write_session() as s:
        add_progress(s, user.id, episode_id=ids[(1, 1)], watched=True)
    assert (await _get(client, user, f"/api/shows/{show_id}"))["next_episode"] is None


async def test_season_detail(client, db, user):
    show_id, _eps, _hidden = _seed(db, user.id)
    d = await _get(client, user, f"/api/shows/{show_id}/seasons/1")
    assert set(d) == {
        "id",
        "show_id",
        "show_title",
        "season_number",
        "name",
        "overview",
        "poster",
        "air_date",
        "episodes",
    }
    assert (d["show_id"], d["show_title"], d["season_number"]) == (show_id, "The Office", 1)
    assert [e["episode_number"] for e in d["episodes"]] == [1, 2]  # hidden S1E3 excluded
    e1 = d["episodes"][0]
    assert set(e1) == EPISODE_SUMMARY_KEYS
    assert e1["still"] == {
        "small": "/api/artwork/w300/still.jpg",
        "large": "/api/artwork/w780/still.jpg",
    }
    assert (e1["overview"], e1["runtime_minutes"], e1["duration_seconds"]) == ("Piloto", 22, 1320.0)
    assert e1["progress"]["watched"] is True
    assert e1["show_backdrop"] == {
        "small": "/api/artwork/w780/ob.jpg",
        "large": "/api/artwork/w1280/ob.jpg",
    }
    assert e1["air_date"] is None
    specials = await _get(client, user, f"/api/shows/{show_id}/seasons/0")
    assert [e["season_number"] for e in specials["episodes"]] == [0]
    r = await client.get(f"/api/shows/{show_id}/seasons/7", headers=user.headers)
    assert r.status_code == 404 and r.json()["error"]["code"] == "not_found"


async def test_episode_detail_versions_and_neighbours(client, db, user):
    _show_id, eps, _ = _seed(db, user.id)
    with db.write_session() as s:
        small = add_file(s, episode_ids=[eps[(1, 2)]], height=2160, size=1)
        big = add_file(s, episode_ids=[eps[(1, 2)]], height=2160, size=2)
        unknown = add_file(s, episode_ids=[eps[(1, 2)]], probe_error="bad")
        original = s.scalar(
            select(m.MediaFileEpisode.media_file_id)
            .where(m.MediaFileEpisode.episode_id == eps[(1, 2)])
            .order_by(m.MediaFileEpisode.media_file_id)
        )
        ids = big.id, small.id, original, unknown.id
    d = await _get(client, user, f"/api/episodes/{eps[(1, 2)]}")
    assert set(d) == EPISODE_SUMMARY_KEYS | {
        "media_files",
        "previous_episode_id",
        "next_episode_id",
    }
    assert [f["id"] for f in d["media_files"]] == list(ids)
    assert d["duration_seconds"] == d["media_files"][0]["duration_seconds"]
    # Neighbours cross seasons and skip specials (and hidden S1E3).
    assert (d["previous_episode_id"], d["next_episode_id"]) == (eps[(1, 1)], eps[(2, 1)])
    first = await _get(client, user, f"/api/episodes/{eps[(1, 1)]}")
    assert first["previous_episode_id"] is None
    last = await _get(client, user, f"/api/episodes/{eps[(2, 2)]}")
    assert last["next_episode_id"] is None
    special = await _get(client, user, f"/api/episodes/{eps[(0, 1)]}")
    assert (special["previous_episode_id"], special["next_episode_id"]) == (None, eps[(1, 1)])
    r = await client.get("/api/episodes/999", headers=user.headers)
    assert r.status_code == 404


@pytest.mark.parametrize("path", ["/api/shows", "/api/shows/1", "/api/episodes/1"])
async def test_auth_required(client, path):
    assert (await client.get(path)).status_code == 401


async def test_show_list_watched_filter(client, db, user, admin):
    """PRD §10 list filters: shows by unwatched_episode_count (specials ignored)."""
    with db.write_session() as s:
        _, done = add_show(s, "Dark", episodes={0: 1, 1: 2})
        _, half = add_show(s, "Lost", episodes={1: 2})
        add_show(s, "Monk", episodes={1: 1})
        add_show(s, "Specials Only", episodes={0: 2})
        for key in [(1, 1), (1, 2)]:  # the unwatched special does not count
            add_progress(s, user.id, episode_id=done[key].id, watched=True, watched_at=T0)
        add_progress(s, user.id, episode_id=half[(1, 1)].id, watched=True, watched_at=T0)
        for episode in half.values():
            add_progress(s, admin.id, episode_id=episode.id, watched=True, watched_at=T0)
    titles = lambda page: [x["title"] for x in page["items"]]  # noqa: E731
    watched = await _get(client, user, "/api/shows?watched=true")
    assert (titles(watched), watched["total"]) == (["Dark"], 1)
    unwatched = await _get(client, user, "/api/shows?watched=false")
    assert (titles(unwatched), unwatched["total"]) == (["Lost", "Monk"], 2)
    assert len((await _get(client, user, "/api/shows"))["items"]) == 4
    assert titles(await _get(client, admin, "/api/shows?watched=true")) == ["Lost"]
    r = await client.get("/api/shows?watched=0", headers=user.headers)
    assert (r.status_code, r.json()["error"]["code"]) == (422, "validation_error")
