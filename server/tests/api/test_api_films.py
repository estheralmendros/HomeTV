"""GET /api/films, GET /api/films/{id} (PRD §10, REST "Catalog")."""

from datetime import date, timedelta

import pytest

from hometv.db import models as m
from tests.factories import T0, add_file, add_film, add_progress, probe_json

FILM_SUMMARY_KEYS = {
    "type",
    "id",
    "title",
    "year",
    "poster",
    "backdrop",
    "overview",
    "match_status",
    "added_at",
    "progress",
}
MEDIA_FILE_KEYS = {
    "id",
    "file_name",
    "size_bytes",
    "container",
    "duration_seconds",
    "label",
    "video",
    "audio_tracks",
    "subtitle_tracks",
    "part_number",
    "probe_error",
    "added_at",
}


async def _list(client, who, query: str = "") -> dict:
    r = await client.get(f"/api/films{query}", headers=who.headers)
    assert r.status_code == 200, r.text
    return r.json()


def _titles(page: dict) -> list[str]:
    return [x["title"] for x in page["items"]]


async def test_summary_shape_images_and_progress(client, db, user):
    with db.write_session() as s:
        dune = add_film(s, "Dune", year=2021, poster="p1.jpg", backdrop="b1.jpg", overview="Arena")
        bare = add_film(s, "Bare", year=None)
        file_id = s.query(m.MediaFile.id).filter(m.MediaFile.film_id == dune.id).scalar()
        add_progress(
            s, user.id, film_id=dune.id, position=600.0, duration=9000.0, media_file_id=file_id
        )
        dune_id, bare_id = dune.id, bare.id
    page = await _list(client, user)
    assert (page["page"], page["page_size"], page["total"]) == (1, 50, 2)
    by_id = {x["id"]: x for x in page["items"]}
    assert set(by_id[dune_id]) == FILM_SUMMARY_KEYS
    assert by_id[dune_id] == {
        "type": "film",
        "id": dune_id,
        "title": "Dune",
        "year": 2021,
        "poster": {"small": "/api/artwork/w342/p1.jpg", "large": "/api/artwork/w780/p1.jpg"},
        "backdrop": {"small": "/api/artwork/w780/b1.jpg", "large": "/api/artwork/w1280/b1.jpg"},
        "overview": "Arena",
        "match_status": "matched",
        "added_at": "2026-01-01T00:00:00Z",
        "progress": {
            "position_seconds": 600.0,
            "duration_seconds": 9000.0,
            "watched": False,
            "watched_at": None,
            "media_file_id": file_id,
            "updated_at": "2026-01-01T00:00:00Z",
        },
    }
    bare = by_id[bare_id]
    assert (bare["poster"], bare["backdrop"], bare["overview"], bare["progress"]) == (
        None,
        None,
        None,
        None,
    )


async def test_progress_is_per_profile(client, db, user, admin):
    with db.write_session() as s:
        film = add_film(s, "Dune")
        add_progress(s, admin.id, film_id=film.id, position=600.0)
    assert (await _list(client, user))["items"][0]["progress"] is None
    assert (await _list(client, admin))["items"][0]["progress"]["position_seconds"] == 600.0


async def test_search_accent_insensitive_and_original_title(client, db, user):
    with db.write_session() as s:
        add_film(s, "Amélie", original_title="Le Fabuleux Destin d'Amélie Poulain")
        add_film(s, "El viaje de Chihiro", original_title="Sen to Chihiro no Kamikakushi")
        add_film(s, "Spider-Man")
    assert _titles(await _list(client, user, "?q=amelie")) == ["Amélie"]
    assert _titles(await _list(client, user, "?q=AMÉLIE")) == ["Amélie"]
    assert _titles(await _list(client, user, "?q=kamikakushi")) == ["El viaje de Chihiro"]
    assert _titles(await _list(client, user, "?q=spider man")) == []  # normalized "spiderman"
    assert _titles(await _list(client, user, "?q=spider-man")) == ["Spider-Man"]
    assert (await _list(client, user, "?q=zzz"))["total"] == 0


