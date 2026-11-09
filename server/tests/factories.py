"""Row factories for catalog/watch tests (and the perf seed). All take an open write session.

- ``add_film(s, title, ...)`` -> ``m.Film`` with ``files`` present media files (default 1).
- ``add_file(s, film_id=..., episode_ids=(...), ...)`` -> ``m.MediaFile`` (probe JSON from
  ``probe_json(...)``; ``missing=True`` sets ``missing_since``; ``root=``/``rel=``/``library=``
  place it, default ``/media/{library}/{n}/{name}``).
- ``add_show(s, title, episodes={season: count}, ...)`` -> ``(m.Show, {(s, e): m.Episode})``,
  each episode with one present file.
- ``add_progress(s, profile_id, film_id=/episode_id=, ...)``.
- ``add_person(s, name, ...)`` -> ``m.Person`` (unique ``tmdb_id`` by default);
  ``add_cast_credit(s, person, film=/show=, character=)`` (local credit, next ``order``);
  ``add_person_credit(s, person, media_type, tmdb_id, character=)`` (TMDB credit).
- ``add_profile(s, name, avatar_id=, is_admin=, pin_hash=)`` -> ``m.Profile`` (non-admin, no PIN).
- ``add_watchlist_entry(s, profile_id, film=/show=, added_at=)`` ("Mi lista", default ``T0``).
- ``add_rating(s, profile_id, film=/show=, stars_x2=8, rated_at=)`` and
  ``add_rating_dismissal(s, profile_id, film=/show=, dismissed_at=)`` (default ``T0``).
- ``add_track_choice(s, profile_id, film=/show=, audio=("eng", None, 6), subtitles=None,
  updated_at=)`` (audio-only by default; ``subtitles`` = ``"off"`` or a dict of track traits).
"""

from datetime import UTC, date, datetime, timedelta
from typing import Any, Literal

from sqlalchemy.orm import Session

from hometv.api.schemas import AudioChoice, SubtitleChoice, TrackChoice
from hometv.db import models as m
from hometv.scanner import ensure_libraries
from hometv.text import normalize_title, search_text, sort_title
from hometv.track_choices import choice_columns

T0 = datetime(2026, 1, 1, tzinfo=UTC)
_WIDTHS = {2160: 3840, 1080: 1920, 720: 1280, 480: 854}
_counter = {"n": 0}


def _next() -> int:
    _counter["n"] += 1
    return _counter["n"]


