"""Enrichment, merge rules and manual re-match with respx TMDB mocks."""

import asyncio
import json
from datetime import timedelta
from pathlib import Path

import httpx
import pytest
import respx
from sqlalchemy import event, func, select

from hometv.api.errors import ApiError
from hometv.db import models as m
from hometv.metadata.artwork import ArtworkCache
from hometv.metadata.enrich import Enricher, match_item, merge_items, resolve_unmatched
from hometv.metadata.tmdb import TMDB_BASE_URL, TMDB_IMAGE_BASE_URL, TmdbClient
from hometv.scan_report import ScanReport
from hometv.scanner import Scanner
from tests.factories import (
    add_film,
    add_profile,
    add_rating,
    add_rating_dismissal,
    add_show,
    add_track_choice,
    add_watchlist_entry,
)

from .conftest import MediaTree

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures" / "tmdb"


def load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


async def _no_sleep(_s: float) -> None:
    return None


class Tmdb:
    """respx router: search results keyed by (query, language); details by path."""

    def __init__(self, router: respx.MockRouter) -> None:
        self.router = router
        self.searches: dict[tuple[str, str, str], list] = {}
        self.search_delay = 0.0
        self.search_movie = router.get(f"{TMDB_BASE_URL}/search/movie").mock(
            side_effect=self._search("movie")
        )
        self.search_tv = router.get(f"{TMDB_BASE_URL}/search/tv").mock(
            side_effect=self._search("tv")
        )
        self.images = router.get(url__regex=rf"^{TMDB_IMAGE_BASE_URL}/.*").respond(content=b"IMG")

    def _search(self, kind: str):
        async def handler(request: httpx.Request) -> httpx.Response:
            if self.search_delay:
                await asyncio.sleep(self.search_delay)
            q = request.url.params["query"]
            lang = request.url.params["language"]
            return httpx.Response(200, json={"results": self.searches.get((kind, q, lang), [])})

        return handler

    def on_search(self, kind: str, query: str, results: list, language: str = "es-ES") -> None:
        self.searches[(kind, query, language)] = results

    def details(self, path: str, data: dict | int, language: str | None = None):
        params = {"language": language} if language else None
        route = self.router.get(f"{TMDB_BASE_URL}{path}", params=params)
        if isinstance(data, int):
            return route.respond(data)
        return route.respond(json=data)


@pytest.fixture
def tmdb():
    with respx.mock(assert_all_called=False) as router:
        yield Tmdb(router)


@pytest.fixture
async def client():
    c = TmdbClient("k", sleep=_no_sleep, max_rps=1000)
    yield c
    await c.aclose()


@pytest.fixture
def settings(media_settings):
    return media_settings(tmdb={"api_key": "k"}, scan={"missing_grace_days": 0})


@pytest.fixture
def artwork(settings, client) -> ArtworkCache:
    return ArtworkCache(settings.artwork_dir, client)


async def _scan(mdb, settings, stub_probe) -> ScanReport:
    return await Scanner(mdb, settings, probe_fn=stub_probe).run()


async def _enrich(mdb, settings, client, artwork, **kw) -> ScanReport:
    report = ScanReport()
    await Enricher(mdb, settings, client, artwork, report=report, **kw).run()
    return report


def _all(mdb, model, *where):
    with mdb.read_session() as s:
        return list(s.scalars(select(model).where(*where).order_by(*model.__table__.primary_key)))


def _count(mdb, model) -> int:
    with mdb.read_session() as s:
        return s.scalar(select(func.count()).select_from(model))


DUNE_RESULT = load("search_movie_dune.json")["results"][0]


def _dune_routes(tmdb: Tmdb) -> None:
    tmdb.on_search("movie", "Dune", load("search_movie_dune.json")["results"])
    tmdb.details("/movie/438631", load("movie_438631.json"), "es-ES")
    tmdb.details("/movie/438631", load("movie_438631_en.json"), "en-US")


# --- Films ------------------------------------------------------------------------------------


async def test_film_enrichment_stores_details_genres_cast_and_artwork(
    mdb, media: MediaTree, settings, stub_probe, tmdb, client, artwork
):
    media.add("Dune (2021)/Dune (2021).mkv")
    _dune_routes(tmdb)
    await _scan(mdb, settings, stub_probe)

    report = await _enrich(mdb, settings, client, artwork)

    assert report.errors == []
    [film] = _all(mdb, m.Film)
    assert film.match_status == "matched"
    assert film.tmdb_id == 438631
    assert film.imdb_id == "tt1160419"
    assert (film.title, film.original_title, film.year) == ("Dune", "Dune", 2021)
    assert str(film.release_date) == "2021-09-15"
    assert film.overview.startswith("Paul Atreides")  # es-ES empty -> en-US
    assert film.tagline == "Más allá del miedo, el destino aguarda."
    assert film.runtime_minutes == 155
    assert film.vote_average == 7.8
    assert film.poster_path == "d5NXSklXo0qyIYkgV94XAgMIckC.jpg"
    assert film.backdrop_path == "jYEW5xZkZk2WTrdbMGAPFuBqbDc.jpg"
    assert film.metadata_updated_at is not None
    assert film.search_text == "dune dune"
    genres = _all(mdb, m.FilmGenre, m.FilmGenre.film_id == film.id)
    assert sorted(g.genre_id for g in genres) == [12, 878]
    assert {g.name for g in _all(mdb, m.Genre)} == {"Ciencia ficción", "Aventura"}
    credits = _all(mdb, m.CastCredit, m.CastCredit.film_id == film.id)
    assert len(credits) == 15
    assert sorted(c.order for c in credits) == list(range(15))
    assert _count(mdb, m.Person) == 15
    art = settings.artwork_dir
    for size in ("w342", "w780"):
        assert (art / size / "d5NXSklXo0qyIYkgV94XAgMIckC.jpg").read_bytes() == b"IMG"
    for size in ("w780", "w1280"):
        assert (art / size / "jYEW5xZkZk2WTrdbMGAPFuBqbDc.jpg").exists()
    assert not (art / "w185").exists()  # profiles are lazy
    assert not (art / "h632").exists()


