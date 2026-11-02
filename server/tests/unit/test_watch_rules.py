"""Watch-state rules, pure: watched ratio (films 95 %, episodes 90 %), last-part rule,
in-progress, Next Up."""

import pytest

from hometv.watch import (
    EpisodeKey,
    EpisodeState,
    apply_progress_rule,
    first_unwatched,
    is_in_progress,
    is_last_part,
    is_title_finished,
    last_episode,
    next_up,
    watched_ratio,
)


@pytest.mark.parametrize(
    ("item_type", "position", "duration", "rule_applies", "expected"),
    [
        ("film", 8999.0, 10000.0, True, (False, 8999.0)),
        ("film", 9000.0, 10000.0, True, (False, 9000.0)),  # 90 % is not enough for a film
        ("film", 9499.0, 10000.0, True, (False, 9499.0)),
        ("film", 9500.0, 10000.0, True, (True, 0.0)),  # exactly 95 %
        ("film", 9999.0, 10000.0, True, (True, 0.0)),
        ("film", 12000.0, 10000.0, True, (True, 0.0)),  # past the end
        ("film", 9500.0, 10000.0, False, (False, 9500.0)),  # non-final part: never watched
        ("film", 0.0, 10000.0, True, (False, 0.0)),
        ("episode", 8999.0, 10000.0, True, (False, 8999.0)),
        ("episode", 9000.0, 10000.0, True, (True, 0.0)),  # exactly 90 %
        ("episode", 9500.0, 10000.0, True, (True, 0.0)),
        ("episode", 12000.0, 10000.0, True, (True, 0.0)),
        ("episode", 0.0, 10000.0, True, (False, 0.0)),
    ],
)
def test_watched_ratio_rule(item_type, position, duration, rule_applies, expected):
    outcome = apply_progress_rule(
        position, duration, ratio=watched_ratio(item_type), rule_applies=rule_applies
    )
    assert (outcome.reaches_watched, outcome.position_seconds) == expected


def test_watched_ratio_per_item_type():
    assert (watched_ratio("film"), watched_ratio("episode")) == (0.95, 0.9)


@pytest.mark.parametrize(
    ("part", "present", "expected"),
    [
        (None, [1, 2], True),  # standalone version: normal rule
        (None, [], True),
        (1, [1, 2], False),
        (2, [1, 2], True),
        (1, [1], True),  # only Part 1 present -> it is the last part
        (2, [1, 2, 3], False),
    ],
)
def test_last_part(part, present, expected):
    assert is_last_part(part, present) is expected


@pytest.mark.parametrize(
    ("watched", "position", "expected"),
    [(False, 29.9, False), (False, 30.0, True), (True, 500.0, False), (False, 0.0, False)],
)
def test_in_progress_threshold(watched, position, expected):
    assert is_in_progress(watched, position) is expected


def _eps(*keys: tuple[int, int]) -> list[EpisodeKey]:
    return [EpisodeKey(i + 1, s, e) for i, (s, e) in enumerate(keys)]


EPISODES = _eps((0, 1), (1, 1), (1, 2), (1, 3), (2, 1), (2, 2))
# ids:          1       2       3       4       5       6


def _state(**kw: tuple[bool, float]) -> dict[int, EpisodeState]:
    return {int(k[1:]): EpisodeState(watched=w, position_seconds=pos) for k, (w, pos) in kw.items()}


def test_next_up_after_highest_watched():
    state = _state(e2=(True, 0), e3=(True, 0))
    assert next_up(EPISODES, state) == 4


def test_next_up_uses_highest_watched_even_with_gaps():
    # S1E1 unwatched, S2E1 watched -> next is S2E2 (after the highest), not the gap.
    state = _state(e5=(True, 0))
    assert next_up(EPISODES, state) == 6


def test_next_up_skips_watched_after_highest():
    state = _state(e2=(True, 0), e4=(True, 0), e5=(True, 0))
    assert next_up(EPISODES, state) == 6
    state = _state(e2=(True, 0), e3=(False, 0), e4=(True, 0))
    assert next_up(EPISODES, state) == 5


