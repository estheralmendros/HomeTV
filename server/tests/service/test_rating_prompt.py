"""Rating prompt (ratings PRD FR-7, FR-8, §4): ``rating_prompt`` on ``report_progress`` /
``mark_watched`` exactly when the request watches the title's finishing item and the profile has
neither rated nor dismissed it; the extra-query budget; the deleted-profile 401 on both writes."""

import re

import pytest
from sqlalchemy import func, select, text, update
from sqlalchemy.exc import IntegrityError

from hometv.api.errors import ApiError, fk_to_unauthorized
from hometv.api.schemas import ProgressRequest
from hometv.db import models as m
from hometv.db.engine import Database
from hometv.watch import (
    EpisodeKey,
    _mark_siblings_watched,
    _show_last_episode_id,
    last_episode,
    mark_watched,
    report_progress,
)
from tests.factories import (
    add_episode,
    add_file,
    add_film,
    add_profile,
    add_progress,
    add_rating,
    add_rating_dismissal,
    add_show,
)
from tests.stubs import capture_writes

FILM_DURATION = 5400.0  # add_file default
EPISODE_DURATION = 1320.0  # add_episode default


@pytest.fixture
def profiles(mdb: Database) -> tuple[int, int]:
    with mdb.write_session() as s:
        return add_profile(s, "Ana").id, add_profile(s, "Luis").id


def _film_file(db: Database, film_id: int, part: int | None = None) -> int:
    with db.read_session() as s:
        q = select(m.MediaFile.id).where(m.MediaFile.film_id == film_id)
        if part is not None:
            q = q.where(m.MediaFile.part_number == part)
        file_id = s.scalar(q.order_by(m.MediaFile.id))
    assert file_id is not None
    return file_id


def _episode_file(db: Database, episode_id: int) -> int:
    with db.read_session() as s:
        file_id = s.scalar(
            select(m.MediaFileEpisode.media_file_id).where(
                m.MediaFileEpisode.episode_id == episode_id
            )
        )
    assert file_id is not None
    return file_id


def play_film(db: Database, profile_id: int, film_id: int, ratio: float, part: int | None = None):
    return report_progress(
        db,
        profile_id,
        ProgressRequest(
            item_type="film",
            item_id=film_id,
            media_file_id=_film_file(db, film_id, part),
            position_seconds=ratio * FILM_DURATION,
        ),
    )


def play_episode(db: Database, profile_id: int, episode_id: int, ratio: float = 0.96):
    return report_progress(
        db,
        profile_id,
        ProgressRequest(
            item_type="episode",
            item_id=episode_id,
            media_file_id=_episode_file(db, episode_id),
            position_seconds=ratio * EPISODE_DURATION,
        ),
    )


def prompt_of(result) -> tuple[str, int, str] | None:
    prompt = result.rating_prompt
    return None if prompt is None else (prompt.kind, prompt.id, prompt.title)


# --- Films -----------------------------------------------------------------------------------