async def test_cast_keeps_one_credit_per_person_first_by_billing_order(
    mdb, media: MediaTree, settings, stub_probe, tmdb, client, artwork
):
    """TMDB can credit one person twice (two roles): ``cast`` must not repeat a person_id."""
    media.add("Dune (2021)/Dune (2021).mkv")
    details = load("movie_438631.json")
    cast = details["credits"]["cast"]
    # Actor 19 (order 0) also plays a later role; actor 0 (order 19) also appears earlier.
    cast.append({**cast[19], "character": "Second role", "order": 5})
    cast.append({**cast[0], "character": "Cameo", "order": 1})
    tmdb.on_search("movie", "Dune", load("search_movie_dune.json")["results"])
    tmdb.details("/movie/438631", details, "es-ES")
    tmdb.details("/movie/438631", load("movie_438631_en.json"), "en-US")
    await _scan(mdb, settings, stub_probe)

    await _enrich(mdb, settings, client, artwork)

    [film] = _all(mdb, m.Film)
    credits = _all(mdb, m.CastCredit, m.CastCredit.film_id == film.id)
    person_ids = [c.person_id for c in credits]
    assert len(person_ids) == len(set(person_ids)) == 15
    with mdb.read_session() as s:
        by_tmdb = {
            p.tmdb_id: c for c in credits for p in [s.get(m.Person, c.person_id)] if p is not None
        }
    assert (by_tmdb[cast[19]["id"]].character, by_tmdb[cast[19]["id"]].order) == (
        cast[19]["character"],
        0,
    )
    assert (by_tmdb[1000].character, by_tmdb[1000].order) == ("Cameo", 1)


async def test_cast_persons_are_loaded_and_inserted_in_batches(
    mdb, media: MediaTree, settings, stub_probe, tmdb, client, artwork
):
    """``_write_cast`` loads the cast's persons with one SELECT (no query per member) and inserts
    the new ones in one statement; known persons are updated in place."""
    media.add("Dune (2021)/Dune (2021).mkv")
    _dune_routes(tmdb)
    known = min(load("movie_438631.json")["credits"]["cast"], key=lambda c: c["order"])
    with mdb.write_session() as s:
        s.add(m.Person(tmdb_id=known["id"], name="Old name"))
    await _scan(mdb, settings, stub_probe)
    statements: list[str] = []

    def capture(_conn, _cursor, statement, *_args):
        statements.append(" ".join(statement.split()).lower())

    event.listen(mdb.write_engine, "before_cursor_execute", capture)
    try:
        report = await _enrich(mdb, settings, client, artwork)
    finally:
        event.remove(mdb.write_engine, "before_cursor_execute", capture)

    assert report.errors == []
    assert len([q for q in statements if q.startswith("select") and "from person" in q]) == 1
    assert len([q for q in statements if q.startswith("insert into person")]) == 1
    assert _count(mdb, m.Person) == 15
    [person] = _all(mdb, m.Person, m.Person.tmdb_id == known["id"])
    assert person.name == known["name"]
    [film] = _all(mdb, m.Film)
    assert len(_all(mdb, m.CastCredit, m.CastCredit.film_id == film.id)) == 15


async def test_spirited_away_matches_via_fallback_language_search(
    mdb, media: MediaTree, settings, stub_probe, tmdb, client, artwork
):
    media.add("Spirited Away (2001)/Spirited Away (2001).mkv")
    media.add("El viaje de Chihiro (2001).mkv")
    es = load("search_movie_chihiro_es.json")["results"]
    en = load("search_movie_chihiro_en.json")["results"]
    tmdb.on_search("movie", "Spirited Away", es)  # es-ES search returns the Spanish title
    tmdb.on_search("movie", "Spirited Away", en, language="en-US")
    tmdb.on_search("movie", "El viaje de Chihiro", es)
    tmdb.details("/movie/129", load("movie_129.json"))
    await _scan(mdb, settings, stub_probe)

    await _enrich(mdb, settings, client, artwork, concurrency=1)

    films = _all(mdb, m.Film)
    assert len(films) == 1  # the second one merged into the first
    assert films[0].tmdb_id == 129
    assert _count(mdb, m.MediaFile) == 2
    en_queries = [
        c.request.url.params["query"]
        for c in tmdb.search_movie.calls
        if c.request.url.params["language"] == "en-US"
    ]
    assert en_queries == ["Spirited Away"]


async def test_no_match_marks_unmatched_and_is_not_retried(
    mdb, media: MediaTree, settings, stub_probe, tmdb, client, artwork
):
    media.add("Arrival (2016).mkv")
    media.show("The Office/Season 2/The.Office.S02E03.mkv")
    tmdb.on_search("tv", "The Office", load("search_tv_office.json")["results"])
    await _scan(mdb, settings, stub_probe)

    report = await _enrich(mdb, settings, client, artwork)

    [film] = _all(mdb, m.Film)
    [show] = _all(mdb, m.Show)
    assert film.match_status == "unmatched"
    assert show.match_status == "unmatched"
    entries = {(e.film_id, e.show_id, e.reason, e.detail) for e in _all(mdb, m.UnmatchedEntry)}
    assert entries == {
        (film.id, None, "no_tmdb_results", None),
        (None, show.id, "low_confidence", "2 exact-title candidates: 2316, 2996"),
    }
    assert report.counts["unmatched"] == 2
    calls = len(tmdb.search_movie.calls) + len(tmdb.search_tv.calls)

    await _enrich(mdb, settings, client, artwork)
    assert len(tmdb.search_movie.calls) + len(tmdb.search_tv.calls) == calls


