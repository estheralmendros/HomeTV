from pathlib import PurePosixPath

import pytest

from hometv.parser import SidecarInfo, parse_sidecar

VIDEO = PurePosixPath("Movie (2020)/Movie (2020).mkv")


def info(language="und", forced=False, hi=False, fmt="srt", kind="text"):
    return SidecarInfo(language=language, forced=forced, hearing_impaired=hi, format=fmt, kind=kind)


@pytest.mark.parametrize(
    ("sidecar", "expected"),
    [
        ("Movie (2020).srt", info()),
        ("Movie (2020).en.srt", info("eng")),
        ("Movie (2020).es.forced.srt", info("spa", forced=True)),
        ("Movie (2020).spa.sdh.srt", info("spa", hi=True)),
        ("Movie (2020).English.ass", info("eng", fmt="ass")),
        ("Movie (2020).pt-BR.srt", info("por")),
        ("Movie (2020).idx", info(fmt="idx", kind="image")),
        ("Movie (2020).en.sup", info("eng", fmt="sup", kind="image")),
        ("Other.en.srt", None),
    ],
)
def test_sidecar_table(sidecar, expected):
    assert parse_sidecar(VIDEO, PurePosixPath("Movie (2020)") / sidecar) == expected


def test_sub_is_never_returned_on_its_own():
    assert parse_sidecar(VIDEO, PurePosixPath("Movie (2020)/Movie (2020).sub")) is None


def test_different_directory_is_none():
    assert parse_sidecar(VIDEO, PurePosixPath("Other/Movie (2020).en.srt")) is None


def test_stem_prefix_must_be_followed_by_dot():
    assert parse_sidecar(VIDEO, PurePosixPath("Movie (2020) extra.en.srt")) is None
    assert parse_sidecar(VIDEO, PurePosixPath("Movie (2020)/Movie (2020)x.en.srt")) is None


def test_unknown_extension_is_none():
    assert parse_sidecar(VIDEO, PurePosixPath("Movie (2020)/Movie (2020).en.txt")) is None


@pytest.mark.parametrize(
    ("tags", "expected"),
    [
        ("cc", info(hi=True)),
        ("HI", info(hi=True)),
        ("en.default", info("eng")),
        ("FORCED.en", info("eng", forced=True)),
        ("fr.en", info("fre")),
        ("de", info("ger")),
        ("Spanish", info("spa")),
        ("zz-not-a-language", info()),
    ],
)
def test_sidecar_tags(tags, expected):
    sidecar = PurePosixPath(f"Movie (2020)/Movie (2020).{tags}.srt")
    assert parse_sidecar(VIDEO, sidecar) == expected


def test_extension_case_insensitive():
    sidecar = PurePosixPath("Movie (2020)/Movie (2020).EN.SRT")
    assert parse_sidecar(VIDEO, sidecar) == info("eng")


@pytest.mark.parametrize("fmt", ["ssa", "vtt"])
def test_other_text_formats(fmt):
    sidecar = PurePosixPath(f"Movie (2020)/Movie (2020).{fmt}")
    assert parse_sidecar(VIDEO, sidecar) == info(fmt=fmt)
