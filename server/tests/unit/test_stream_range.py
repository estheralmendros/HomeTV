import pytest

from hometv.playback.stream import ByteRange, RangeNotSatisfiable, content_type, parse_range


@pytest.mark.parametrize(
    ("header", "expected"),
    [
        (None, None),
        ("", None),
        ("bytes=0-99", ByteRange(0, 99)),
        ("bytes=100-", ByteRange(100, 999)),
        ("bytes=-100", ByteRange(900, 999)),
        ("bytes=-5000", ByteRange(0, 999)),
        ("bytes=900-5000", ByteRange(900, 999)),
        ("bytes=0-0", ByteRange(0, 0)),
        ("bytes=10-19, 30-39", ByteRange(10, 19)),
        ("items=0-1", None),
        ("bytes=abc", None),
    ],
)
def test_parse_range(header, expected):
    assert parse_range(header, 1000) == expected


@pytest.mark.parametrize("header", ["bytes=1000-", "bytes=1000-2000", "bytes=50-10", "bytes=-0"])
def test_unsatisfiable(header):
    with pytest.raises(RangeNotSatisfiable):
        parse_range(header, 1000)


def test_content_range_and_length():
    r = ByteRange(100, 999)
    assert r.length == 900
    assert r.content_range(1000) == "bytes 100-999/1000"


@pytest.mark.parametrize(
    ("container", "mime"),
    [
        ("mkv", "video/x-matroska"),
        ("mp4", "video/mp4"),
        ("webm", "video/webm"),
        ("avi", "video/x-msvideo"),
        ("mov", "video/quicktime"),
        ("ts", "video/mp2t"),
        ("m2ts", "video/mp2t"),
        ("asf", "application/octet-stream"),
        (None, "application/octet-stream"),
    ],
)
def test_content_type(container, mime):
    assert content_type(container) == mime