async def test_sorting(client, db, user):
    with db.write_session() as s:
        add_film(s, "The Matrix", year=1999, added_at=T0 + timedelta(days=3))
        add_film(s, "Alien", year=1979, added_at=T0 + timedelta(days=1))
        add_film(s, "Zodiac", year=None, added_at=T0 + timedelta(days=2))
        add_film(s, "Moon", year=2009, added_at=T0)
    assert _titles(await _list(client, user)) == ["Alien", "The Matrix", "Moon", "Zodiac"]
    assert _titles(await _list(client, user, "?order=desc")) == [
        "Zodiac",
        "Moon",
        "The Matrix",
        "Alien",
    ]
    assert _titles(await _list(client, user, "?sort=year")) == [
        "Alien",
        "The Matrix",
        "Moon",
        "Zodiac",
    ]  # nulls last
    assert _titles(await _list(client, user, "?sort=year&order=desc")) == [
        "Moon",
        "The Matrix",
        "Alien",
        "Zodiac",
    ]  # nulls last in both orders
    assert _titles(await _list(client, user, "?sort=added&order=desc")) == [
        "The Matrix",
        "Zodiac",
        "Alien",
        "Moon",
    ]
    assert _titles(await _list(client, user, "?sort=added")) == [
        "Moon",
        "Alien",
        "Zodiac",
        "The Matrix",
    ]


async def test_ties_broken_by_id(client, db, user):
    with db.write_session() as s:
        ids = [add_film(s, "Same", year=2000).id for _ in range(3)]
    for query in ("", "?order=desc", "?sort=year&order=desc", "?sort=added&order=desc"):
        assert [x["id"] for x in (await _list(client, user, query))["items"]] == ids


async def test_year_range_and_genre_filters(client, db, user):
    with db.write_session() as s:
        add_film(s, "A", year=1999, genres=((18, "Drama"),))
        add_film(s, "B", year=2005, genres=((18, "Drama"), (878, "Ciencia ficción")))
        add_film(s, "C", year=2010, genres=((878, "Ciencia ficción"),))
        add_film(s, "D", year=None, genres=((18, "Drama"),))
    assert _titles(await _list(client, user, "?year_from=2005")) == ["B", "C"]
    assert _titles(await _list(client, user, "?year_to=2005")) == ["A", "B"]
    assert _titles(await _list(client, user, "?year_from=2005&year_to=2005")) == ["B"]
    assert _titles(await _list(client, user, "?genre=18")) == ["A", "B", "D"]
    assert _titles(await _list(client, user, "?genre=18&year_from=2000")) == ["B"]
    assert _titles(await _list(client, user, "?genre=99")) == []


async def test_pagination(client, db, user):
    with db.write_session() as s:
        for i in range(5):
            add_film(s, f"Film {i}")
    page = await _list(client, user, "?page=2&page_size=2")
    assert (page["page"], page["page_size"], page["total"]) == (2, 2, 5)
    assert _titles(page) == ["Film 2", "Film 3"]
    assert (await _list(client, user, "?page=9&page_size=2"))["items"] == []
    assert (await _list(client, user, "?page=1000000&page_size=200"))["items"] == []


@pytest.mark.parametrize(
    "query",
    [
        "?page=0",
        "?page=1000001",
        f"?page={2**62}",
        "?page_size=0",
        "?page_size=201",
        "?sort=rating",
        "?order=up",
        "?year_from=1899",
        "?year_to=2101",
        "?year_from=2010&year_to=2000",
        f"?q={'x' * 101}",
        "?q=",
    ],
)
async def test_validation(client, user, query):
    r = await client.get(f"/api/films{query}", headers=user.headers)
    assert r.status_code == 422
    assert r.json()["error"]["code"] == "validation_error"


async def test_missing_files_are_hidden(client, db, user):
    with db.write_session() as s:
        gone = add_film(s, "Gone", files=0)
        add_file(s, film_id=gone.id, missing=True)
        partly = add_film(s, "Partly", files=1)
        hidden_file = add_file(s, film_id=partly.id, missing=True)
        gone_id, partly_id = gone.id, partly.id
    page = await _list(client, user)
    assert _titles(page) == ["Partly"] and page["total"] == 1
    r = await client.get(f"/api/films/{gone_id}", headers=user.headers)
    assert r.status_code == 404 and r.json()["error"]["code"] == "not_found"
    detail = (await client.get(f"/api/films/{partly_id}", headers=user.headers)).json()
    assert hidden_file.id not in [f["id"] for f in detail["media_files"]]
    assert len(detail["media_files"]) == 1


async def test_auth_required(client, user):
    assert (await client.get("/api/films")).status_code == 401
    r = await client.get(f"/api/films?token={user.token}")
    assert r.status_code == 401 and r.json()["error"]["code"] == "unauthorized"


