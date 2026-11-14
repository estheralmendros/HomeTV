"""``parse_report.report_lines``: pure formatting of the parse-upgrade dry run."""

from hometv.parse_report import ReportRow, report_lines


def film(title, year=None, part=None):
    return {"kind": "film", "title": title, "year": year, "part": part}


def test_empty_input_prints_header_and_zero_summary():
    assert report_lines([]) == [
        "status\tstored\tnew\tpath",
        "",
        "# 0 film files, 0 differ: pending 0, unmatched 0, matched 0, manual 0, unlinked 0",
    ]


def test_only_differing_rows_sorted_by_status_then_path_with_dashes():
    rows = [
        ReportRow("matched", "b.mkv", film("Mission"), film("Mission - Fallout", 2018)),
        ReportRow("pending", "same.mkv", film("Same", 2000), film("Same", 2000)),
        ReportRow("pending", "z.mkv", film("doy mis ojos", 2003), film("Te doy mis ojos", 2003)),
        ReportRow("unlinked", "u.mkv", None, film("Spider-Man", 2002)),
        ReportRow("pending", "a.mkv", film("A", 1999, 1), film("A", 1999, 2)),
        ReportRow("manual", "m.mkv", film("M"), None),
    ]
    assert report_lines(rows) == [
        "status\tstored\tnew\tpath",
        "pending\tfilm|A|1999|1\tfilm|A|1999|2\ta.mkv",
        "pending\tfilm|doy mis ojos|2003|-\tfilm|Te doy mis ojos|2003|-\tz.mkv",
        "matched\tfilm|Mission|-|-\tfilm|Mission - Fallout|2018|-\tb.mkv",
        "manual\tfilm|M|-|-\t-\tm.mkv",
        "unlinked\t-\tfilm|Spider-Man|2002|-\tu.mkv",
        "",
        "# 6 film files, 5 differ: pending 2, unmatched 0, matched 1, manual 1, unlinked 1",
    ]
