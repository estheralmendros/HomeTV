"""Change settling (prd.md FR-5, pure): no clock, no I/O; times and sizes come in.

Each relevant event dirties one ``ScanScope`` (``scope_for`` under the longest accepted root)
and sets its ``last_event``; video files also record their observed size. A scope is quiet once
``quiet_seconds`` passed without an event. Quiet scopes are released only when the whole burst
is quiet (every pending scope), so one copy or ``mv`` of several folders is one batch even though
the reader delivers it in batches a moment apart; a quiet scope is held at most one more quiet
period while other folders keep changing. ``due`` lists the video paths of the releasable scopes,
the caller stats them, and ``settle`` returns every one whose videos kept their size (one batch),
re-arming the others. ``suspend`` stops intake but keeps (and keeps settling) the
pending scopes; only ``set_roots`` and ``drop_root`` remove them.
"""

from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field

from hometv.changes.backend import SIZED_KINDS as _SIZED_KINDS
from hometv.changes.backend import WatchEvent
from hometv.changes.backend import file_extension as _extension
from hometv.changes.scope import MAX_PENDING_SCOPES, ScanScope, merge_scopes, root_of, scope_for
from hometv.parser import SIDECAR_EXTENSIONS, VIDEO_EXTENSIONS

_GONE_KINDS = frozenset({"deleted", "moved_from"})
_IGNORED_KINDS = frozenset({"watch_limit", "backend_failed"})


@dataclass
class _Pending:
    last_event: float
    sizes: dict[str, int | None] = field(default_factory=dict)  # video path -> recorded size


class ChangeSettler:
    def __init__(
        self, roots: Iterable[str], quiet_seconds: float, *, cap: int = MAX_PENDING_SCOPES
    ) -> None:
        self.quiet_seconds = quiet_seconds
        self.cap = cap
        self._roots: frozenset[str] = frozenset(roots)
        self._pending: dict[ScanScope, _Pending] = {}

    # -- roots --

    def set_roots(self, roots: Iterable[str]) -> None:
        """Accept events for ``roots`` (ends a suspension); drop pending scopes of other roots."""
        self._roots = frozenset(roots)
        self._pending = {s: p for s, p in self._pending.items() if s.root in self._roots}

    def suspend(self) -> None:
        """Accept no new events; pending scopes are kept and keep settling."""
        self._roots = frozenset()

    def drop_root(self, root: str) -> None:
        self._pending = {s: p for s, p in self._pending.items() if s.root != root}

    # -- intake --

    def add(self, event: WatchEvent, now: float) -> None:
        if event.kind in _IGNORED_KINDS:
            return
        if event.kind == "root_gone":
            if event.root is not None:
                self.drop_root(event.root)
            return
        if event.kind == "overflow":
            for root in self._roots:
                self._touch(ScanScope(root, "", True), now)
            self._widen()
            return
        found = root_of(event.path, self._roots)
        if found is None:
            return
        root = found
        ext = _extension(event.path)
        is_video = not event.is_dir and ext in VIDEO_EXTENSIONS
        if not event.is_dir and not is_video and ext not in SIDECAR_EXTENSIONS:
            return
        scope = scope_for(root, event.path, event.is_dir)
        if scope is None:
            return
        pending = self._touch(scope, now)
        if is_video:
            if event.kind in _SIZED_KINDS:
                pending.sizes[event.path] = event.size
            elif event.kind in _GONE_KINDS:
                pending.sizes[event.path] = None
            else:  # "modified": keep the recorded size (the event carries none)
                pending.sizes.setdefault(event.path, None)
        self._widen()

    def _touch(self, scope: ScanScope, now: float) -> _Pending:
        whole = ScanScope(scope.root, "", True)
        if whole in self._pending:
            scope = whole
        elif scope.whole_root:
            self._absorb(scope.root)
        pending = self._pending.get(scope)
        if pending is None:
            pending = self._pending[scope] = _Pending(now)
        pending.last_event = max(pending.last_event, now)
        return pending

    def _absorb(self, root: str) -> None:
        """Fold every pending scope of ``root`` into its whole-root scope."""
        whole = ScanScope(root, "", True)
        for scope in [s for s in self._pending if s.root == root and s != whole]:
            self._fold(self._pending.pop(scope), whole)

    def _fold(self, entry: _Pending, target: ScanScope) -> None:
        existing = self._pending.get(target)
        if existing is None:
            self._pending[target] = entry
            return
        existing.last_event = max(existing.last_event, entry.last_event)
        existing.sizes.update(entry.sizes)

    def _widen(self) -> None:
        if len(self._pending) <= self.cap:
            return
        for scope in merge_scopes(self._pending, self.cap):
            if scope.whole_root:
                self._absorb(scope.root)

    # -- settling --

    @property
    def pending(self) -> list[ScanScope]:
        return sorted(self._pending)

    def next_deadline(self) -> float | None:
        if not self._pending:
            return None
        times = [p.last_event for p in self._pending.values()]
        return min(max(times) + self.quiet_seconds, min(times) + 2 * self.quiet_seconds)

    def _quiet(self, now: float) -> list[ScanScope]:
        """Quiet scopes, once the burst is quiet or one of them waited a second quiet period."""
        ages = {s: now - p.last_event for s, p in self._pending.items()}
        if not ages or (
            min(ages.values()) < self.quiet_seconds and max(ages.values()) < 2 * self.quiet_seconds
        ):
            return []
        return sorted(s for s, age in ages.items() if age >= self.quiet_seconds)

    def due(self, now: float) -> list[str]:
        """Video paths of the releasable quiet scopes (the caller stats them for ``settle``)."""
        return [path for scope in self._quiet(now) for path in self._pending[scope].sizes]

    def settle(self, now: float, sizes: Mapping[str, int | None]) -> list[ScanScope]:
        """Quiet scopes whose videos all kept their recorded size, removed and returned
        together; a changed (or unchecked) video records the new size and re-arms its scope."""
        ready: list[ScanScope] = []
        for scope in self._quiet(now):
            entry = self._pending[scope]
            stable = True
            for path, recorded in entry.sizes.items():
                if path not in sizes:
                    # Not checked: re-arm. poll_once passes one ``now`` to ``due`` and here, so
                    # this happens only if the pending set changed during its stat (e.g. a
                    # dropped root ended the burst and released a scope ``due`` held back).
                    stable = False
                elif sizes[path] != recorded:
                    entry.sizes[path] = sizes[path]
                    stable = False
            if stable:
                ready.append(scope)
                del self._pending[scope]
            else:
                entry.last_event = now
        return ready