# --- Detail --------------------------------------------------------------------------------------


def _seed_detail(db):
    with db.write_session() as s:
        film = add_film(
            s,
            "Dune",
            year=2021,
            original_title="Dune",
            poster="p.jpg",
            backdrop="b.jpg",
            overview="Arena",
            genres=((878, "Ciencia ficción"), (12, "Aventura")),
            files=0,
            tmdb_id=438631,
            release_date=date(2021, 9, 15),
            tagline="Más allá del miedo",
            runtime_minutes=155,
            vote_average=7.8,
        )
        for order, (name, character, path) in enumerate(
            [("Timothée Chalamet", "Paul Atreides", "tc.jpg"), ("Zendaya", "Chani", None)]
        ):
            person = m.Person(tmdb_id=100 + order, name=name, profile_path=path)
            s.add(person)
            s.flush()
            # Insert in reverse order to check ORDER BY credit order.
            s.add(
                m.CastCredit(
                    person_id=person.id, film_id=film.id, character=character, order=1 - order
                )
            )
        hevc = probe_json(
            codec="hevc",
            width=3840,
            height=2160,
            hdr="hdr10",
            duration=9360.5,
            audio=[
                {
                    "index": 1,
                    "codec": "eac3",
                    "channels": 6,
                    "channel_layout": "5.1(side)",
                    "language": "eng",
                    "title": "Surround",
                    "default": True,
                }
            ],
            subtitles=[
                {
                    "index": 3,
                    "codec": "subrip",
                    "language": "spa",
                    "title": None,
                    "forced": False,
                    "hearing_impaired": False,
                    "default": False,
                    "kind": "text",
                },
                {
                    "index": 4,
                    "codec": "hdmv_pgs_subtitle",
                    "language": "eng",
                    "title": None,
                    "forced": False,
                    "hearing_impaired": False,
                    "default": False,
                    "kind": "image",
                },
            ],
        )
        uhd = add_file(s, film_id=film.id, name="Dune (2021).mkv", size=23_456_789_012, probe=hevc)
        s.add(
            m.SubtitleFile(
                media_file_id=uhd.id,
                path="/media/films/x/Dune.en.forced.srt",
                language="eng",
                forced=True,
                hearing_impaired=False,
                format="srt",
                kind="text",
                size_bytes=10,
                mtime_ns=1,
            )
        )
        hd_small = add_file(s, film_id=film.id, height=1080, size=5)
        hd_big = add_file(s, film_id=film.id, height=1080, size=9)
        broken = add_file(s, film_id=film.id, probe_error="Invalid data")
        part2 = add_file(s, film_id=film.id, part_number=2, height=2160)
        part1 = add_file(s, film_id=film.id, part_number=1, height=720)
        s.flush()
        sub_id = s.query(m.SubtitleFile.id).scalar()
        return film.id, {
            "uhd": uhd.id,
            "hd_small": hd_small.id,
            "hd_big": hd_big.id,
            "broken": broken.id,
            "part1": part1.id,
            "part2": part2.id,
            "sub": sub_id,
        }