async def test_transient_failure_keeps_pending_and_next_run_retries(
    mdb, media: MediaTree, settings, stub_probe, tmdb, client, artwork
):
    media.add("Dune (2021)/Dune (2021).mkv")
    await _scan(mdb, settings, stub_probe)
    tmdb.search_movie.mock(side_effect=None).respond(503)

    report = await _enrich(mdb, settings, client, artwork)

    assert "tmdb_unavailable: Dune" in report.errors
    assert _all(mdb, m.Film)[0].match_status == "pending"
    assert _count(mdb, m.UnmatchedEntry) == 0

    tmdb.search_movie.mock(side_effect=tmdb._search("movie"))
    _dune_routes(tmdb)
    await _enrich(mdb, settings, client, artwork)
    assert _all(mdb, m.Film)[0].match_status == "matched"


async def test_circuit_opens_after_five_consecutive_failures(
    mdb, media: MediaTree, settings, stub_probe, tmdb, client, artwork
):
    for title in ("Alien (1979)", "Brazil (1985)", "Cars (2006)", "Dune (2021)", "Heat (1995)"):
        media.add(f"{title}.mkv")
    media.add("Fargo (1996).mkv")
    await _scan(mdb, settings, stub_probe)
    tmdb.search_movie.mock(side_effect=None).respond(500)

    report = await _enrich(mdb, settings, client, artwork, concurrency=1)

    assert tmdb.search_movie.call_count == 5 * 4  # 5 items x (1 + 3 retries), then no requests
    assert "tmdb_circuit_open" in report.errors
    assert {f.match_status for f in _all(mdb, m.Film)} == {"pending"}


async def test_401_stops_enrichment_and_records_auth_failure(
    mdb, media: MediaTree, settings, stub_probe, tmdb, client, artwork
):
    media.add("Alien (1979).mkv")
    media.add("Dune (2021).mkv")
    await _scan(mdb, settings, stub_probe)
    tmdb.search_movie.mock(side_effect=None).respond(401)

    report = await _enrich(mdb, settings, client, artwork, concurrency=1)

    assert tmdb.search_movie.call_count == 1
    assert "tmdb_auth_failed" in report.errors
    assert {f.match_status for f in _all(mdb, m.Film)} == {"pending"}


async def test_without_tmdb_client_items_stay_pending(
    mdb, media: MediaTree, settings, stub_probe, artwork
):
    media.add("Dune (2021).mkv")
    await _scan(mdb, settings, stub_probe)
    await _enrich(mdb, settings, None, artwork)
    assert _all(mdb, m.Film)[0].match_status == "pending"


# --- Shows ------------------------------------------------------------------------------------


async def test_show_enrichment_stores_seasons_and_episodes(
    mdb, media: MediaTree, settings, stub_probe, tmdb, client, artwork
):
    media.show("The Office (2005)/Season 2/The.Office.S02E03.mkv")
    media.show("The Office (2005)/Season 2/The.Office.S02E07.mkv")  # absent from TMDB
    tmdb.on_search("tv", "The Office", load("search_tv_office.json")["results"])
    tmdb.details("/tv/2316", load("tv_2316.json"))
    tmdb.details("/tv/2316/season/2", load("tv_2316_season_2.json"), "es-ES")
    tmdb.details("/tv/2316/season/2", load("tv_2316_season_2_en.json"), "en-US")
    await _scan(mdb, settings, stub_probe)

    report = await _enrich(mdb, settings, client, artwork)

    assert report.errors == []
    [show] = _all(mdb, m.Show)
    assert (show.match_status, show.tmdb_id, show.title, show.status) == (
        "matched",
        2316,
        "The Office",
        "Ended",
    )
    assert str(show.first_air_date) == "2005-03-24"
    assert show.year == 2005
    assert [g.genre_id for g in _all(mdb, m.ShowGenre)] == [35]
    assert len(_all(mdb, m.CastCredit, m.CastCredit.show_id == show.id)) == 4
    [season] = _all(mdb, m.Season)
    assert (season.name, season.poster_path, str(season.air_date)) == (
        "Temporada 2",
        "office_s2.jpg",
        "2005-09-20",
    )
    assert season.overview == "Season two of The Office."
    assert season.metadata_fetched_at is not None
    eps = {e.episode_number: e for e in _all(mdb, m.Episode)}
    assert eps[3].title == "Olimpiadas de oficina"
    assert eps[3].overview == "Ryan's first sale..."
    assert eps[3].still_path == "s2e3.jpg"
    assert eps[3].runtime_minutes == 22
    assert eps[7].title is None
    assert not (settings.artwork_dir / "w300").exists()  # stills are lazy


# --- Merge ------------------------------------------------------------------------------------