def test_film_reaching_the_rule_prompts_and_keeps_every_progress_key(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune", poster="/dune.jpg").id
    result = play_film(mdb, a, film, 0.96)
    assert result.watched and result.position_seconds == 0.0
    assert prompt_of(result) == ("film", film, "Dune")
    assert result.rating_prompt is not None and result.rating_prompt.poster is not None
    assert result.rating_prompt.poster.small.endswith("/dune.jpg")
    again = play_film(mdb, a, film, 0.97)  # credits: the same prompt again (state-based)
    assert prompt_of(again) == ("film", film, "Dune")


def test_film_below_the_rule_does_not_prompt(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune").id
    assert play_film(mdb, a, film, 0.5).rating_prompt is None


def test_multi_part_film_prompts_on_the_last_part_only(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Epic", files=0)
        add_file(s, film_id=film.id, part_number=1)
        add_file(s, film_id=film.id, part_number=2)
        film_id = film.id
    assert play_film(mdb, a, film_id, 0.99, part=1).rating_prompt is None
    assert prompt_of(play_film(mdb, a, film_id, 0.96, part=2)) == ("film", film_id, "Epic")


@pytest.mark.parametrize("state", ["rating", "dismissal"])
def test_a_rating_or_dismissal_silences_the_prompt_for_that_profile_only(mdb, profiles, state):
    a, b = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        if state == "rating":
            add_rating(s, a, film=film)
        else:
            add_rating_dismissal(s, a, film=film)
        film_id = film.id
    assert play_film(mdb, a, film_id, 0.96).rating_prompt is None
    assert mark_watched(mdb, a, "film", film_id, True).rating_prompt is None
    assert prompt_of(play_film(mdb, b, film_id, 0.96)) == ("film", film_id, "Dune")


def test_film_mark_prompts_unmark_does_not_and_hidden_film_does_not(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune").id
        hidden = add_film(s, "Gone", files=0)
        add_file(s, film_id=hidden.id, missing=True)
        hidden_id = hidden.id
    marked = mark_watched(mdb, a, "film", film, True)
    assert (marked.updated, prompt_of(marked)) == (1, ("film", film, "Dune"))
    unmarked = mark_watched(mdb, a, "film", film, False)
    assert (unmarked.updated, unmarked.rating_prompt) == (1, None)
    assert mark_watched(mdb, a, "film", hidden_id, True).rating_prompt is None


def test_unmark_then_mark_a_rated_film_does_not_prompt(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        add_rating(s, a, film=film)
        film_id = film.id
    mark_watched(mdb, a, "film", film_id, False)
    assert mark_watched(mdb, a, "film", film_id, True).rating_prompt is None


# --- Shows -----------------------------------------------------------------------------------


def test_episode_reports_prompt_only_for_the_last_present_episode(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        show, eps = add_show(s, "Lost", episodes={1: 2, 2: 2})
        ids = {k: e.id for k, e in eps.items()}
        show_id = show.id
    assert play_episode(mdb, a, ids[(1, 2)]).rating_prompt is None  # not the last
    assert play_episode(mdb, a, ids[(2, 2)], ratio=0.5).rating_prompt is None  # below the rule
    assert prompt_of(play_episode(mdb, a, ids[(2, 2)])) == ("show", show_id, "Lost")


def test_last_episode_ignores_missing_files_and_seasons_not_on_disk(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        show, eps = add_show(s, "Lost", episodes={1: 3})
        add_episode(s, show, 2, 1, with_file=False)  # announced, not on disk
        s.execute(
            update(m.MediaFile)
            .where(
                m.MediaFile.id.in_(
                    select(m.MediaFileEpisode.media_file_id).where(
                        m.MediaFileEpisode.episode_id == eps[(1, 3)].id
                    )
                )
            )
            .values(missing_since=func.current_timestamp())
        )
        e2, show_id = eps[(1, 2)].id, show.id
    assert prompt_of(play_episode(mdb, a, e2)) == ("show", show_id, "Lost")


def test_specials_only_and_specials_excluded(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        only, sp = add_show(s, "Extras", episodes={0: 3})
        mixed, mx = add_show(s, "Mixed", episodes={0: 5, 1: 3})
        ids = (only.id, sp[(0, 3)].id, mixed.id, mx[(0, 5)].id, mx[(1, 3)].id)
    only_id, sp3, mixed_id, s0e5, s1e3 = ids
    assert prompt_of(play_episode(mdb, a, sp3)) == ("show", only_id, "Extras")
    assert play_episode(mdb, a, s0e5).rating_prompt is None
    assert prompt_of(play_episode(mdb, a, s1e3)) == ("show", mixed_id, "Mixed")


def test_multi_episode_file_whose_sibling_is_the_last_episode(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        show, eps = add_show(s, "Lost", episodes={1: 1}, with_files=False)
        add_file(s, episode_ids=[eps[(1, 1)].id])
        e2 = add_episode(s, show, 1, 2, with_file=False)
        e3 = add_episode(s, show, 1, 3, with_file=False)
        add_file(s, episode_ids=[e2.id, e3.id], duration=EPISODE_DURATION)  # S1E2-E3 in one file
        e2_id, show_id = e2.id, show.id
    assert prompt_of(play_episode(mdb, a, e2_id)) == ("show", show_id, "Lost")


def test_season_and_show_marks_give_at_most_one_show_prompt(mdb, profiles):
    a, b = profiles
    with mdb.write_session() as s:
        show, eps = add_show(s, "Lost", episodes={1: 2, 2: 2})
        s1, s2 = eps[(1, 1)].season_id, eps[(2, 1)].season_id
        show_id, last = show.id, eps[(2, 2)].id
        empty_show = add_show(s, "Ghost", with_files=False)[0].id
    marked = mark_watched(mdb, a, "season", s2, True)
    assert (marked.updated, prompt_of(marked)) == (2, ("show", show_id, "Lost"))
    assert mark_watched(mdb, a, "season", s1, True).rating_prompt is None  # earlier season
    assert mark_watched(mdb, a, "season", s2, False).rating_prompt is None
    assert prompt_of(mark_watched(mdb, b, "show", show_id, True)) == ("show", show_id, "Lost")
    assert prompt_of(mark_watched(mdb, b, "episode", last, True)) == ("show", show_id, "Lost")
    assert mark_watched(mdb, b, "episode", eps[(1, 1)].id, True).rating_prompt is None
    assert mark_watched(mdb, a, "show", empty_show, True).rating_prompt is None


def test_show_rating_or_dismissal_silences_the_show_prompt(mdb, profiles):
    a, b = profiles
    with mdb.write_session() as s:
        show, eps = add_show(s, "Lost", episodes={1: 2})
        add_rating(s, a, show=show)
        add_rating_dismissal(s, b, show=show)
        show_id, last = show.id, eps[(1, 2)].id
    for pid in (a, b):
        assert play_episode(mdb, pid, last).rating_prompt is None
        assert mark_watched(mdb, pid, "show", show_id, True).rating_prompt is None


# --- Helpers ---------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("present", "missing"),
    [
        ([(1, e) for e in range(1, 11)] + [(2, e) for e in range(1, 11)], [(2, 10)]),  # case 8
        ([(0, 1), (0, 2), (0, 3)], []),  # case 9
        ([(0, 5), (1, 1), (1, 2), (1, 3)], []),  # cases 10-11
        ([(0, 7), (1, 1)], [(1, 1)]),  # regular season gone: the special is last
    ],
)
def test_show_last_episode_id_matches_the_pure_rule(mdb, present, missing):
    with mdb.write_session() as s:
        show, _ = add_show(s, "X", episodes={1: 0})
        keys = []
        for season, number in present:
            ep = add_episode(s, show, season, number, with_file=False)
            add_file(s, episode_ids=[ep.id], missing=(season, number) in missing)
            if (season, number) not in missing:
                keys.append(EpisodeKey(ep.id, season, number))
        assert _show_last_episode_id(s, show.id) == last_episode(keys)
        assert last_episode(keys) is not None


def test_mark_siblings_watched_returns_the_sibling_ids(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        show, _ = add_show(s, "X", episodes={1: 0})
        e1, e2, e3 = (add_episode(s, show, 1, n, with_file=False) for n in (1, 2, 3))
        both = add_file(s, episode_ids=[e1.id, e2.id, e3.id])
        single = add_file(s, episode_ids=[e1.id])
        siblings = _mark_siblings_watched(s, a, both.id, e1.id, m.utcnow())
        assert sorted(siblings) == sorted([e2.id, e3.id])
        assert _mark_siblings_watched(s, a, single.id, e1.id, m.utcnow()) == []


# --- Query budget (FR-8 table) ---------------------------------------------------------------


def _flat(statements: list[str]) -> list[str]:
    return [re.sub(r"\s+", " ", st) for st in statements]


def _budget(statements: list[str]) -> tuple[int, int, int]:
    """(rating checks, last-episode lookups, show loads) among the write session's statements."""
    flat = _flat(statements)
    checks = sum(1 for st in flat if re.search(r"\brating(_dismissal)?\b", st))
    lookups = sum(
        1 for st in flat if "FROM episode" in st and "ORDER BY episode.season_number DESC" in st
    )
    shows = sum(1 for st in flat if "FROM show WHERE show.id" in st)
    return checks, lookups, shows


def _measure(db: Database, call) -> tuple[int, int, int]:
    with capture_writes(db) as statements:
        call()
    return _budget(statements)


def test_query_budget_of_progress_reports_and_marks(mdb, profiles):
    a, b = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune").id
        epic = add_film(s, "Epic", files=0)
        add_file(s, film_id=epic.id, part_number=1)
        add_file(s, film_id=epic.id, part_number=2)
        show, eps = add_show(s, "Lost", episodes={1: 2, 2: 2})
        show_id = show.id
        first, last = eps[(1, 1)].id, eps[(2, 2)].id
        s1, s2 = eps[(1, 1)].season_id, eps[(2, 1)].season_id
        empty_show = add_show(s, "Ghost", with_files=False)[0].id
    epic_id = epic.id
    assert _measure(mdb, lambda: play_film(mdb, a, film, 0.5)) == (0, 0, 0)
    assert _measure(mdb, lambda: play_film(mdb, a, epic_id, 0.99, part=1)) == (0, 0, 0)
    assert _measure(mdb, lambda: play_film(mdb, a, film, 0.96)) == (1, 0, 0)
    assert _measure(mdb, lambda: mark_watched(mdb, a, "film", film, False)) == (0, 0, 0)
    assert _measure(mdb, lambda: mark_watched(mdb, a, "film", film, True)) == (1, 0, 0)
    assert _measure(mdb, lambda: play_episode(mdb, a, first, 0.5)) == (0, 0, 0)
    assert _measure(mdb, lambda: play_episode(mdb, a, first)) == (0, 1, 0)
    assert _measure(mdb, lambda: play_episode(mdb, a, last)) == (1, 1, 1)
    assert _measure(mdb, lambda: mark_watched(mdb, a, "episode", last, False)) == (0, 0, 0)
    assert _measure(mdb, lambda: mark_watched(mdb, a, "season", s1, True)) == (0, 1, 0)
    assert _measure(mdb, lambda: mark_watched(mdb, a, "season", s2, True)) == (1, 1, 1)
    # A show target already loads the show (pre-existing 404 check): the prompt reuses it.
    assert _measure(mdb, lambda: mark_watched(mdb, a, "show", show_id, True)) == (1, 1, 1)
    assert _measure(mdb, lambda: mark_watched(mdb, a, "show", show_id, False)) == (0, 0, 1)
    assert _measure(mdb, lambda: mark_watched(mdb, a, "show", empty_show, True)) == (0, 0, 1)
    with mdb.write_session() as s:
        add_rating_dismissal(s, b, show=s.get(m.Show, show_id))
    assert _measure(mdb, lambda: play_episode(mdb, b, last)) == (1, 1, 0)


def test_film_prompt_reuses_the_loaded_film_row(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune").id
    for call in (
        lambda: play_film(mdb, a, film, 0.96),
        lambda: mark_watched(mdb, a, "film", film, True),
    ):
        with capture_writes(mdb) as statements:
            assert call().rating_prompt is not None
        assert sum(1 for st in _flat(statements) if "FROM film WHERE film.id" in st) == 1


def test_episode_report_loads_the_episode_row_once(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        _, eps = add_show(s, "Lost", episodes={1: 2})
        first, last = eps[(1, 1)].id, eps[(1, 2)].id
    for episode_id, prompted in ((first, False), (last, True)):
        with capture_writes(mdb) as statements:
            assert (play_episode(mdb, a, episode_id).rating_prompt is not None) is prompted
        loads = [st for st in _flat(statements) if "FROM episode WHERE episode.id" in st]
        assert len(loads) == 1, loads


# --- Deleted profile (§4) --------------------------------------------------------------------


def _delete_profile(db: Database, profile_id: int) -> None:
    with db.write_session() as s:
        s.execute(m.Profile.__table__.delete().where(m.Profile.id == profile_id))


def _progress_rows(db: Database) -> int:
    with db.read_session() as s:
        return int(s.scalar(select(func.count()).select_from(m.PlaybackProgress)) or 0)


def test_report_progress_for_a_deleted_profile_is_unauthorized(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune").id
    _delete_profile(mdb, a)
    with pytest.raises(ApiError) as exc:
        play_film(mdb, a, film, 0.96)
    assert (exc.value.status_code, exc.value.code) == (401, "unauthorized")
    assert _progress_rows(mdb) == 0


def test_mark_watched_for_a_deleted_profile_is_unauthorized(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune").id
        show = add_show(s, "Lost")[0].id
    _delete_profile(mdb, a)
    for target, item_id in (("film", film), ("show", show)):
        with pytest.raises(ApiError) as exc:
            mark_watched(mdb, a, target, item_id, True)
        assert (exc.value.status_code, exc.value.code) == (401, "unauthorized")
    assert _progress_rows(mdb) == 0
    assert mark_watched(mdb, a, "film", film, False).updated == 0  # nothing inserted: no FK


def test_fk_to_unauthorized_reraises_other_integrity_errors(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune").id
        s.execute(
            text(
                "CREATE TRIGGER reject_progress BEFORE INSERT ON playback_progress "
                "BEGIN SELECT RAISE(ABORT, 'rejected by trigger'); END"
            )
        )
    with pytest.raises(IntegrityError, match="rejected by trigger"):
        play_film(mdb, a, film, 0.96)
    with pytest.raises(IntegrityError, match="UNIQUE"), fk_to_unauthorized():
        raise IntegrityError("INSERT", None, Exception("UNIQUE constraint failed: x"))
    with pytest.raises(ApiError), fk_to_unauthorized():
        raise IntegrityError("INSERT", None, Exception("FOREIGN KEY constraint failed"))


def test_mark_siblings_and_prompt_leave_ratings_untouched(mdb, profiles):
    a, _ = profiles
    with mdb.write_session() as s:
        film = add_film(s, "Dune")
        add_rating(s, a, film=film, stars_x2=3)
        film_id = film.id
        add_progress(s, a, film_id=film_id, watched=True)
    play_film(mdb, a, film_id, 0.96)
    mark_watched(mdb, a, "film", film_id, False)
    with mdb.read_session() as s:
        assert [(r.stars_x2) for r in s.scalars(select(m.Rating))] == [3]
