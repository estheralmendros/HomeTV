"""AC (Rev 8): version order is total and identical on every endpoint.

An episode with three versions, 1080p 4 GB (PRD id 9), 2160p 12 GB (id 7) and 1080p 4 GB
(id 5, same size as id 9), returns them as 7, 5, 9 from GET /api/episodes/{id} and from its
/api/home entry, on repeated calls; a version without a video stream sorts last. A film without
parts with the same files returns the same order. Ids are allocated in insertion order here, so
"id 5 < id 9" is kept by inserting id 5's file first.
"""

from tests.factories import add_file, add_film, add_progress, add_show, probe_json

GB = 1_000_000_000


def _no_video_probe() -> dict:
    probe = probe_json()
    probe["video"] = None
    return probe


def _versions(s, **link) -> list[int]:
    """Insert the PRD's versions; returns their ids in the expected order (7, 5, 9, no-video)."""
    no_video = add_file(s, **link, size=20 * GB, probe=_no_video_probe())
    id5 = add_file(s, **link, height=1080, size=4 * GB)
    id7 = add_file(s, **link, height=2160, size=12 * GB)
    id9 = add_file(s, **link, height=1080, size=4 * GB)
    assert id5.id < id9.id
    return [id7.id, id5.id, id9.id, no_video.id]


async def test_episode_versions_same_order_on_detail_and_home(client, db, user):
    with db.write_session() as s:
        _show, eps = add_show(s, "Office", episodes={1: 1}, with_files=False)
        episode = eps[(1, 1)]
        expected = _versions(s, episode_ids=[episode.id])
        add_progress(s, user.id, episode_id=episode.id, position=300.0)
        episode_id = episode.id
    for _ in range(3):
        detail = await client.get(f"/api/episodes/{episode_id}", headers=user.headers)
        assert [f["id"] for f in detail.json()["media_files"]] == expected
        home = (await client.get("/api/home", headers=user.headers)).json()
        (entry,) = [e for e in home["continue_watching"] if e["type"] == "episode"]
        assert [f["id"] for f in entry["media_files"]] == expected
        assert entry["media_files"] == detail.json()["media_files"]


async def test_film_without_parts_same_order(client, db, user):
    with db.write_session() as s:
        film = add_film(s, "Dune", files=0)
        expected = _versions(s, film_id=film.id)
        film_id = film.id
    for _ in range(3):
        r = await client.get(f"/api/films/{film_id}", headers=user.headers)
        assert [f["id"] for f in r.json()["media_files"]] == expected


async def test_film_standalone_then_parts_with_two_part1_encodes(client, db, user):
    """AC: standalone, Part 1 (higher resolution first), Part 1 (lower), Part 2."""
    with db.write_session() as s:
        film = add_film(s, "Kill Bill", files=0)
        part2 = add_file(s, film_id=film.id, part_number=2, height=2160)
        part1_low = add_file(s, film_id=film.id, part_number=1, height=720)
        standalone = add_file(s, film_id=film.id, height=480)
        part1_high = add_file(s, film_id=film.id, part_number=1, height=1080)
        expected = [standalone.id, part1_high.id, part1_low.id, part2.id]
        film_id = film.id
    r = await client.get(f"/api/films/{film_id}", headers=user.headers)
    files = r.json()["media_files"]
    assert [f["id"] for f in files] == expected
    assert [f["part_number"] for f in files] == [None, 1, 1, 2]