async def test_film_detail_shape(client, db, user):
    film_id, ids = _seed_detail(db)
    r = await client.get(f"/api/films/{film_id}", headers=user.headers)
    assert r.status_code == 200
    d = r.json()
    assert set(d) == {
        "type",
        "id",
        "title",
        "original_title",
        "year",
        "release_date",
        "overview",
        "tagline",
        "runtime_minutes",
        "vote_average",
        "genres",
        "poster",
        "backdrop",
        "match_status",
        "tmdb_id",
        "cast",
        "media_files",
        "progress",
        "in_watchlist",
        "added_at",
    }
    assert d["in_watchlist"] is False
    assert (d["release_date"], d["tagline"], d["runtime_minutes"], d["vote_average"]) == (
        "2021-09-15",
        "Más allá del miedo",
        155,
        7.8,
    )
    assert d["tmdb_id"] == 438631 and d["progress"] is None
    assert d["genres"] == [{"id": 12, "name": "Aventura"}, {"id": 878, "name": "Ciencia ficción"}]
    assert d["cast"] == [
        {
            "person_id": d["cast"][0]["person_id"],
            "name": "Zendaya",
            "character": "Chani",
            "profile_image": None,
        },
        {
            "person_id": d["cast"][1]["person_id"],
            "name": "Timothée Chalamet",
            "character": "Paul Atreides",
            "profile_image": {
                "small": "/api/artwork/w185/tc.jpg",
                "large": "/api/artwork/h632/tc.jpg",
            },
        },
    ]
    # Version order: part_number ASC NULLS FIRST, height DESC (null last), size DESC, id ASC.
    assert [f["id"] for f in d["media_files"]] == [
        ids["uhd"],
        ids["hd_big"],
        ids["hd_small"],
        ids["broken"],
        ids["part1"],
        ids["part2"],
    ]
    uhd = d["media_files"][0]
    assert set(uhd) == MEDIA_FILE_KEYS
    assert uhd == {
        "id": ids["uhd"],
        "file_name": "Dune (2021).mkv",
        "size_bytes": 23_456_789_012,
        "container": "mkv",
        "duration_seconds": 9360.5,
        "label": "2160p HEVC HDR10 · EAC3 5.1",
        "video": {
            "codec": "hevc",
            "width": 3840,
            "height": 2160,
            "hdr": "hdr10",
            "frame_rate": 23.976,
        },
        "audio_tracks": [
            {
                "index": 1,
                "codec": "eac3",
                "channels": 6,
                "channel_layout": "5.1(side)",
                "language": "eng",
                "title": "Surround",
                "default": True,
            }
        ],
        "subtitle_tracks": [
            {
                "id": "emb-3",
                "source": "embedded",
                "kind": "text",
                "codec": "subrip",
                "language": "spa",
                "title": None,
                "forced": False,
                "hearing_impaired": False,
                "default": False,
                "url": f"/api/media-files/{ids['uhd']}/subtitles/emb-3.vtt",
            },
            {
                "id": "emb-4",
                "source": "embedded",
                "kind": "image",
                "codec": "hdmv_pgs_subtitle",
                "language": "eng",
                "title": None,
                "forced": False,
                "hearing_impaired": False,
                "default": False,
                "url": None,
            },
            {
                "id": f"ext-{ids['sub']}",
                "source": "external",
                "kind": "text",
                "codec": "srt",
                "language": "eng",
                "title": None,
                "forced": True,
                "hearing_impaired": False,
                "default": False,
                "url": f"/api/media-files/{ids['uhd']}/subtitles/ext-{ids['sub']}.vtt",
            },
        ],
        "part_number": None,
        "probe_error": None,
        "added_at": "2026-01-01T00:00:00Z",
    }
    broken = d["media_files"][3]
    assert broken["label"] == "Desconocido"
    assert (broken["video"], broken["audio_tracks"], broken["subtitle_tracks"]) == (None, [], [])
    assert broken["probe_error"] == "Invalid data" and broken["container"] is None
    assert d["media_files"][4]["part_number"] == 1


async def test_film_detail_cast_limit(client, db, user, app):
    film_id, _ = _seed_detail(db)
    app.state.settings.tmdb.cast_limit = 1
    d = (await client.get(f"/api/films/{film_id}", headers=user.headers)).json()
    assert [c["name"] for c in d["cast"]] == ["Zendaya"]


async def test_film_detail_404(client, user):
    r = await client.get("/api/films/999", headers=user.headers)
    assert r.status_code == 404 and r.json()["error"]["code"] == "not_found"


async def test_watched_filter_is_per_profile(client, db, user, admin):
    """PRD §10 list filters: `watched` (in-progress counts as not watched)."""
    with db.write_session() as s:
        seen = add_film(s, "Alien")
        started = add_film(s, "Blade Runner")
        add_film(s, "Casablanca")
        add_progress(s, user.id, film_id=seen.id, watched=True, watched_at=T0)
        add_progress(s, user.id, film_id=started.id, position=600.0)
        add_progress(s, admin.id, film_id=started.id, watched=True, watched_at=T0)
    watched = await _list(client, user, "?watched=true")
    assert (_titles(watched), watched["total"]) == (["Alien"], 1)
    unwatched = await _list(client, user, "?watched=false")
    assert (_titles(unwatched), unwatched["total"]) == (["Blade Runner", "Casablanca"], 2)
    assert _titles(await _list(client, user)) == ["Alien", "Blade Runner", "Casablanca"]
    assert _titles(await _list(client, admin, "?watched=true")) == ["Blade Runner"]
    for value in ("maybe", "1", "yes", "on", "True"):  # only the literal true|false
        r = await client.get(f"/api/films?watched={value}", headers=user.headers)
        assert (r.status_code, r.json()["error"]["code"]) == (422, "validation_error"), value
