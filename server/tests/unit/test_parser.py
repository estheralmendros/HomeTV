from datetime import date
from pathlib import PurePosixPath

import pytest

from hometv.parser import Ignored, MultiPart, ParsedEpisode, ParsedFilm, Unparseable, parse

F, S = "films", "shows"

# (row, library_type, relative_path, siblings, expected) — the filename-parsing table, rows 1-38.
ROWS = [
    (1, F, "Dune (2021)/Dune (2021).mkv", (), ParsedFilm("Dune", 2021)),
    (2, F, "Dune.2021.2160p.UHD.BluRay.x265-GROUP.mkv", (), ParsedFilm("Dune", 2021)),
    (3, F, "The Matrix (1999).mp4", (), ParsedFilm("The Matrix", 1999)),
    (
        4,
        F,
        "Blade Runner 2049 (2017)/Blade.Runner.2049.2017.1080p.mkv",
        (),
        ParsedFilm("Blade Runner 2049", 2017),
    ),
    (
        5,
        F,
        "2001 A Space Odyssey (1968)/2001.A.Space.Odyssey.1968.mkv",
        (),
        ParsedFilm("2001 A Space Odyssey", 1968),
    ),
    (6, F, "Amélie (2001)/Amelie.mkv", (), ParsedFilm("Amélie", 2001)),
    (7, F, "Inception.mkv", (), ParsedFilm("Inception", None)),
    (8, F, "Alien (1979)/Alien.1979.Directors.Cut.1080p.mkv", (), ParsedFilm("Alien", 1979)),
    (9, F, "Dune (2021)/Dune (2021) - 2160p.mkv", (), ParsedFilm("Dune", 2021)),
    (
        10,
        F,
        "Kill Bill Vol 1 (2003)/Kill.Bill.Vol.1.2003.cd1.avi",
        (),
        MultiPart("Kill Bill Vol 1", 2003, 1),
    ),
    (
        11,
        F,
        "Heat (1995)/Heat (1995) - Part 2.mkv",
        ("Heat (1995) - Part 1",),
        MultiPart("Heat", 1995, 2),
    ),
    (12, F, "Dune (2021)/Extras/Making of.mkv", (), Ignored("extra")),
    (13, F, "Dune (2021)/Behind The Scenes/Sandworm.mkv", (), Ignored("extra")),
    (14, F, "Dune (2021)/Trailers/Teaser.mp4", (), Ignored("trailer")),
    (15, F, "Dune (2021)/Dune-trailer.mp4", (), Ignored("trailer")),
    (16, F, "Dune (2021)/sample.mkv", (), Ignored("sample")),
    (17, F, "Dune.2021.1080p-sample.mkv", (), Ignored("sample")),
    (18, F, "Dune (2021)/Dune (2021).nfo", (), Ignored("non_video")),
    (19, F, "Dune (2021)/._Dune (2021).mkv", (), Ignored("hidden")),
    (20, F, "Dune (2021)/Dune (2021).en.srt", (), Ignored("non_video")),
    (
        21,
        S,
        "The Office/Season 02/The Office S02E03.mkv",
        (),
        ParsedEpisode("The Office", None, 2, (3,)),
    ),
    (
        22,
        S,
        "Breaking Bad (2008)/Season 1/Breaking.Bad.S01E01.720p.mkv",
        (),
        ParsedEpisode("Breaking Bad", 2008, 1, (1,)),
    ),
    (
        23,
        S,
        "Friends/Season 01/Friends.S01E01E02.mkv",
        (),
        ParsedEpisode("Friends", None, 1, (1, 2)),
    ),
    (
        24,
        S,
        "Friends/Season 01/Friends - S01E16-E17.mkv",
        (),
        ParsedEpisode("Friends", None, 1, (16, 17)),
    ),
    (25, S, "Lost/Season 3/3x07 - Not in Portland.mkv", (), ParsedEpisode("Lost", None, 3, (7,))),
    (
        26,
        S,
        "Doctor Who (2005)/Specials/Doctor.Who.2005.S00E01.mkv",
        (),
        ParsedEpisode("Doctor Who", 2005, 0, (1,)),
    ),
    (27, S, "Dark/Temporada 2/Episode 05.mkv", (), ParsedEpisode("Dark", None, 2, (5,))),
    (28, S, "Severance.S01E01.2160p.mkv", (), ParsedEpisode("Severance", None, 1, (1,))),
    (29, S, "The Daily Show/2021-03-04.mkv", (), Unparseable("no_episode_number", "")),
    (30, S, "One Piece/One Piece - 105.mkv", (), Unparseable("no_season", "")),
    (31, S, "The Office/Season 02/Extras/Bloopers.mkv", (), Ignored("extra")),
    (
        32,
        F,
        "Harry Potter and the Deathly Hallows Part 1 (2010)/"
        "Harry.Potter.and.the.Deathly.Hallows.Part.1.2010.1080p.mkv",
        (),
        ParsedFilm("Harry Potter and the Deathly Hallows Part 1", 2010),
    ),
    (
        33,
        F,
        "The Hunger Games Mockingjay - Part 2 (2015).mkv",
        (),
        ParsedFilm("The Hunger Games Mockingjay Part 2", 2015),
    ),
    (
        34,
        F,
        "Twilight Breaking Dawn Part 1 (2011).mkv",
        ("Twilight Breaking Dawn Part 2 (2012)",),
        ParsedFilm("Twilight Breaking Dawn Part 1", 2011),
    ),
    (35, F, "Heat (1995)/Heat (1995) - Part 2.mkv", (), ParsedFilm("Heat Part 2", 1995)),
    (
        36,
        F,
        "Kill Bill Vol 1 (2003)/Kill.Bill.Vol.1.2003.CD2.avi",
        (),
        MultiPart("Kill Bill Vol 1", 2003, 2),
    ),
    (
        37,
        S,
        "One Piece/Season 05/One Piece - 105.mkv",
        (),
        ParsedEpisode("One Piece", None, 5, (105,)),
    ),
    (38, S, "Lost/Season 3/Lost.S03E07.mkv", (), ParsedEpisode("Lost", None, 3, (7,))),
]