async def test_two_pending_films_resolving_to_same_id_merge_without_integrity_error(
    mdb, media: MediaTree, settings, stub_probe, tmdb, client, artwork
):
    a = media.add("Dune (2021)/Dune (2021).mkv")
    b = media.add("Dune.mkv")
    tmdb.on_search("movie", "Dune", [DUNE_RESULT])
    tmdb.details("/movie/438631", load("movie_438631.json"))
    tmdb.search_delay = 0.05
    await _scan(mdb, settings, stub_probe)
    assert _count(mdb, m.Film) == 2

    report = await _enrich(mdb, settings, client, artwork)

    assert report.errors == []
    [film] = _all(mdb, m.Film)
    assert film.tmdb_id == 438631
    files = _all(mdb, m.MediaFile)
    assert {f.path for f in files} == {str(a), str(b)}
    assert {f.film_id for f in files} == {film.id}
    aliases = _all(mdb, m.ItemAlias)
    assert len(aliases) == 1
    assert aliases[0].film_id == film.id
    assert aliases[0].parsed_title_norm == "dune"
    assert _count(mdb, m.UnmatchedEntry) == 0

    # A rescan of the loser's spelling links to the survivor, no new item.
    media.touch(b if aliases[0].parsed_year is None else a)
    await _scan(mdb, settings, stub_probe)
    assert _count(mdb, m.Film) == 1


async def test_merge_films_moves_files_and_keeps_latest_progress(
    mdb, media: MediaTree, settings, stub_probe
):
    media.add("Dune (2021).mkv")
    media.add("Dune Part One (2021).mkv")
    await _scan(mdb, settings, stub_probe)
    survivor, loser = _all(mdb, m.Film)
    now = m.utcnow()
    with mdb.write_session() as s:
        p1, p2, p3 = (add_profile(s, n).id for n in ("A", "B", "C"))
        s.get(m.Film, survivor.id).tmdb_id = 438631
        s.add_all(
            [
                # p1: both rows, loser newer -> loser's kept
                m.PlaybackProgress(
                    profile_id=p1,
                    film_id=survivor.id,
                    position_seconds=10,
                    updated_at=now - timedelta(hours=2),
                ),
                m.PlaybackProgress(
                    profile_id=p1,
                    film_id=loser.id,
                    position_seconds=20,
                    updated_at=now - timedelta(hours=1),
                ),
                # p2: both rows, survivor newer -> survivor's kept
                m.PlaybackProgress(
                    profile_id=p2, film_id=survivor.id, position_seconds=30, updated_at=now
                ),
                m.PlaybackProgress(
                    profile_id=p2,
                    film_id=loser.id,
                    position_seconds=40,
                    updated_at=now - timedelta(days=1),
                ),
                # p3: only loser -> moved
                m.PlaybackProgress(
                    profile_id=p3,
                    film_id=loser.id,
                    position_seconds=50,
                    updated_at=now - timedelta(days=3),
                ),
            ]
        )
        s.add(m.UnmatchedEntry(film_id=loser.id, reason="low_confidence"))

    with mdb.write_session() as s:
        merge_items(s, "film", s.get(m.Film, loser.id), s.get(m.Film, survivor.id))

    assert [f.id for f in _all(mdb, m.Film)] == [survivor.id]
    assert {f.film_id for f in _all(mdb, m.MediaFile)} == {survivor.id}
    progress = {p.profile_id: p for p in _all(mdb, m.PlaybackProgress)}
    assert {p.film_id for p in progress.values()} == {survivor.id}
    assert sorted(p.position_seconds for p in progress.values()) == [20, 30, 50]
    assert _count(mdb, m.UnmatchedEntry) == 0
    [alias] = _all(mdb, m.ItemAlias)
    assert (alias.parsed_title_norm, alias.parsed_year, alias.film_id) == (
        loser.parsed_title_norm,
        2021,
        survivor.id,
    )


async def test_merge_shows_with_overlapping_seasons_and_episodes(
    mdb, media: MediaTree, settings, stub_probe
):
    media.show("The Office (2005)/Season 1/The.Office.S01E01.mkv")
    media.show("The Office (2005)/Season 2/The.Office.S02E01.mkv")
    media.show("The Office US/Season 2/The.Office.US.S02E01.mkv")  # overlapping episode
    media.show("The Office US/Season 2/The.Office.US.S02E02.mkv")  # new episode in season
    media.show("The Office US/Season 3/The.Office.US.S03E01.mkv")  # new season
    await _scan(mdb, settings, stub_probe)
    survivor, loser = _all(mdb, m.Show)
    with mdb.write_session() as s:
        s.get(m.Show, survivor.id).tmdb_id = 2316
        for season in s.scalars(select(m.Season)):
            season.metadata_fetched_at = m.utcnow()
        pid = add_profile(s, "Esther").id
        s2e1 = {
            e.show_id: e.id
            for e in s.scalars(
                select(m.Episode).where(m.Episode.season_number == 2, m.Episode.episode_number == 1)
            )
        }
        now = m.utcnow()
        s.add(
            m.PlaybackProgress(
                profile_id=pid,
                episode_id=s2e1[survivor.id],
                position_seconds=1,
                updated_at=now - timedelta(days=1),
            )
        )
        s.add(
            m.PlaybackProgress(
                profile_id=pid, episode_id=s2e1[loser.id], position_seconds=2, updated_at=now
            )
        )

    with mdb.write_session() as s:
        merge_items(s, "show", s.get(m.Show, loser.id), s.get(m.Show, survivor.id))

    assert [sh.id for sh in _all(mdb, m.Show)] == [survivor.id]
    seasons = {se.season_number: se for se in _all(mdb, m.Season)}
    assert sorted(seasons) == [1, 2, 3]
    assert {se.show_id for se in seasons.values()} == {survivor.id}
    assert seasons[3].metadata_fetched_at is None  # moved season refetched later
    assert seasons[2].metadata_fetched_at is not None
    eps = _all(mdb, m.Episode)
    assert sorted((e.season_number, e.episode_number) for e in eps) == [
        (1, 1),
        (2, 1),
        (2, 2),
        (3, 1),
    ]
    assert {e.show_id for e in eps} == {survivor.id}
    s2e1 = next(e for e in eps if (e.season_number, e.episode_number) == (2, 1))
    links = _all(mdb, m.MediaFileEpisode, m.MediaFileEpisode.episode_id == s2e1.id)
    assert len(links) == 2  # both files are versions of the survivor episode
    [progress] = _all(mdb, m.PlaybackProgress)
    assert (progress.episode_id, progress.position_seconds) == (s2e1.id, 2)