def probe_json(
    *,
    container: str = "mkv",
    duration: float | None = 5400.0,
    codec: str = "h264",
    width: int | None = 1920,
    height: int | None = 1080,
    hdr: str | None = None,
    audio: list[dict[str, Any]] | None = None,
    subtitles: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    return {
        "container": container,
        "duration_seconds": duration,
        "start_seconds": 0.0,
        "bitrate": None,
        "video": {
            "index": 0,
            "codec": codec,
            "profile": None,
            "width": width,
            "height": height,
            "pix_fmt": None,
            "frame_rate": 23.976,
            "hdr": hdr,
        },
        "audio": audio
        if audio is not None
        else [
            {
                "index": 1,
                "codec": "aac",
                "channels": 2,
                "channel_layout": "stereo",
                "language": "eng",
                "title": None,
                "default": True,
            }
        ],
        "subtitles": subtitles or [],
    }


def libraries(s: Session) -> dict[str, int]:
    return ensure_libraries(s)


def add_file(
    s: Session,
    *,
    film_id: int | None = None,
    episode_ids: tuple[int, ...] | list[int] = (),
    name: str | None = None,
    height: int | None = 1080,
    size: int = 1_000_000,
    duration: float | None = 5400.0,
    part_number: int | None = None,
    missing: bool = False,
    probe: dict[str, Any] | None = None,
    probe_error: str | None = None,
    added_at: datetime | None = None,
    root: str | None = None,
    rel: str | None = None,
    library: str | None = None,
) -> m.MediaFile:
    libs = libraries(s)
    n = _next()
    name = name or f"file{n}.mkv"
    library = library or ("films" if film_id is not None or not episode_ids else "shows")
    root = root or f"/media/{library}"
    rel = rel or f"{n}/{name}"
    if probe_error is not None:
        probe = None
    elif probe is None:
        probe = probe_json(duration=duration, height=height, width=_WIDTHS.get(height or 0))
    video = (probe or {}).get("video") or {}
    row = m.MediaFile(
        library_id=libs[library],
        root_path=root,
        path=f"{root.rstrip('/')}/{rel}",
        relative_path=rel,
        size_bytes=size,
        mtime_ns=n,
        container=(probe or {}).get("container"),
        duration_seconds=(probe or {}).get("duration_seconds"),
        video_codec=video.get("codec"),
        width=video.get("width"),
        height=video.get("height"),
        hdr=video.get("hdr"),
        probe=probe,
        probe_error=probe_error,
        film_id=film_id,
        part_number=part_number,
        missing_since=T0 if missing else None,
        added_at=added_at or T0,
    )
    s.add(row)
    s.flush()
    for episode_id in episode_ids:
        s.add(m.MediaFileEpisode(media_file_id=row.id, episode_id=episode_id))
    s.flush()
    return row


def _item_fields(title: str, year: int | None, original_title: str | None) -> dict[str, Any]:
    return {
        "parsed_title": title,
        "parsed_title_norm": normalize_title(title),
        "parsed_year": year,
        "title": title,
        "original_title": original_title,
        "year": year,
        "sort_title": sort_title(title),
        "search_text": search_text(title, original_title),
    }


def add_film(
    s: Session,
    title: str,
    *,
    year: int | None = 2020,
    original_title: str | None = None,
    poster: str | None = None,
    backdrop: str | None = None,
    overview: str | None = None,
    added_at: datetime | None = None,
    genres: tuple[tuple[int, str], ...] = (),
    files: int = 1,
    tmdb_id: int | None = None,
    match_status: str = "matched",
    **extra: Any,
) -> m.Film:
    libs = libraries(s)
    film = m.Film(
        library_id=libs["films"],
        match_status=match_status,
        tmdb_id=tmdb_id,
        poster_path=poster,
        backdrop_path=backdrop,
        overview=overview,
        added_at=added_at or T0,
        **_item_fields(title, year, original_title),
        **extra,
    )
    s.add(film)
    s.flush()
    for genre_id, name in genres:
        if s.get(m.Genre, genre_id) is None:
            s.add(m.Genre(id=genre_id, name=name))
            s.flush()
        s.add(m.FilmGenre(film_id=film.id, genre_id=genre_id))
    for _ in range(files):
        add_file(s, film_id=film.id)
    s.flush()
    return film


def add_show(
    s: Session,
    title: str,
    *,
    year: int | None = 2005,
    episodes: dict[int, int] | None = None,
    poster: str | None = None,
    backdrop: str | None = None,
    overview: str | None = None,
    content_updated_at: datetime | None = None,
    genres: tuple[tuple[int, str], ...] = (),
    match_status: str = "matched",
    with_files: bool = True,
    **extra: Any,
) -> tuple[m.Show, dict[tuple[int, int], m.Episode]]:
    libs = libraries(s)
    show = m.Show(
        library_id=libs["shows"],
        match_status=match_status,
        poster_path=poster,
        backdrop_path=backdrop,
        overview=overview,
        added_at=T0,
        content_updated_at=content_updated_at or T0,
        **_item_fields(title, year, None),
        **extra,
    )
    s.add(show)
    s.flush()
    for genre_id, name in genres:
        if s.get(m.Genre, genre_id) is None:
            s.add(m.Genre(id=genre_id, name=name))
            s.flush()
        s.add(m.ShowGenre(show_id=show.id, genre_id=genre_id))
    result: dict[tuple[int, int], m.Episode] = {}
    for season_number, count in (episodes or {1: 3}).items():
        for number in range(1, count + 1):
            result[(season_number, number)] = add_episode(
                s, show, season_number, number, with_file=with_files
            )
    return show, result


def add_episode(
    s: Session,
    show: m.Show,
    season_number: int,
    episode_number: int,
    *,
    with_file: bool = True,
    title: str | None = None,
    duration: float | None = 1320.0,
) -> m.Episode:
    from sqlalchemy import select

    season = s.scalar(
        select(m.Season).where(m.Season.show_id == show.id, m.Season.season_number == season_number)
    )
    if season is None:
        season = m.Season(
            show_id=show.id,
            season_number=season_number,
            name=f"Temporada {season_number}",
            air_date=date(2005, 1, 1) + timedelta(days=season_number),
        )
        s.add(season)
        s.flush()
    episode = m.Episode(
        season_id=season.id,
        show_id=show.id,
        season_number=season_number,
        episode_number=episode_number,
        title=title or f"Episode {episode_number}",
        added_at=T0,
    )
    s.add(episode)
    s.flush()
    if with_file:
        add_file(s, episode_ids=[episode.id], duration=duration)
    return episode


def add_progress(
    s: Session,
    profile_id: int,
    *,
    film_id: int | None = None,
    episode_id: int | None = None,
    position: float = 0.0,
    duration: float = 5400.0,
    watched: bool = False,
    watched_at: datetime | None = None,
    updated_at: datetime | None = None,
    media_file_id: int | None = None,
) -> m.PlaybackProgress:
    row = m.PlaybackProgress(
        profile_id=profile_id,
        film_id=film_id,
        episode_id=episode_id,
        position_seconds=position,
        duration_seconds=duration,
        watched=watched,
        watched_at=watched_at or (T0 if watched else None),
        updated_at=updated_at or T0,
        media_file_id=media_file_id,
    )
    s.add(row)
    s.flush()
    return row


def add_person(
    s: Session,
    name: str = "Actor",
    *,
    tmdb_id: int | None = None,
    profile_path: str | None = None,
    **extra: Any,
) -> m.Person:
    person = m.Person(
        tmdb_id=tmdb_id if tmdb_id is not None else 9_000_000 + _next(),
        name=name,
        profile_path=profile_path,
        **extra,
    )
    s.add(person)
    s.flush()
    return person


def add_cast_credit(
    s: Session,
    person: m.Person,
    *,
    film: m.Film | None = None,
    show: m.Show | None = None,
    character: str | None = None,
    order: int | None = None,
) -> m.CastCredit:
    row = m.CastCredit(
        person_id=person.id,
        film_id=film.id if film is not None else None,
        show_id=show.id if show is not None else None,
        character=character,
        order=order if order is not None else _next(),
    )
    s.add(row)
    s.flush()
    return row


def add_person_credit(
    s: Session,
    person: m.Person,
    media_type: str,
    tmdb_id: int,
    *,
    character: str | None = None,
) -> m.PersonCredit:
    row = m.PersonCredit(
        person_id=person.id, media_type=media_type, tmdb_id=tmdb_id, character=character
    )
    s.add(row)
    s.flush()
    return row


def add_profile(
    s: Session,
    name: str = "A",
    *,
    avatar_id: str = "avatar-01",
    is_admin: bool = False,
    pin_hash: str | None = None,
) -> m.Profile:
    row = m.Profile(name=name, avatar_id=avatar_id, is_admin=is_admin, pin_hash=pin_hash)
    s.add(row)
    s.flush()
    return row


def add_watchlist_entry(
    s: Session,
    profile_id: int,
    *,
    film: m.Film | None = None,
    show: m.Show | None = None,
    added_at: datetime | None = None,
) -> m.WatchlistEntry:
    row = m.WatchlistEntry(
        profile_id=profile_id,
        film_id=film.id if film is not None else None,
        show_id=show.id if show is not None else None,
        added_at=added_at or T0,
    )
    s.add(row)
    s.flush()
    return row


def add_rating(
    s: Session,
    profile_id: int,
    *,
    film: m.Film | None = None,
    show: m.Show | None = None,
    stars_x2: int = 8,
    rated_at: datetime | None = None,
) -> m.Rating:
    row = m.Rating(
        profile_id=profile_id,
        film_id=film.id if film is not None else None,
        show_id=show.id if show is not None else None,
        stars_x2=stars_x2,
        rated_at=rated_at or T0,
    )
    s.add(row)
    s.flush()
    return row


def add_rating_dismissal(
    s: Session,
    profile_id: int,
    *,
    film: m.Film | None = None,
    show: m.Show | None = None,
    dismissed_at: datetime | None = None,
) -> m.RatingDismissal:
    row = m.RatingDismissal(
        profile_id=profile_id,
        film_id=film.id if film is not None else None,
        show_id=show.id if show is not None else None,
        dismissed_at=dismissed_at or T0,
    )
    s.add(row)
    s.flush()
    return row


SubtitlesArg = Literal["off"] | dict[str, Any] | None


def add_track_choice(
    s: Session,
    profile_id: int,
    *,
    film: m.Film | None = None,
    show: m.Show | None = None,
    audio: tuple[str, str | None, int | None] | None = ("eng", None, 6),
    subtitles: SubtitlesArg = None,
    updated_at: datetime | None = None,
) -> m.TrackChoice:
    """``audio`` = ``(language, title, channels)`` or ``None``; ``subtitles`` = ``"off"``, a dict
    of track traits (``language`` required; ``title`` None, ``forced``/``hearing_impaired`` False,
    ``source`` "embedded", ``kind`` "text" by default) or ``None`` (no part). Audio-only by
    default; columns built by ``track_choices.choice_columns``."""
    sub: SubtitleChoice | None = None
    if subtitles == "off":
        sub = SubtitleChoice(mode="off")
    elif isinstance(subtitles, dict):
        sub = SubtitleChoice(mode="track", source="embedded", kind="text", **subtitles)
    choice = TrackChoice(
        audio=AudioChoice(language=audio[0], title=audio[1], channels=audio[2]) if audio else None,
        subtitles=sub,
    )
    row = m.TrackChoice(
        profile_id=profile_id,
        film_id=film.id if film is not None else None,
        show_id=show.id if show is not None else None,
        **choice_columns(choice),
        updated_at=updated_at or T0,
    )
    s.add(row)
    s.flush()
    return row
