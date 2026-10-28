"""Pure person normalisation (actor screen FR-5): ``normalize_credits`` rules 1-5,
``normalize_person``, ``is_fresh``."""

from datetime import UTC, date, datetime, timedelta
from typing import Any

import pytest

from hometv.metadata.tmdb import BIOGRAPHY_LANGUAGE_KEY
from hometv.people import (
    PERSON_TTL,
    PersonCreditRow,
    PersonFetch,
    is_fresh,
    normalize_credits,
    normalize_person,
)

M, T = "movie", "tv"


def _c(media_type: Any = M, id_: Any = 1, character: Any = None, **extra: Any) -> dict[str, Any]:
    return {"media_type": media_type, "id": id_, "character": character, **extra}


@pytest.mark.parametrize(
    ("cast", "expected"),
    [
        pytest.param(None, (), id="missing"),
        pytest.param("x", (), id="not-a-list"),
        pytest.param({"id": 1}, (), id="dict-not-list"),
        pytest.param([], (), id="empty"),
        # rule 1: bad entries dropped
        pytest.param(["x", 3, None], (), id="r1-not-dicts"),
        pytest.param([_c(id_=None), _c(id_="12"), _c(id_=1.5)], (), id="r1-id-not-int"),
        pytest.param([_c(id_=True)], (), id="r1-id-bool"),
        pytest.param([{"id": 1}], (), id="r1-no-media-type"),
        pytest.param([_c(media_type="person")], (), id="r1-other-media-type"),
        # rule 2: adult
        pytest.param([_c(adult=True)], (), id="r2-adult"),
        pytest.param(
            [_c(adult=False, character="A")], (PersonCreditRow(M, 1, "A"),), id="r2-not-adult"
        ),
        # rule 3: character
        pytest.param([_c(character="  Paul  ")], (PersonCreditRow(M, 1, "Paul"),), id="r3-strip"),
        pytest.param([_c(character="   ")], (PersonCreditRow(M, 1, None),), id="r3-blank"),
        pytest.param([_c(character=7)], (PersonCreditRow(M, 1, None),), id="r3-not-str"),
        pytest.param(
            [_c(media_type=T, character="Finn", episode_count=3, crew_job="x")],
            (PersonCreditRow(T, 1, "Finn"),),
            id="r3-other-fields-ignored",
        ),
        # rule 4: merge
        pytest.param(
            [_c(character="A"), _c(character="B"), _c(character="A"), _c(character=None)],
            (PersonCreditRow(M, 1, "A / B"),),
            id="r4-merge-dedup",
        ),
        pytest.param(
            [_c(character=None), _c(character="")], (PersonCreditRow(M, 1, None),), id="r4-none"
        ),
        pytest.param(
            [_c(M, 5, "Film"), _c(T, 5, "Show")],
            (PersonCreditRow(M, 5, "Film"), PersonCreditRow(T, 5, "Show")),
            id="r4-same-id-other-type",
        ),
        # rule 5: sort
        pytest.param(
            [_c(T, 2), _c(M, 9), _c(T, 1), _c(M, 3)],
            (
                PersonCreditRow(M, 3, None),
                PersonCreditRow(M, 9, None),
                PersonCreditRow(T, 1, None),
                PersonCreditRow(T, 2, None),
            ),
            id="r5-sorted",
        ),
    ],
)
def test_normalize_credits(cast: object, expected: tuple[PersonCreditRow, ...]) -> None:
    assert normalize_credits(cast) == expected


def _person(**fields: Any) -> dict[str, Any]:
    base: dict[str, Any] = {
        "biography": "Bio",
        BIOGRAPHY_LANGUAGE_KEY: "es-ES",
        "birthday": "1995-12-27",
        "deathday": None,
        "place_of_birth": "Manhattan",
        "combined_credits": {"cast": [_c(M, 438631, "Paul")], "crew": [_c(M, 99, job="x")]},
    }
    base.update(fields)
    return base


def test_normalize_person_full() -> None:
    assert normalize_person(_person()) == PersonFetch(
        biography="Bio",
        biography_language="es-ES",
        birthday=date(1995, 12, 27),
        deathday=None,
        place_of_birth="Manhattan",
        credits=(PersonCreditRow(M, 438631, "Paul"),),
    )


@pytest.mark.parametrize(
    ("bio", "lang", "expected"),
    [
        ("  Bio  ", "en-US", ("Bio", "en-US")),
        ("   ", "es-ES", (None, None)),
        (None, "es-ES", (None, None)),
        (42, "es-ES", (None, None)),
        ("Bio", None, ("Bio", None)),
    ],
)
def test_normalize_person_biography(bio: Any, lang: Any, expected: tuple[Any, Any]) -> None:
    fetch = normalize_person(_person(biography=bio, **{BIOGRAPHY_LANGUAGE_KEY: lang}))
    assert (fetch.biography, fetch.biography_language) == expected


def test_normalize_person_missing_language_key() -> None:
    data = _person()
    del data[BIOGRAPHY_LANGUAGE_KEY]
    assert normalize_person(data).biography_language is None


@pytest.mark.parametrize(
    ("place", "expected"),
    [(" Madrid, Spain ", "Madrid, Spain"), ("", None), ("  ", None), (None, None), (3, None)],
)
def test_normalize_person_place_of_birth(place: Any, expected: str | None) -> None:
    assert normalize_person(_person(place_of_birth=place)).place_of_birth == expected


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("1932-10-08", date(1932, 10, 8)),
        (None, None),
        ("", None),
        ("1995", None),
        ("19921008", None),  # accepted by date.fromisoformat on 3.12: rejected by the strict check
        ("1995-13-40", None),
        ("1995-1-2", None),
        (" 1995-12-27", None),
        (19951227, None),
    ],
)
def test_normalize_person_dates(value: Any, expected: date | None) -> None:
    fetch = normalize_person(_person(birthday=value, deathday=value))
    assert fetch.birthday == expected
    assert fetch.deathday == expected


def test_deathday_before_birthday_kept_as_given() -> None:
    fetch = normalize_person(_person(birthday="2010-05-03", deathday="1932-10-08"))
    assert (fetch.birthday, fetch.deathday) == (date(2010, 5, 3), date(1932, 10, 8))


@pytest.mark.parametrize(
    "combined",
    [pytest.param(None, id="null"), pytest.param("x", id="str"), pytest.param([], id="list")],
)
def test_normalize_person_combined_credits_wrong_type(combined: Any) -> None:
    assert normalize_person(_person(combined_credits=combined)).credits == ()


def test_normalize_person_combined_credits_missing() -> None:
    data = _person()
    del data["combined_credits"]
    assert normalize_person(data).credits == ()


def test_normalize_person_empty_payload() -> None:
    assert normalize_person({}) == PersonFetch(None, None, None, None, None, ())


NOW = datetime(2026, 10, 28, 12, 0, tzinfo=UTC)


@pytest.mark.parametrize(
    ("fetched_at", "fresh"),
    [
        (None, False),
        (NOW, True),
        (NOW - timedelta(days=29, hours=23), True),
        (NOW - timedelta(days=30), False),
        (NOW - timedelta(days=31), False),
    ],
)
def test_is_fresh(fetched_at: datetime | None, fresh: bool) -> None:
    assert is_fresh(fetched_at, NOW) is fresh


def test_ttl_is_30_days() -> None:
    assert timedelta(days=30) == PERSON_TTL


def test_is_fresh_custom_ttl() -> None:
    assert is_fresh(NOW - timedelta(hours=2), NOW, timedelta(hours=1)) is False
