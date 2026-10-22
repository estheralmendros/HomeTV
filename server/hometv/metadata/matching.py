"""Pure TMDB match decision (PRD §5 Matching). No I/O.

Results are raw TMDB search result dicts (movie: ``title``/``original_title``/``release_date``;
tv: ``name``/``original_name``/``first_air_date``). ``merge_results`` combines the searches made
in both languages by TMDB id (first search order kept) and records every known title of a
result under ``ALT_TITLES_KEY`` so that a result title-matches in either language.
"""

from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from typing import Any, Literal

from hometv.text import normalize_title

MAX_CANDIDATES = 5
YEAR_TOLERANCE = 1
ALT_TITLES_KEY = "_hometv_titles"

Kind = Literal["movie", "tv"]
MatchReason = Literal["matched", "no_tmdb_results", "low_confidence"]


@dataclass(frozen=True)
class MatchDecision:
    tmdb_id: int | None
    reason: MatchReason
    detail: str | None = None


def _own_titles(result: dict[str, Any]) -> list[str]:
    keys = ("title", "original_title", "name", "original_name")
    return [result[k] for k in keys if isinstance(result.get(k), str) and result[k]]


def result_titles(result: dict[str, Any]) -> set[str]:
    """Normalized known titles of a result (all languages it was fetched in)."""
    titles = [*_own_titles(result), *result.get(ALT_TITLES_KEY, [])]
    return {normalize_title(t) for t in titles}


def result_year(result: dict[str, Any], kind: Kind) -> int | None:
    value = result.get("release_date" if kind == "movie" else "first_air_date")
    if isinstance(value, str) and len(value) >= 4 and value[:4].isdigit():
        return int(value[:4])
    return None


def merge_results(*searches: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    merged: dict[int, dict[str, Any]] = {}
    for results in searches:
        for result in results:
            tmdb_id = result.get("id")
            if not isinstance(tmdb_id, int):
                continue
            if tmdb_id not in merged:
                merged[tmdb_id] = {**result, ALT_TITLES_KEY: list(_own_titles(result))}
            else:
                titles = merged[tmdb_id][ALT_TITLES_KEY]
                titles.extend(t for t in _own_titles(result) if t not in titles)
    return list(merged.values())


def choose_match(
    parsed_title: str, parsed_year: int | None, results: Sequence[dict[str, Any]], kind: Kind
) -> MatchDecision:
    if not results:
        return MatchDecision(None, "no_tmdb_results")
    wanted = normalize_title(parsed_title)
    candidates = [r for r in results[:MAX_CANDIDATES] if wanted in result_titles(r)]
    if parsed_year is not None:
        for result in candidates:
            year = result_year(result, kind)
            if year is not None and abs(year - parsed_year) <= YEAR_TOLERANCE:
                return MatchDecision(result["id"], "matched")
        return MatchDecision(None, "low_confidence", _detail(candidates))
    if len(candidates) == 1:
        return MatchDecision(candidates[0]["id"], "matched")
    return MatchDecision(None, "low_confidence", _detail(candidates))


def _detail(candidates: Sequence[dict[str, Any]]) -> str:
    detail = f"{len(candidates)} exact-title candidates"
    if candidates:
        detail += ": " + ", ".join(str(c["id"]) for c in candidates)
    return detail