def _seed_merge_watchlist(mdb, kind: str):
    """Loser + survivor of ``kind`` and four profiles' entries: loser only (A), both with the
    loser earlier (B), both with the survivor earlier (C), survivor only (D)."""
    t0 = m.utcnow() - timedelta(days=10)
    with mdb.write_session() as s:
        if kind == "film":
            survivor, loser = add_film(s, "Dune"), add_film(s, "Dune Part One")
        else:
            survivor, loser = add_show(s, "The Office")[0], add_show(s, "The Office US")[0]
        a, b, c, d = (add_profile(s, n).id for n in ("A", "B", "C", "D"))
        for pid, item, days in [
            (a, loser, 1),
            (b, loser, 2),
            (b, survivor, 3),
            (c, loser, 5),
            (c, survivor, 4),
            (d, survivor, 6),
        ]:
            add_watchlist_entry(s, pid, **{kind: item}, added_at=t0 + timedelta(days=days))
        ids = {
            (e.profile_id, e.film_id or e.show_id): e.id
            for e in s.scalars(select(m.WatchlistEntry))
        }
        return loser.id, survivor.id, (a, b, c, d), t0, ids


@pytest.mark.parametrize("kind", ["film", "show"])
def test_merge_moves_watchlist_entries_keeping_the_earlier_added_at(mdb, kind):
    loser, survivor, (a, b, c, d), t0, ids = _seed_merge_watchlist(mdb, kind)
    model = m.Film if kind == "film" else m.Show
    with mdb.write_session() as s:
        merge_items(s, kind, s.get(model, loser), s.get(model, survivor))

    entries = {e.profile_id: e for e in _all(mdb, m.WatchlistEntry)}
    assert len(_all(mdb, m.WatchlistEntry)) == 4  # one per profile
    column = "film_id" if kind == "film" else "show_id"
    assert {getattr(e, column) for e in entries.values()} == {survivor}
    assert {e.film_id if kind == "show" else e.show_id for e in entries.values()} == {None}
    assert {pid: e.added_at for pid, e in entries.items()} == {
        a: t0 + timedelta(days=1),  # moved
        b: t0 + timedelta(days=2),  # loser's earlier date
        c: t0 + timedelta(days=4),  # survivor's earlier date
        d: t0 + timedelta(days=6),  # unchanged
    }
    assert entries[a].id == ids[(a, loser)]
    assert entries[d].id == ids[(d, survivor)]


def _seed_merge_ratings(mdb, kind: str):
    """Loser + survivor of ``kind``; four profiles with ratings and dismissals: loser only (A),
    both with the loser newer/earlier (B), both with the survivor newer/earlier (C), survivor
    only (D). Rating ``stars_x2`` encodes the row (loser odd, survivor even)."""
    t0 = m.utcnow() - timedelta(days=10)
    with mdb.write_session() as s:
        if kind == "film":
            survivor, loser = add_film(s, "Dune"), add_film(s, "Dune Part One")
        else:
            survivor, loser = add_show(s, "The Office")[0], add_show(s, "The Office US")[0]
        a, b, c, d = (add_profile(s, n).id for n in ("A", "B", "C", "D"))
        for pid, item, days, x2 in [
            (a, loser, 1, 1),
            (b, loser, 3, 3),  # B: the loser's rating is newer, its dismissal later
            (b, survivor, 2, 2),
            (c, loser, 4, 5),  # C: the survivor's rating is newer, its dismissal later
            (c, survivor, 5, 4),
            (d, survivor, 6, 6),
        ]:
            when = t0 + timedelta(days=days)
            add_rating(s, pid, **{kind: item}, stars_x2=x2, rated_at=when)
            add_rating_dismissal(s, pid, **{kind: item}, dismissed_at=when)
        ratings = {
            (r.profile_id, r.film_id or r.show_id): r.id for r in s.scalars(select(m.Rating))
        }
        return loser.id, survivor.id, (a, b, c, d), t0, ratings


@pytest.mark.parametrize("kind", ["film", "show"])
def test_merge_moves_ratings_keeping_the_later_one_and_the_survivor_row(mdb, kind):
    loser, survivor, (a, b, c, d), t0, ids = _seed_merge_ratings(mdb, kind)
    model = m.Film if kind == "film" else m.Show
    with mdb.write_session() as s:
        merge_items(s, kind, s.get(model, loser), s.get(model, survivor))

    rows = {r.profile_id: r for r in _all(mdb, m.Rating)}
    assert len(_all(mdb, m.Rating)) == 4  # one per profile
    column = "film_id" if kind == "film" else "show_id"
    assert {getattr(r, column) for r in rows.values()} == {survivor}
    assert {pid: (r.stars_x2, r.rated_at) for pid, r in rows.items()} == {
        a: (1, t0 + timedelta(days=1)),  # moved
        b: (3, t0 + timedelta(days=3)),  # loser newer: copied onto the survivor row
        c: (4, t0 + timedelta(days=5)),  # survivor newer: kept
        d: (6, t0 + timedelta(days=6)),  # unchanged
    }
    assert rows[a].id == ids[(a, loser)]  # re-pointed
    assert rows[b].id == ids[(b, survivor)]  # the survivor row always stays
    assert rows[c].id == ids[(c, survivor)]