def _compare(result, expected):
    if isinstance(expected, Unparseable):
        # `detail` is free text; the reason is the contract.
        assert isinstance(result, Unparseable), result
        assert result.reason == expected.reason
        assert isinstance(result.detail, str)
    else:
        assert result == expected


@pytest.mark.parametrize(
    ("library_type", "path", "siblings", "expected"),
    [pytest.param(lt, p, s, e, id=f"row{n:02d}") for n, lt, p, s, e in ROWS],
)
def test_parse_table(library_type, path, siblings, expected):
    result = parse(PurePosixPath(path), library_type, frozenset(siblings))
    _compare(result, expected)


def test_table_has_38_rows():
    assert [r[0] for r in ROWS] == list(range(1, 39))


# --- Additional behavior beyond the table -------------------------------------------------


def test_parse_does_no_filesystem_io_on_nonexistent_paths():
    path = PurePosixPath("Definitely Not Here (2020)/Definitely.Not.Here.2020.mkv")
    assert parse(path, "films") == ParsedFilm("Definitely Not Here", 2020)


def test_parse_does_not_touch_filesystem(monkeypatch):
    import os

    def boom(*_a, **_k):
        raise AssertionError("parser must not do I/O")

    for name in ("stat", "lstat", "listdir", "scandir"):
        monkeypatch.setattr(os, name, boom)
    assert parse(PurePosixPath("Dune (2021)/Dune (2021).mkv"), "films") == ParsedFilm("Dune", 2021)


@pytest.mark.parametrize(
    "ext",
    [".MKV", ".Mp4", ".m4v", ".avi", ".mov", ".ts", ".m2ts", ".webm", ".wmv", ".mpg", ".mpeg"],
)
def test_video_extensions_case_insensitive(ext):
    assert parse(PurePosixPath(f"Dune (2021)/Dune (2021){ext}"), "films") == ParsedFilm(
        "Dune", 2021
    )


def test_hidden_directory_component():
    assert parse(PurePosixPath(".hidden/Dune (2021).mkv"), "films") == Ignored("hidden")


def test_ds_store_is_hidden():
    assert parse(PurePosixPath("Dune (2021)/.DS_Store"), "films") == Ignored("hidden")


@pytest.mark.parametrize("folder", ["extras", "FEATURETTES", "Deleted Scenes", "behind the scenes"])
def test_extras_folders_case_insensitive(folder):
    assert parse(PurePosixPath(f"Dune (2021)/{folder}/x.mkv"), "films") == Ignored("extra")


