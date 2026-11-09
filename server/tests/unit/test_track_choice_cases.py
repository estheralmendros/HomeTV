"""Sanity of ``shared/track-choice-cases.json`` (read by the web and TV suites, which implement
the matching rule; the server never matches): shape only, plus every choice being a body the
server accepts."""

import json
from pathlib import Path
from typing import Any

import pytest

from hometv.api.schemas import TrackChoice

CASES_PATH = Path(__file__).resolve().parents[3] / "shared" / "track-choice-cases.json"
AUDIO_TRACK_KEYS = {"index", "language", "title", "channels", "default"}
SUBTITLE_TRACK_KEYS = {
    "id",
    "kind",
    "source",
    "language",
    "title",
    "forced",
    "hearing_impaired",
    "default",
}
RESULT_KEYS = {"audio_stream_index", "subtitle_id"}


def _cases() -> list[dict[str, Any]]:
    doc = json.loads(CASES_PATH.read_text(encoding="utf-8"))
    assert doc["version"] == 1
    cases: list[dict[str, Any]] = doc["cases"]
    return cases


def test_file_has_every_table_row_with_unique_names():
    cases = _cases()
    names = [c["name"] for c in cases]
    assert len(cases) >= 24 and len(set(names)) == len(names)
    assert {int(name[:2]) for name in names} == set(range(1, 25))  # prd.md §2 rows 1..24


@pytest.mark.parametrize("case", _cases(), ids=lambda c: c["name"][:3].strip())
def test_case_shape(case):
    allowed = {"name", "mode", "pick", "choice", "audio_tracks", "subtitle_tracks"}
    allowed |= {"expected", "expected_web", "expected_tv"}
    assert {"name", "mode", "choice", "audio_tracks", "subtitle_tracks", "expected"} <= set(case)
    assert set(case) <= allowed
    assert case["mode"] in ("direct", "hls")
    assert all(set(t) == AUDIO_TRACK_KEYS for t in case["audio_tracks"])
    assert all(set(t) == SUBTITLE_TRACK_KEYS for t in case["subtitle_tracks"])
    assert all(t["kind"] in ("text", "image") for t in case["subtitle_tracks"])
    assert set(case["expected"]) == RESULT_KEYS
    for key in ("expected_web", "expected_tv"):
        assert set(case.get(key, {})) <= RESULT_KEYS
    if "pick" in case:
        assert set(case["pick"]) == AUDIO_TRACK_KEYS
    choice = case["choice"]
    if choice is not None:  # written in full, and a body the server accepts
        assert set(choice) == {"audio", "subtitles"}
        if choice["audio"] is not None:
            assert set(choice["audio"]) == {"language", "title", "channels"}
        if choice["subtitles"] is not None:
            assert set(choice["subtitles"]) == {
                "mode",
                "language",
                "title",
                "forced",
                "hearing_impaired",
                "source",
                "kind",
            }
        TrackChoice.model_validate(choice)