def test_merge_rating_tie_keeps_the_survivors(mdb):
    t0 = m.utcnow() - timedelta(days=1)
    with mdb.write_session() as s:
        survivor, loser = add_film(s, "Dune"), add_film(s, "Dune Part One")
        a = add_profile(s, "A").id
        add_rating(s, a, film=loser, stars_x2=3, rated_at=t0)
        kept = add_rating(s, a, film=survivor, stars_x2=8, rated_at=t0).id
        loser_id, survivor_id = loser.id, survivor.id
    with mdb.write_session() as s:
        merge_items(s, "film", s.get(m.Film, loser_id), s.get(m.Film, survivor_id))
    [row] = _all(mdb, m.Rating)
    assert (row.id, row.film_id, row.stars_x2) == (kept, survivor_id, 8)


@pytest.mark.parametrize("kind", ["film", "show"])
def test_merge_moves_dismissals_keeping_the_earlier(mdb, kind):
    loser, survivor, (a, b, c, d), t0, _ = _seed_merge_ratings(mdb, kind)
    model = m.Film if kind == "film" else m.Show
    with mdb.write_session() as s:
        merge_items(s, kind, s.get(model, loser), s.get(model, survivor))

    rows = {r.profile_id: r for r in _all(mdb, m.RatingDismissal)}
    assert len(_all(mdb, m.RatingDismissal)) == 4
    column = "film_id" if kind == "film" else "show_id"
    assert {getattr(r, column) for r in rows.values()} == {survivor}
    assert {pid: r.dismissed_at for pid, r in rows.items()} == {
        a: t0 + timedelta(days=1),
        b: t0 + timedelta(days=2),  # survivor's earlier date
        c: t0 + timedelta(days=4),  # loser's earlier date
        d: t0 + timedelta(days=6),
    }


def _seed_merge_choices(mdb, kind: str):
    """Loser + survivor of ``kind``; four profiles with track choices: loser only (A), both with
    the loser newer (B), both with the survivor newer (C), survivor only (D). The loser rows are
    audio-only (channels = the row), the survivor rows subtitles-only ("off"), so a mixed result
    would show."""
    t0 = m.utcnow() - timedelta(days=10)
    with mdb.write_session() as s:
        if kind == "film":
            survivor, loser = add_film(s, "Dune"), add_film(s, "Dune Part One")
        else:
            survivor, loser = add_show(s, "The Office")[0], add_show(s, "The Office US")[0]
        a, b, c, d = (add_profile(s, n).id for n in ("A", "B", "C", "D"))
        for pid, item, days in [
            (a, loser, 1),
            (b, loser, 3),  # B: the loser's choice is newer
            (b, survivor, 2),
            (c, loser, 4),  # C: the survivor's choice is newer
            (c, survivor, 5),
            (d, survivor, 6),
        ]:
            is_loser = item is loser
            add_track_choice(
                s,
                pid,
                **{kind: item},
                audio=("eng", "Original", days) if is_loser else None,
                subtitles=None if is_loser else "off",
                updated_at=t0 + timedelta(days=days),
            )
        ids = {
            (r.profile_id, r.film_id or r.show_id): r.id for r in s.scalars(select(m.TrackChoice))
        }
        return loser.id, survivor.id, (a, b, c, d), t0, ids


def _parts(row: m.TrackChoice) -> tuple[object, ...]:
    return tuple(getattr(row, column) for column in m.TRACK_CHOICE_PART_COLUMNS)


@pytest.mark.parametrize("kind", ["film", "show"])
def test_merge_moves_track_choices_keeping_the_later_whole_choice(mdb, kind):
    loser, survivor, (a, b, c, d), t0, ids = _seed_merge_choices(mdb, kind)
    model = m.Film if kind == "film" else m.Show
    with mdb.write_session() as s:
        merge_items(s, kind, s.get(model, loser), s.get(model, survivor))

    rows = {r.profile_id: r for r in _all(mdb, m.TrackChoice)}
    assert len(_all(mdb, m.TrackChoice)) == 4  # one per profile
    column = "film_id" if kind == "film" else "show_id"
    assert {getattr(r, column) for r in rows.values()} == {survivor}
    audio = ("eng", "Original")
    no_subs = (None, None, None, False, False, None, None)
    off = (None, None, None, "off", None, None, False, False, None, None)
    assert {pid: (_parts(r), r.updated_at) for pid, r in rows.items()} == {
        a: ((*audio, 1, *no_subs), t0 + timedelta(days=1)),  # moved
        b: ((*audio, 3, *no_subs), t0 + timedelta(days=3)),  # loser newer: copied as a whole
        c: (off, t0 + timedelta(days=5)),  # survivor newer: kept as a whole
        d: (off, t0 + timedelta(days=6)),  # unchanged
    }
    assert rows[a].id == ids[(a, loser)]  # re-pointed
    assert rows[b].id == ids[(b, survivor)]  # the survivor row always stays
    assert rows[c].id == ids[(c, survivor)]


def test_merge_track_choice_tie_keeps_the_survivors(mdb):
    t0 = m.utcnow() - timedelta(days=1)
    with mdb.write_session() as s:
        survivor, loser = add_film(s, "Dune"), add_film(s, "Dune Part One")
        a = add_profile(s, "A").id
        add_track_choice(s, a, film=loser, audio=("spa", None, 2), updated_at=t0)
        kept = add_track_choice(s, a, film=survivor, audio=("eng", None, 6), updated_at=t0).id
        loser_id, survivor_id = loser.id, survivor.id
    with mdb.write_session() as s:
        merge_items(s, "film", s.get(m.Film, loser_id), s.get(m.Film, survivor_id))
    [row] = _all(mdb, m.TrackChoice)
    assert (row.id, row.film_id, row.audio_language) == (kept, survivor_id, "eng")


