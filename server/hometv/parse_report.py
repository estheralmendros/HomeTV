"""Dev tool: which films-library files would parse differently today.

Run as ``python -m hometv.parse_report``. Read-only: opens the database with ``read_session()``
only (no migrations, no writes, no media folder access) and selects explicit columns, never
``parser_version``, so it also works on a database still at revision 0007. Prints the files whose
new parse (kind, title, year, part) differs from the stored ``media_file.parsed``, tab-separated,
then a summary line.
"""

import sys
from collections import defaultdict
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import Any

from sqlalchemy import select

from hometv.config import Settings, load_settings
from hometv.db import models as m
from hometv.db.engine import Database
from hometv.parser import parse
from hometv.scanner import serialize_parse

STATUSES = ("pending", "unmatched", "matched", "manual", "unlinked")
HEADER = "status\tstored\tnew\tpath"


@dataclass(frozen=True)
class ReportRow:
    """A films-library file: ``stored``/``new`` are ``media_file.parsed``-shaped dicts or None."""

    status: str
    path: str
    stored: dict[str, Any] | None
    new: dict[str, Any] | None


def _signature(parsed: dict[str, Any] | None) -> tuple[Any, ...] | None:
    if parsed is None:
        return None
    if parsed.get("kind") == "episode":
        return ("episode", parsed.get("show_title"), parsed.get("show_year"), None)
    return (parsed.get("kind"), parsed.get("title"), parsed.get("year"), parsed.get("part"))


def _cell(parsed: dict[str, Any] | None) -> str:
    signature = _signature(parsed)
    if signature is None:
        return "-"
    return "|".join("-" if part is None else str(part) for part in signature)


def report_lines(rows: Iterable[ReportRow]) -> list[str]:
    """Header, the differing rows (by status, then path) and a summary line."""
    all_rows = list(rows)
    differing = [r for r in all_rows if _signature(r.stored) != _signature(r.new)]
    order = {status: i for i, status in enumerate(STATUSES)}
    differing.sort(key=lambda r: (order.get(r.status, len(order)), r.path))
    counts = dict.fromkeys(STATUSES, 0)
    for row in differing:
        counts[row.status] = counts.get(row.status, 0) + 1
    lines = [HEADER]
    lines += [f"{r.status}\t{_cell(r.stored)}\t{_cell(r.new)}\t{r.path}" for r in differing]
    by_status = ", ".join(f"{status} {count}" for status, count in counts.items())
    lines += ["", f"# {len(all_rows)} film files, {len(differing)} differ: {by_status}"]
    return lines


def collect_rows(db: Database) -> list[ReportRow]:
    with db.read_session() as session:
        found = session.execute(
            select(
                m.MediaFile.relative_path,
                m.MediaFile.parsed,
                m.MediaFile.manual_match,
                m.Film.match_status,
            )
            .join(m.Library, m.Library.id == m.MediaFile.library_id)
            .outerjoin(m.Film, m.Film.id == m.MediaFile.film_id)
            .where(m.Library.type == "films")
        ).all()
    stems: dict[str, list[str]] = defaultdict(list)
    for relative_path, *_rest in found:
        path = PurePosixPath(relative_path)
        stems[str(path.parent)].append(path.stem)
    rows: list[ReportRow] = []
    for relative_path, stored, manual_match, match_status in found:
        path = PurePosixPath(relative_path)
        siblings = list(stems[str(path.parent)])
        siblings.remove(path.stem)  # the other rows only
        status = "manual" if manual_match else (match_status or "unlinked")
        new = serialize_parse(parse(path, "films", frozenset(siblings)))
        rows.append(ReportRow(status, relative_path, stored, new))
    return rows


def main(settings: Settings | None = None, *, write: Callable[[str], object] = print) -> int:
    settings = settings or load_settings()
    if not settings.db_path.exists():
        print(f"database not found: {settings.db_path}", file=sys.stderr)
        return 1
    db = Database(settings.db_path)
    try:
        for line in report_lines(collect_rows(db)):
            write(line)
    finally:
        db.dispose()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
