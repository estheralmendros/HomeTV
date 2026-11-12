"""``hometv.track_choices`` (whole-choice upsert on visible titles, subtitle style, the
deleted-profile 401) and the read side in ``catalog`` (film/episode detail, "Seguir viendo"
episodes; one ``FROM track_choice`` statement each)."""

from datetime import timedelta

import pytest
from sqlalchemy import select, text
from sqlalchemy.exc import IntegrityError

from hometv import catalog, track_choices
from hometv.api.errors import ApiError
from hometv.api.schemas import AudioChoice, SubtitleChoice, SubtitleStyle, TrackChoice
from hometv.db import models as m
from hometv.db.engine import Database
from tests.factories import (
    T0,
    add_file,
    add_film,
    add_profile,
    add_progress,
    add_show,
    add_track_choice,
)
from tests.stubs import capture_selects, capture_writes

ENG6 = AudioChoice(language="eng", channels=6)
SPA_TRACK = SubtitleChoice(mode="track", language="spa", source="embedded", kind="text")
OFF = SubtitleChoice(mode="off")


@pytest.fixture
def profiles(mdb: Database) -> tuple[int, int]:
    with mdb.write_session() as s:
        return add_profile(s, "Ana").id, add_profile(s, "Luis").id


def rows(db: Database) -> list[m.TrackChoice]:
    with db.read_session() as s:
        return list(s.scalars(select(m.TrackChoice).order_by(m.TrackChoice.id)))


def test_set_track_choice_upserts_the_whole_choice_and_bumps_updated_at(mdb, profiles):
    a, b = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        show = add_show(s, "Lost")[0]
        add_track_choice(s, a, film=film, audio=("spa", "Original", 2), subtitles="off")
        film_id, show_id = film.id, show.id
    track_choices.set_track_choice(
        mdb, a, "film", film_id, TrackChoice(audio=ENG6, subtitles=SPA_TRACK)
    )
    track_choices.set_track_choice(mdb, a, "show", show_id, TrackChoice(subtitles=OFF))
    track_choices.set_track_choice(mdb, b, "film", film_id, TrackChoice(audio=ENG6))
    found = rows(mdb)
    assert [(r.profile_id, r.film_id, r.show_id) for r in found] == [
        (a, film_id, None),
        (a, None, show_id),
        (b, film_id, None),
    ]
    assert [catalog.choice_out(r) for r in found] == [
        TrackChoice(audio=ENG6, subtitles=SPA_TRACK),
        TrackChoice(subtitles=OFF),
        TrackChoice(audio=ENG6),
    ]
    assert found[0].updated_at > T0
    # A null part in a later PUT clears that part; the same body again still bumps updated_at.
    track_choices.set_track_choice(mdb, a, "film", film_id, TrackChoice(subtitles=SPA_TRACK))
    first = rows(mdb)[0]
    assert catalog.choice_out(first) == TrackChoice(subtitles=SPA_TRACK)
    assert first.audio_language is None and first.audio_channels is None
    with mdb.write_session() as s:
        s.get_one(m.TrackChoice, first.id).updated_at = T0
    track_choices.set_track_choice(mdb, a, "film", film_id, TrackChoice(subtitles=SPA_TRACK))
    assert rows(mdb)[0].updated_at > T0
    assert rows(mdb)[0].id == first.id