def test_extras_only_applies_to_directories_not_file_stem():
    assert parse(PurePosixPath("Extras (2009).mkv"), "films") == ParsedFilm("Extras", 2009)


@pytest.mark.parametrize(
    "path",
    [
        "Dune (2021)/SAMPLE.mkv",
        "Dune (2021)/Dune.sample.mkv",
        "Dune (2021)/Dune sample.mkv",
        "Dune (2021)/Sample/dune.mkv",
    ],
)
def test_sample_variants(path):
    assert parse(PurePosixPath(path), "films") == Ignored("sample")


def test_trailer_suffix_case_insensitive():
    assert parse(PurePosixPath("Dune (2021)/Dune-TRAILER.mkv"), "films") == Ignored("trailer")


def test_disc_token_always_multipart():
    path = PurePosixPath("Avatar (2009)/Avatar (2009) disc2.mkv")
    assert parse(path, "films") == MultiPart("Avatar", 2009, 2)


def test_pt_token_multipart_with_sibling():
    path = PurePosixPath("Heat (1995)/Heat (1995) pt2.mkv")
    assert parse(path, "films", frozenset({"Heat (1995) pt1"})) == MultiPart("Heat", 1995, 2)


def test_part_sibling_compared_case_insensitively():
    path = PurePosixPath("Heat (1995)/Heat (1995) - Part 2.mkv")
    assert parse(path, "films", frozenset({"heat (1995) - PART 1"})) == MultiPart("Heat", 1995, 2)


def test_part_sibling_with_same_number_is_not_multipart():
    path = PurePosixPath("Heat (1995)/Heat (1995) - Part 2.mkv")
    result = parse(path, "films", frozenset({"Heat (1995) - Part 2"}))
    assert result == ParsedFilm("Heat Part 2", 1995)


def test_film_folder_year_wins_when_filename_has_none():
    assert parse(PurePosixPath("Amélie (2001)/Amelie.mkv"), "films") == ParsedFilm("Amélie", 2001)


def test_year_out_of_range_discarded():
    next_year_plus = date.today().year + 5
    result = parse(PurePosixPath(f"Future Film ({next_year_plus}).mkv"), "films")
    assert isinstance(result, ParsedFilm)
    assert result.year is None


def test_film_no_title_unparseable():
    result = parse(PurePosixPath("1080p.mkv"), "films")
    assert isinstance(result, Unparseable)
    assert result.reason == "no_title"


def test_absolute_number_without_season_is_never_s01():
    result = parse(PurePosixPath("Naruto/Naruto 105.mkv"), "shows")
    assert isinstance(result, Unparseable)
    assert result.reason == "no_season"


def test_season_folder_short_form():
    result = parse(PurePosixPath("Dark/S2/Episode 05.mkv"), "shows")
    assert result == ParsedEpisode("Dark", None, 2, (5,))


def test_series_folder():
    result = parse(PurePosixPath("Dark/Series 3/Episode 01.mkv"), "shows")
    assert result == ParsedEpisode("Dark", None, 3, (1,))


def test_episode_range_without_e_prefix():
    result = parse(PurePosixPath("Friends/Season 01/Friends - S01E16-18.mkv"), "shows")
    assert result == ParsedEpisode("Friends", None, 1, (16, 17, 18))


def test_date_based_name_without_season_folder_reports_missing_episode():
    # Row 29: a missing episode number is reported before a missing season.
    result = parse(PurePosixPath("The Daily Show/Season 2021/2021-03-04.mkv"), "shows")
    assert isinstance(result, Unparseable)
    assert result.reason == "no_episode_number"


def test_kill_bill_volume_preserved_in_title():
    result = parse(PurePosixPath("Kill Bill Vol 2 (2004)/Kill.Bill.Vol.2.2004.mkv"), "films")
    assert result == ParsedFilm("Kill Bill Vol 2", 2004)


def test_show_folder_year_stripped():
    result = parse(PurePosixPath("Doctor Who (2005)/Season 1/Doctor.Who.S01E01.mkv"), "shows")
    assert result == ParsedEpisode("Doctor Who", 2005, 1, (1,))


def test_root_folder_name_does_not_influence_parsing():
    # relative_path excludes the root: the first component is always the show folder.
    result = parse(PurePosixPath("Lost/Season 3/Lost.S03E07.mkv"), "shows")
    assert result.show_title == "Lost"