async def test_admin_match_during_fetch_wins_over_stale_enrichment(
    mdb, media: MediaTree, settings, stub_probe, tmdb, client, artwork
):
    media.add("Dune (2021).mkv")
    await _scan(mdb, settings, stub_probe)
    [film] = _all(mdb, m.Film)
    tmdb.on_search("movie", "Dune", [DUNE_RESULT])

    def admin_matches_meanwhile(_request):
        with mdb.write_session() as s:
            row = s.get(m.Film, film.id)
            row.tmdb_id, row.match_status, row.title = 841, "matched", "Dune (1984)"
        return httpx.Response(200, json=load("movie_438631.json"))

    tmdb.router.get(f"{TMDB_BASE_URL}/movie/438631").mock(side_effect=admin_matches_meanwhile)

    await _enrich(mdb, settings, client, artwork)

    [after] = _all(mdb, m.Film)
    assert (after.tmdb_id, after.title) == (841, "Dune (1984)")


# --- Manual re-match --------------------------------------------------------------------------


async def test_match_item_refetches_and_merges(
    mdb, media: MediaTree, settings, stub_probe, tmdb, client, artwork
):
    media.add("Dune (2021).mkv")
    media.add("Duna (2021).mkv")
    _dune_routes(tmdb)
    await _scan(mdb, settings, stub_probe)
    dune, duna = _all(mdb, m.Film)
    with mdb.write_session() as s:
        a, b = add_profile(s, "A").id, add_profile(s, "B").id
        kept = add_watchlist_entry(s, a, film=s.get(m.Film, dune.id)).id
        add_watchlist_entry(s, b, film=s.get(m.Film, duna.id))
        kept_rating = add_rating(s, a, film=s.get(m.Film, dune.id), stars_x2=7).id
        add_rating(s, b, film=s.get(m.Film, duna.id), stars_x2=2)
        kept_dismissal = add_rating_dismissal(s, a, film=s.get(m.Film, dune.id)).id
        kept_choice = add_track_choice(s, a, film=s.get(m.Film, dune.id)).id
        add_track_choice(s, b, film=s.get(m.Film, duna.id), audio=None, subtitles="off")

    assert await match_item(mdb, settings, client, artwork, "film", dune.id, 438631) == dune.id
    assert [(r.id, r.film_id) for r in _all(mdb, m.TrackChoice, m.TrackChoice.profile_id == a)] == [
        (kept_choice, dune.id)
    ]
    # a re-match without a duplicate keeps the entry
    assert [
        (e.id, e.film_id) for e in _all(mdb, m.WatchlistEntry, m.WatchlistEntry.profile_id == a)
    ] == [(kept, dune.id)]
    assert [
        (r.id, r.film_id, r.stars_x2) for r in _all(mdb, m.Rating, m.Rating.profile_id == a)
    ] == [(kept_rating, dune.id, 7)]
    assert [r.id for r in _all(mdb, m.RatingDismissal)] == [kept_dismissal]
    assert _all(mdb, m.Film)[0].match_status == "matched"
    assert len(_all(mdb, m.CastCredit)) == 15
    # matching again refreshes (cast cleared first, no duplicates)
    await match_item(mdb, settings, client, artwork, "film", dune.id, 438631)
    assert len(_all(mdb, m.CastCredit)) == 15

    survivor = await match_item(mdb, settings, client, artwork, "film", duna.id, 438631)
    assert survivor == dune.id
    assert [f.id for f in _all(mdb, m.Film)] == [dune.id]
    entries = _all(mdb, m.WatchlistEntry)
    assert sorted((e.profile_id, e.film_id) for e in entries) == [(a, dune.id), (b, dune.id)]
    assert kept in {e.id for e in entries}
    assert sorted((r.profile_id, r.film_id, r.stars_x2) for r in _all(mdb, m.Rating)) == [
        (a, dune.id, 7),
        (b, dune.id, 2),
    ]
    assert sorted(
        (r.profile_id, r.film_id, r.audio_language, r.subtitle_mode)
        for r in _all(mdb, m.TrackChoice)
    ) == [(a, dune.id, "eng", None), (b, dune.id, None, "off")]


async def test_match_item_errors(
    mdb, media: MediaTree, settings, stub_probe, tmdb, client, artwork
):
    media.add("Dune (2021).mkv")
    await _scan(mdb, settings, stub_probe)
    [film] = _all(mdb, m.Film)
    tmdb.details("/movie/1", 404)
    tmdb.details("/movie/2", 503)

    async def code(coro):
        with pytest.raises(ApiError) as exc:
            await coro
        return exc.value.status_code, exc.value.code

    assert await code(match_item(mdb, settings, client, artwork, "film", 999, 1)) == (
        404,
        "not_found",
    )
    assert await code(match_item(mdb, settings, client, artwork, "film", film.id, 1)) == (
        404,
        "tmdb_not_found",
    )
    assert await code(match_item(mdb, settings, client, artwork, "film", film.id, 2)) == (
        502,
        "tmdb_unavailable",
    )
    assert await code(match_item(mdb, settings, None, artwork, "film", film.id, 1)) == (
        503,
        "tmdb_not_configured",
    )


