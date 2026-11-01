"""Ratings, pure (ratings PRD FR-6, FR-11): household average rounding, ``RatingRequest``
validation and the stars <-> ``stars_x2`` conversion."""

import json

import pytest
from pydantic import ValidationError

from hometv.api.schemas import RatingRequest, stars_to_x2
from hometv.catalog import household_average, stars_from_x2


@pytest.mark.parametrize(
    ("sum_x2", "count", "expected"),
    [
        (16, 2, 4.0),  # 4 + 4
        (24, 3, 4.0),  # "4,0 (3)"
        (17, 2, 4.3),  # 4.25 -> 4.3 (half up, not banker's 4.2)
        (19, 2, 4.8),  # 4.75 -> 4.8
        (1, 1, 0.5),
        (20, 2, 5.0),
        (21, 3, 3.5),  # 3.5 exactly
        (25, 3, 4.2),  # 4.1666... -> 4.2
        (23, 3, 3.8),  # 3.8333... -> 3.8
    ],
)
def test_household_average_one_decimal_half_up(sum_x2, count, expected):
    assert household_average(sum_x2, count) == expected


VALID = [x / 2 for x in range(1, 11)]


@pytest.mark.parametrize("stars", [*VALID, 4, 1, 5])
def test_rating_request_accepts_half_steps_and_ints(stars):
    body = RatingRequest.model_validate_json(json.dumps({"stars": stars}))
    assert body.stars == stars
    assert stars_from_x2(stars_to_x2(body.stars)) == stars


@pytest.mark.parametrize(
    "payload",
    [
        {"stars": 0},
        {"stars": 0.0},
        {"stars": 5.5},
        {"stars": 4.3},
        {"stars": -0.5},
        {"stars": "4.5"},
        {"stars": True},
        {"stars": None},
        {},
    ],
)
def test_rating_request_rejects(payload):
    with pytest.raises(ValidationError):
        RatingRequest.model_validate_json(json.dumps(payload))


@pytest.mark.parametrize(("stars", "x2"), [(0.5, 1), (4.0, 8), (4.5, 9), (5.0, 10)])
def test_stars_conversion_rounds(stars, x2):
    assert stars_to_x2(stars) == x2
    assert stars_to_x2(stars + 1e-10) == x2  # a float a hair off a half step still rounds
    assert stars_from_x2(x2) == stars
