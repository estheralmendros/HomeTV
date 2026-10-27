"""In-memory progress of one scan run (ScanStatus counts/errors) and its ``scan_run`` row.

Shared by the scanner, enrichment and the scheduler. ``save(session, run_id)`` copies the
report into the ``scan_run`` row inside the caller's (short) write transaction.
"""

from dataclasses import dataclass, field
from typing import Any

from sqlalchemy.orm import Session

from hometv.db.models import ScanRun

COUNT_KEYS = (
    "files_seen",
    "new",
    "changed",
    "unchanged",
    "ignored",
    "unmatched",
    "missing",
    "restored",
    "removed",
)
MAX_ERRORS = 50


@dataclass
class ScanReport:
    phase: str = "walking"
    processed: int = 0
    total: int | None = None
    counts: dict[str, int] = field(default_factory=lambda: dict.fromkeys(COUNT_KEYS, 0))
    errors: list[str] = field(default_factory=list)
    # Roots a walk could not reach (``root_unavailable`` or a ``walk_error`` of the root itself);
    # not persisted: the scheduler hands it to the change watcher after every run.
    unreachable_roots: set[str] = field(default_factory=set)

    def add_error(self, message: str) -> None:
        """Record an error string (deduplicated, at most ``MAX_ERRORS`` kept)."""
        if message not in self.errors and len(self.errors) < MAX_ERRORS:
            self.errors.append(message)

    def count(self, key: str, n: int = 1) -> None:
        self.counts[key] = self.counts.get(key, 0) + n

    def save(self, session: Session, run_id: int | None, **extra: Any) -> None:
        if run_id is None:
            return
        run = session.get(ScanRun, run_id)
        if run is None:
            return
        run.phase = self.phase
        run.processed = self.processed
        run.total = self.total
        run.counts = dict(self.counts)
        run.errors = list(self.errors)
        for key, value in extra.items():
            setattr(run, key, value)
