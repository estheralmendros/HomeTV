"""choose_match / merge_results (PRD §5 Matching): synthetic TMDB result lists."""

import pytest

from hometv.metadata.matching import MatchDecision, choose_match, merge_results


def movie(id_, title, year, original=None):
    return {
        "id": id_,
        "title": title,
        "original_title": original or title,
        "release_date": f"{year}-06-01" if year else "",
    }


def tv(id_, name, year, original=None):
    return {
        "id": id_,
        "name": name,
        "original_name": original or name,
        "first_air_date": f"{year}-03-24" if year else None,
    }


DUNES = [movie(438631, "Dune", 2021), movie(841, "Dune", 1984)]
OFFICES = [tv(2316, "The Office", 2005), tv(2996, "The Office", 2001)]


@pytest.mark.parametrize(
    ("title", "year", "results", "kind", "expected"),
    [
        ("Dune", 2021, [], "movie", MatchDecision(None, "no_tmdb_results")),
        ("Dune", 2021, DUNES, "movie", MatchDecision(438631, "matched")),
        ("Dune", 1984, DUNES, "movie", MatchDecision(841, "matched")),
        ("Dune", 2020, DUNES, "movie", MatchDecision(438631, "matched")),  # year - 1
        ("Dune", 1985, DUNES, "movie", MatchDecision(841, "matched")),  # year + 1
        ("dune!", 2022, DUNES, "movie", MatchDecision(438631, "matched")),  # normalized
        ("Alien", None, [movie(348, "Alien", 1979)], "movie", MatchDecision(348, "matched")),
        ("The Office", 2005, OFFICES, "tv", MatchDecision(2316, "matched")),
        (
            "Lost",
            None,
            [tv(4607, "Lost", 2004), tv(9, "Lost Girl", 2010)],
            "tv",
            MatchDecision(4607, "matched"),
        ),
    ],
)
def test_choose_match_matches(title, year, results, kind, expected):
    decision = choose_match(title, year, results, kind)
    assert (decision.tmdb_id, decision.reason) == (expected.tmdb_id, expected.reason)


def test_year_outside_tolerance_is_low_confidence():
    decision = choose_match("Dune", 2023, DUNES, "movie")
    assert decision.tmdb_id is None
    assert decision.reason == "low_confidence"


def test_no_title_match_is_low_confidence():
    decision = choose_match("Arrival", 2016, DUNES, "movie")
    assert (decision.tmdb_id, decision.reason) == (None, "low_confidence")
    assert decision.detail == "0 exact-title candidates"


def test_no_year_with_two_exact_matches_lists_candidates():
    decision = choose_match("The Office", None, OFFICES, "tv")
    assert decision == MatchDecision(None, "low_confidence", "2 exact-title candidates: 2316, 2996")


def test_only_first_five_results_are_considered():
    noise = [movie(i, f"Other {i}", 2000) for i in range(1, 6)]
    assert choose_match("Dune", 2021, [*noise, DUNES[0]], "movie").reason == "low_confidence"


def test_original_title_matches():
    results = [movie(129, "El viaje de Chihiro", 2001, original="Sen to Chihiro no Kamikakushi")]
    assert choose_match("Sen to Chihiro no Kamikakushi", 2001, results, "movie").tmdb_id == 129


def test_merge_results_keeps_first_order_and_unions_titles():
    es = [
        movie(129, "El viaje de Chihiro", 2001, original="千と千尋の神隠し"),
        movie(5, "Otra", 2001),
    ]
    en = [movie(7, "Extra", 2001), movie(129, "Spirited Away", 2001, original="千と千尋の神隠し")]
    merged = merge_results(es, en)
    assert [r["id"] for r in merged] == [129, 5, 7]
    assert choose_match("Spirited Away", 2001, merged, "movie").tmdb_id == 129
    assert choose_match("El viaje de Chihiro", 2001, merged, "movie").tmdb_id == 129
    assert choose_match("Spirited Away", 2001, es, "movie").reason == "low_confidence"


def test_result_without_date_has_no_year():
    results = [movie(1, "Dune", None)]
    assert choose_match("Dune", 2021, results, "movie").reason == "low_confidence"
    assert choose_match("Dune", None, results, "movie").tmdb_id == 1
