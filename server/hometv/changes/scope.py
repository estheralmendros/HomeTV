"""Scan scopes (prd.md FR-4, pure): what a change-triggered (``watch``) run walks.

Three shapes of ``ScanScope(root, rel_dir, recursive)``:

- top-level dir ``(root, "A", True)``: ``root/A`` and everything below it;
- root shallow ``(root, "", False)``: only the files directly in ``root``;
- whole root ``(root, "", True)``: the widened scope (overflow / more than ``cap`` scopes).

``root_of`` picks the **longest** configured root containing a path (seeded roots may nest).
Names starting with ``.`` or not valid UTF-8 never produce a scope (the scanner skips them too,
and a scope is stored in ``scan_run.scope`` and returned by the API).
"""

import os
from collections import Counter
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import Any

from hometv.text import undecodable

MAX_PENDING_SCOPES = 50


def within(path: str, root: str) -> bool:
    """``path`` equals ``root`` or is below it (path-component prefix, not string prefix)."""
    return path == root or path.startswith(root.rstrip("/") + "/")


@dataclass(frozen=True, order=True)
class ScanScope:
    root: str  # a configured root, as stored (not realpath)
    rel_dir: str  # "" or one top-level entry name under root (no separators)
    recursive: bool

    @property
    def path(self) -> str:
        return self.root if self.rel_dir == "" else os.path.join(self.root, self.rel_dir)

    @property
    def whole_root(self) -> bool:
        return self.rel_dir == "" and self.recursive

    def contains(self, path: str) -> bool:
        if self.recursive:
            return within(path, self.path)
        return os.path.dirname(path) == self.root

    def to_json(self) -> dict[str, Any]:
        return {"root": self.root, "path": self.path, "recursive": self.recursive}

    @classmethod
    def from_json(cls, value: Mapping[str, Any]) -> "ScanScope":
        """Inverse of ``to_json`` for round-trip tests; production reads ``ScanScopeOut``."""
        root = str(value["root"])
        path = str(value["path"])
        rel_dir = "" if path == root else os.path.relpath(path, root)
        return cls(root, rel_dir, bool(value["recursive"]))


def root_of(path: str, roots: Iterable[str]) -> str | None:
    """The longest root that equals ``path`` or is a path-component prefix of it."""
    best: str | None = None
    for root in roots:
        if within(path, root) and (best is None or len(root) > len(best)):
            best = root
    return best


def scope_for(root: str, path: str, is_dir: bool) -> ScanScope | None:
    """The scope a change of ``path`` (under ``root``) dirties; None for the root itself, a
    path outside it, or any dot / undecodable component."""
    if path == root or not within(path, root):
        return None
    parts = PurePosixPath(os.path.relpath(path, root)).parts
    if any(part.startswith(".") or undecodable(part) for part in parts):
        return None
    if len(parts) == 1 and not is_dir:
        return ScanScope(root, "", False)
    return ScanScope(root, parts[0], True)


def merge_scopes(scopes: Iterable[ScanScope], cap: int = MAX_PENDING_SCOPES) -> list[ScanScope]:
    """Deduplicate; a whole-root scope absorbs every other scope of its root; while more than
    ``cap`` remain, widen the root with the most scopes (ties: smallest root) to a whole-root
    scope. Stops when no root has two or more scopes left (more distinct roots than ``cap``:
    widening cannot shrink the list further). Sorted result."""
    unique = set(scopes)
    whole = {s.root for s in unique if s.whole_root}
    merged = {s for s in unique if s.root not in whole or s.whole_root}
    while len(merged) > cap:
        counts = Counter(s.root for s in merged)
        root, count = min(counts.items(), key=lambda item: (-item[1], item[0]))
        if count < 2:
            break
        merged = {s for s in merged if s.root != root} | {ScanScope(root, "", True)}
    return sorted(merged)
