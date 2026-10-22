from datetime import UTC, datetime, timedelta, timezone

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
