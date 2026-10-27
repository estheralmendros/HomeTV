from datetime import UTC, datetime, timedelta, timezone

import pytest
from pydantic import BaseModel

from hometv.api.schemas import Page, UtcDateTime


class _M(BaseModel):
    at: UtcDateTime
    maybe: UtcDateTime | None = None


def test_utc_datetime_serializes_iso_with_z():
    m = _M(at=datetime(2026, 10, 21, 18, 4, 5, 123456, tzinfo=UTC))
    assert m.model_dump(mode="json") == {"at": "2026-10-21T18:04:05Z", "maybe": None}


def test_utc_datetime_converts_other_offsets_and_naive_as_utc():
    plus2 = timezone(timedelta(hours=2))
    assert _M(at=datetime(2026, 10, 21, 20, 4, 5, tzinfo=plus2)).model_dump(mode="json")["at"] == (
        "2026-10-21T18:04:05Z"
    )
    assert _M(at=datetime(2026, 10, 21, 18, 4, 5)).model_dump(mode="json")["at"] == (
        "2026-10-21T18:04:05Z"
    )


def test_page_shape():
    page = Page[int](items=[1, 2], page=1, page_size=50, total=2)
    assert page.model_dump() == {"items": [1, 2], "page": 1, "page_size": 50, "total": 2}


# --- HOMETV-1: WatcherStatus / ScanStatus (prd2 FR-23) -------------------------------------


def test_disabled_watcher_constant():
    from hometv.api.schemas import DISABLED_WATCHER

    assert DISABLED_WATCHER.model_dump() == {
        "state": "disabled",
        "unavailable_roots": (),
        "detail": None,
    }


def test_watcher_status_is_frozen():
    import pydantic

    from hometv.api.schemas import WatcherStatus

    status = WatcherStatus(state="active", unavailable_roots=["/b", "/a"], detail=None)
    with pytest.raises(pydantic.ValidationError):
        setattr(status, "state", "error")  # noqa: B010


def test_watcher_status_roots_are_a_tuple_serialized_as_an_array():
    import json

    from hometv.api.schemas import DISABLED_WATCHER, WatcherStatus

    status = WatcherStatus(state="active", unavailable_roots=["/b", "/a"], detail=None)
    assert status.unavailable_roots == ("/b", "/a")
    assert DISABLED_WATCHER.unavailable_roots == ()  # shared singleton: nothing to mutate
    assert json.loads(status.model_dump_json())["unavailable_roots"] == ["/b", "/a"]
    for mode in ("validation", "serialization"):
        prop = WatcherStatus.model_json_schema(mode=mode)["properties"]["unavailable_roots"]
        assert (prop["type"], prop["items"]) == ("array", {"type": "string"})


def test_watcher_status_detail_with_a_lone_surrogate_serializes():
    import os

    from hometv.api.schemas import WatcherStatus

    bad = os.fsdecode(b"\xff")
    status = WatcherStatus(
        state="error",
        unavailable_roots=[f"/media/{bad}"],
        detail=f"inotify reader stopped: OSError: {bad}",
    )
    text = status.model_dump_json()  # would raise PydanticSerializationError unsanitized
    assert "inotify reader stopped: OSError: \\\\udcff" in text
    assert status.detail == "inotify reader stopped: OSError: \\udcff"
    assert status.unavailable_roots == ("/media/\\udcff",)