def test_next_up_none_when_next_in_progress_or_last_or_only_specials():
    assert next_up(EPISODES, _state(e2=(True, 0), e3=(False, 120.0))) is None
    assert next_up(EPISODES, _state(e2=(True, 0), e3=(False, 10.0))) == 3  # < 30 s
    assert next_up(EPISODES, _state(e6=(True, 0))) is None
    assert next_up(EPISODES, _state(e1=(True, 0))) is None  # specials don't count
    assert next_up(EPISODES, {}) is None


def test_next_up_never_returns_a_special():
    eps = _eps((1, 1), (0, 5))
    assert next_up(eps, _state(e1=(True, 0))) is None


def test_first_unwatched():
    assert first_unwatched(EPISODES, {}) == 2  # specials excluded
    assert first_unwatched(EPISODES, _state(e2=(True, 0), e4=(True, 0))) == 3
    assert first_unwatched(EPISODES, {i: EpisodeState(True, 0) for i in range(2, 7)}) is None


# --- "Finished" rule -----------------------------------------------------------------------------


def _film_reaches(position: float, part: int | None = None, parts: tuple[int, ...] = ()) -> bool:
    outcome = apply_progress_rule(
        position, 10000.0, ratio=watched_ratio("film"), rule_applies=is_last_part(part, parts)
    )
    return outcome.reaches_watched


@pytest.mark.parametrize(
    ("case", "film_watched", "expected"),
    [
        (1, _film_reaches(9600.0), True),  # single-file film watched by playback (>= 95 %)
        (2, _film_reaches(9600.0, part=2, parts=(1, 2)), True),  # last part reaches the rule
        (3, _film_reaches(9900.0, part=1, parts=(1, 2)), False),  # non-final part at 99 %
        (4, True, True),  # "Marcar como visto"
    ],
)
def test_film_finished(case, film_watched, expected):
    assert is_title_finished("film", film_watched=film_watched) is expected


def _eps(*codes: tuple[int, int]) -> list[EpisodeKey]:
    """Episodes with ids ``season * 100 + episode`` (S2E10 -> 210)."""
    return [EpisodeKey(s * 100 + e, s, e) for s, e in codes]


_S1_S2 = [(s, e) for s in (1, 2) for e in range(1, 11)]
_S1_S2_LAST_MISSING = [c for c in _S1_S2 if c != (2, 10)]  # S2E10's file missing


@pytest.mark.parametrize(
    ("case", "present", "watched", "expected_last", "finished"),
    [
        (5, _S1_S2, {210}, 210, True),
        (6, _S1_S2, set(range(101, 111)), 210, False),  # all of S1, not S2E10
        (7, _S1_S2, {210}, 210, True),  # S3 announced on TMDB, not on disk: not an input
        (8, _S1_S2_LAST_MISSING, {209}, 209, True),
        (9, [(0, 1), (0, 2), (0, 3)], {3}, 3, True),  # specials-only show
        (10, [(0, 5), (1, 1), (1, 2), (1, 3)], {103}, 103, True),  # specials excluded
        (11, [(0, 5), (1, 1), (1, 2), (1, 3)], {5}, 103, False),  # only the special watched
        (12, _S1_S2, {209, 210}, 210, True),  # S2E9-E10 in one file: siblings marked
        (13, [], set(), None, False),  # no present episode
    ],
)
def test_show_finished(case, present, watched, expected_last, finished):
    last = last_episode(_eps(*present))
    assert last == expected_last
    assert is_title_finished("show", last_episode_id=last, watched_episode_ids=watched) is finished


def test_last_episode_prefers_regular_seasons_over_higher_numbered_specials():
    assert last_episode(_eps((0, 99), (1, 1))) == 101
    assert last_episode(_eps((0, 2), (0, 7), (0, 3))) == 7
    assert last_episode(_eps((3, 1), (2, 12), (3, 2))) == 302