async def test_match_show_resets_and_refetches_seasons(
    mdb, media: MediaTree, settings, stub_probe, tmdb, client, artwork
):
    media.show("Office/Season 2/Office.S02E03.mkv")
    tmdb.details("/tv/2316", load("tv_2316.json"))
    tmdb.details("/tv/2316/season/2", load("tv_2316_season_2.json"))
    await _scan(mdb, settings, stub_probe)
    [show] = _all(mdb, m.Show)

    assert await match_item(mdb, settings, client, artwork, "show", show.id, 2316) == show.id

    [season] = _all(mdb, m.Season)
    assert season.name == "Temporada 2"
    assert season.metadata_fetched_at is not None
    assert {e.title for e in _all(mdb, m.Episode)} == {"Olimpiadas de oficina"}


async def test_resolve_multi_part_links_all_parts_and_survives_rescan(
    mdb, media: MediaTree, settings, stub_probe, tmdb, client, artwork
):
    cd1 = media.add("Kill Bill Vol 1 (2003)/Kill.Bill.Vol.1.2003.cd1.avi")
    media.add("Kill Bill Vol 1 (2003)/Kill.Bill.Vol.1.2003.cd2.avi")
    tmdb.details("/movie/24", {**load("movie_129.json"), "id": 24, "title": "Kill Bill: Vol. 1"})
    await _scan(mdb, settings, stub_probe)
    entry = _all(mdb, m.UnmatchedEntry)[0]
    with mdb.read_session() as s:
        count_before = s.scalar(select(func.count()).select_from(m.UnmatchedEntry))
    assert count_before == 2

    target = await resolve_unmatched(mdb, settings, client, artwork, entry.id, 24)

    [film] = _all(mdb, m.Film)
    assert target == ("film", film.id)
    assert (film.tmdb_id, film.match_status, film.title) == (24, "matched", "Kill Bill: Vol. 1")
    files = _all(mdb, m.MediaFile)
    assert sorted((f.part_number, f.film_id) for f in files) == [(1, film.id), (2, film.id)]
    assert all(f.manual_match == {"tmdb_id": 24} for f in files)
    assert _count(mdb, m.UnmatchedEntry) == 0

    media.touch(cd1)
    await _scan(mdb, settings, stub_probe)
    assert {f.film_id for f in _all(mdb, m.MediaFile)} == {film.id}
    assert _count(mdb, m.UnmatchedEntry) == 0
    assert _count(mdb, m.Film) == 1


async def test_resolve_show_file_links_episodes(
    mdb, media: MediaTree, settings, stub_probe, tmdb, client, artwork
):
    f = media.show("The Office/Office 16-17.mkv")  # no season -> unparseable
    tmdb.details("/tv/2316", load("tv_2316.json"))
    tmdb.details("/tv/2316/season/2", load("tv_2316_season_2.json"))
    await _scan(mdb, settings, stub_probe)
    [entry] = _all(mdb, m.UnmatchedEntry)

    kind, show_id = await resolve_unmatched(
        mdb, settings, client, artwork, entry.id, 2316, season_number=2, episode_numbers=[16, 17]
    )

    assert kind == "show"
    [show] = _all(mdb, m.Show)
    assert (show.id, show.tmdb_id, show.match_status) == (show_id, 2316, "matched")
    assert sorted(e.episode_number for e in _all(mdb, m.Episode)) == [16, 17]
    assert _count(mdb, m.MediaFileEpisode) == 2
    [row] = _all(mdb, m.MediaFile)
    assert row.manual_match == {"tmdb_id": 2316, "season": 2, "episodes": [16, 17]}
    assert _count(mdb, m.UnmatchedEntry) == 0
    assert _all(mdb, m.Season)[0].metadata_fetched_at is not None

    media.touch(f)
    await _scan(mdb, settings, stub_probe)
    assert _count(mdb, m.MediaFileEpisode) == 2
    assert _count(mdb, m.UnmatchedEntry) == 0


async def test_resolve_errors(mdb, media: MediaTree, settings, stub_probe, tmdb, client, artwork):
    media.add("Alien (1979).mkv")
    media.show("The Office/Office 16.mkv")
    stub_probe.fail("Alien (1979).mkv")
    await _scan(mdb, settings, stub_probe)
    probe_entry = _all(mdb, m.UnmatchedEntry, m.UnmatchedEntry.reason == "probe_failed")[0]
    show_entry = _all(mdb, m.UnmatchedEntry, m.UnmatchedEntry.reason == "no_season")[0]
    with mdb.write_session() as s:
        film = s.scalar(select(m.Film))
        item_entry = m.UnmatchedEntry(film_id=film.id, reason="low_confidence")
        s.add(item_entry)
        s.flush()
        item_entry_id = item_entry.id

    async def code(coro):
        with pytest.raises(ApiError) as exc:
            await coro
        return exc.value.status_code, exc.value.code

    assert await code(resolve_unmatched(mdb, settings, client, artwork, 999, 1)) == (
        404,
        "not_found",
    )
    assert await code(resolve_unmatched(mdb, settings, client, artwork, item_entry_id, 1)) == (
        409,
        "not_a_file_entry",
    )
    assert await code(resolve_unmatched(mdb, settings, client, artwork, probe_entry.id, 1)) == (
        409,
        "not_resolvable",
    )
    assert await code(resolve_unmatched(mdb, settings, client, artwork, show_entry.id, 2316)) == (
        422,
        "validation_error",
    )
    tmdb.details("/tv/5", 404)
    assert await code(
        resolve_unmatched(
            mdb, settings, client, artwork, show_entry.id, 5, season_number=1, episode_numbers=[1]
        )
    ) == (404, "tmdb_not_found")