def test_unknown_and_hidden_titles_are_not_found(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        hidden_film = add_film(s, "Gone", files=0)
        add_file(s, film_id=hidden_film.id, missing=True)
        hidden_show = add_show(s, "Ghost", with_files=False)[0].id
        cases = [
            ("film", 999, "Film 999 not found"),
            ("film", hidden_film.id, f"Film {hidden_film.id} not found"),
            ("show", 999, "Show 999 not found"),
            ("show", hidden_show, f"Show {hidden_show} not found"),
        ]
    for kind, item_id, message in cases:
        with pytest.raises(ApiError) as exc:
            track_choices.set_track_choice(mdb, a, kind, item_id, TrackChoice(audio=ENG6))
        assert (exc.value.status_code, exc.value.code, exc.value.message) == (
            404,
            "not_found",
            message,
        )
    assert rows(mdb) == []


def test_unmatched_titles_can_be_remembered(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        unmatched = add_film(s, "Home Video", match_status="unmatched").id
        pending = add_show(s, "New Show", match_status="pending")[0].id
    track_choices.set_track_choice(mdb, a, "film", unmatched, TrackChoice(audio=ENG6))
    track_choices.set_track_choice(mdb, a, "show", pending, TrackChoice(subtitles=OFF))
    assert [(r.film_id, r.show_id) for r in rows(mdb)] == [(unmatched, None), (None, pending)]


@pytest.mark.parametrize("kind", ["film", "show"])
def test_set_track_choice_for_a_deleted_profile_is_unauthorized(mdb, profiles, kind):
    a, _ = profiles
    with mdb.write_session() as s:
        item_id = add_film(s, "Dune").id if kind == "film" else add_show(s, "Lost")[0].id
        s.execute(m.Profile.__table__.delete().where(m.Profile.id == a))
    with pytest.raises(ApiError) as exc:
        track_choices.set_track_choice(mdb, a, kind, item_id, TrackChoice(audio=ENG6))
    assert (exc.value.status_code, exc.value.code) == (401, "unauthorized")
    assert rows(mdb) == []


def test_set_track_choice_reraises_integrity_errors_other_than_the_profile_fk(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film_id = add_film(s, "Dune").id
        s.execute(
            text(
                "CREATE TRIGGER reject_choice BEFORE INSERT ON track_choice "
                "BEGIN SELECT RAISE(ABORT, 'rejected by trigger'); END"
            )
        )
    with pytest.raises(IntegrityError):
        track_choices.set_track_choice(mdb, a, "film", film_id, TrackChoice(audio=ENG6))


_STYLE = SubtitleStyle(
    size="large", color="yellow", edge="shadow", edge_color="dark_gray", position="high"
)


def test_set_subtitle_style_updates_the_five_columns_of_that_profile_only(mdb, profiles):
    a, b = profiles
    track_choices.set_subtitle_style(mdb, a, _STYLE)
    with mdb.read_session() as s:
        ana, luis = s.get(m.Profile, a), s.get(m.Profile, b)
        assert ana is not None and luis is not None
        assert (
            ana.subtitle_size,
            ana.subtitle_color,
            ana.subtitle_edge,
            ana.subtitle_edge_color,
            ana.subtitle_position,
        ) == ("large", "yellow", "shadow", "dark_gray", "high")
        assert (luis.subtitle_size, luis.subtitle_color, luis.subtitle_position) == (
            "medium",
            "white",
            "low",
        )


def test_set_subtitle_style_for_a_deleted_profile_is_unauthorized(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        s.execute(m.Profile.__table__.delete().where(m.Profile.id == a))
    with pytest.raises(ApiError) as exc:
        track_choices.set_subtitle_style(mdb, a, _STYLE)
    assert (exc.value.status_code, exc.value.code) == (401, "unauthorized")


# --- Read side (catalog) ----------------------------------------------------------------------


def _choice_selects(statements: list[tuple[str, object]]) -> int:
    return sum("FROM track_choice" in statement for statement, _ in statements)


def test_film_detail_carries_the_requesters_choice(mdb, profiles):
    a, b = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        other = add_film(s, "Alien")
        add_track_choice(s, a, film=film)
        add_track_choice(s, b, film=film, audio=None, subtitles={"language": "spa"})
        film_id, other_id = film.id, other.id
    with mdb.read_session() as s:
        assert catalog.film_detail(s, a, film_id, 10).track_choice == TrackChoice(audio=ENG6)
        assert catalog.film_detail(s, b, film_id, 10).track_choice == TrackChoice(
            subtitles=SPA_TRACK
        )
        assert catalog.film_detail(s, a, other_id, 10).track_choice is None


def test_episode_detail_carries_the_shows_choice(mdb, profiles):
    a, b = profiles
    with mdb.write_session() as s:
        show, eps = add_show(s, "Lost", episodes={1: 2})
        add_track_choice(s, a, show=show, audio=("eng", None, 2), subtitles="off")
        episode_id = eps[(1, 2)].id
    with mdb.read_session() as s:
        assert catalog.episode_detail(s, a, episode_id).track_choice == TrackChoice(
            audio=AudioChoice(language="eng", channels=2), subtitles=OFF
        )
        assert catalog.episode_detail(s, b, episode_id).track_choice is None


def _home_episode_choices(db: Database, profile_id: int) -> dict[str, TrackChoice | None]:
    with db.read_session() as s:
        cards = catalog.home(s, profile_id, 20).continue_watching
    return {c.show_title: c.track_choice for c in cards if c.type == "episode"}


def test_home_episodes_carry_their_shows_choice(mdb, profiles):
    a, b = profiles
    with mdb.write_session() as s:
        lost, lost_eps = add_show(s, "Lost")
        office, office_eps = add_show(s, "Office")
        add_track_choice(s, a, show=lost, subtitles="off")
        add_track_choice(s, b, show=office)  # another profile's row: never shown to Ana
        for i, ep in enumerate((lost_eps[(1, 1)], office_eps[(1, 1)])):
            add_progress(
                s, a, episode_id=ep.id, position=600, duration=1320, updated_at=T0 + timedelta(i)
            )
    assert _home_episode_choices(mdb, a) == {
        "Lost": TrackChoice(audio=ENG6, subtitles=OFF),
        "Office": None,
    }


def test_choice_reads_spend_one_query_each(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        show, eps = add_show(s, "Lost")
        add_track_choice(s, a, film=film)
        add_track_choice(s, a, show=show)
        add_progress(s, a, episode_id=eps[(1, 1)].id, position=600, duration=1320)
        film_id, show_id, episode_id = film.id, show.id, eps[(1, 1)].id
    counts = {}
    with mdb.read_session() as s:
        for name, call in {
            "film": lambda: catalog.film_detail(s, a, film_id, 10),
            "episode": lambda: catalog.episode_detail(s, a, episode_id),
            "home": lambda: catalog.home(s, a, 20),
            "show": lambda: catalog.show_detail(s, a, show_id, 10),
            "season": lambda: catalog.season_detail(s, a, show_id, 1),
        }.items():
            with capture_selects(mdb) as statements:
                call()
            counts[name] = _choice_selects(statements)
    assert counts == {"film": 1, "episode": 1, "home": 1, "show": 0, "season": 0}


def test_home_without_episode_cards_reads_no_choice(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        add_progress(s, a, film_id=film.id, position=600)
        add_track_choice(s, a, film=film)
    with mdb.read_session() as s, capture_selects(mdb) as statements:
        home = catalog.home(s, a, 20)
    assert [c.type for c in home.continue_watching] == ["film"]
    assert _choice_selects(statements) == 0


def test_choice_reads_never_touch_the_write_engine(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        add_track_choice(s, a, film=film)
        film_id = film.id
    with capture_writes(mdb) as statements, mdb.read_session() as s:
        assert catalog.film_detail(s, a, film_id, 10).track_choice is not None
        catalog.home(s, a, 20)
    assert statements == []


def test_catalog_never_imports_the_track_choices_module():
    import subprocess
    import sys

    code = "import sys, hometv.catalog; print('hometv.track_choices' in sys.modules)"
    out = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, check=True)
    assert out.stdout.strip() == "False"


def test_service_does_not_import_the_dependency_layer():
    import subprocess
    import sys

    code = "import sys, hometv.track_choices; print('hometv.api.deps' in sys.modules)"
    out = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, check=True)
    assert out.stdout.strip() == "False"
