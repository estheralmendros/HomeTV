"""Pure decisions of the scanner parse upgrade (no I/O).

When ``PARSER_VERSION`` rises, films-library rows with an older stamp are reparsed. This module
decides *what* to do with a film and its present files; ``scanner.Scanner`` applies the plan
inside one write transaction (every lookup answer given here is re-read there).

``Key`` = ``(normalize_title(title), year)``, the identity ``find_film`` looks up.
"""

from collections import Counter
from collections.abc import Callable, Sequence
from dataclasses import dataclass

type Key = tuple[str, int | None]
type Lookup = Callable[[Key], int | None]  # film id owning the key (alias first), or None


@dataclass(frozen=True)
class FilmState:
    id: int
    key: Key  # the film's stored (parsed_title_norm, parsed_year)
    title: str  # the film's stored parsed_title
    eligible: bool  # pending/unmatched and no present file with a manual match


@dataclass(frozen=True)
class Member:
    """A present file of the film. ``key``/``title`` are None when its new parse is not a film."""

    file_id: int
    key: Key | None
    title: str | None


@dataclass(frozen=True)
class Noop:
    """Same identity and title: files only get their new ``parsed`` and the stamp."""


@dataclass(frozen=True)
class Retitle:
    """Same key, different display title (punctuation): the film's titles are updated."""

    title: str


@dataclass(frozen=True)
class Rename:
    """The key is free: rename the film in place (per-profile rows stay on it)."""

    title: str
    year: int | None


@dataclass(frozen=True)
class MergeInto:
    """Another film owns the key: merge this film into it."""

    film_id: int


@dataclass(frozen=True)
class Split:
    """Members disagree. ``stay`` keep the film, ``moves`` are relinked by key, ``detached`` leave
    through the normal parse (file-level entry). ``merge_into`` (only when nothing stays and a
    film receives files) is the key of the film the emptied film merges into."""

    stay: tuple[int, ...]
    moves: tuple[int, ...]
    detached: tuple[int, ...]
    merge_into: Key | None


@dataclass(frozen=True)
class Keep:
    """No member parses as a film any more: files stay on the film untouched (only stamped), so
    its per-profile rows are never orphaned."""


@dataclass(frozen=True)
class Defer:
    """Not every present file is walked or already upgraded: a later run retries."""


@dataclass(frozen=True)
class Skip:
    """The film is no longer eligible (or has no files): write nothing, stamp nothing."""


type FilmPlan = Noop | Retitle | Rename | MergeInto | Split | Keep | Defer | Skip


def plan_film_upgrade(
    film: FilmState,
    members: Sequence[Member],
    lookup: Lookup,
    *,
    complete: bool = True,
) -> FilmPlan:
    if not film.eligible or not members:
        return Skip()
    if not complete:
        return Defer()

    def owner(key: Key) -> int | None:
        return film.id if key == film.key else lookup(key)

    keys = {mem.key for mem in members}
    if keys == {None}:
        return Keep()
    if len(keys) == 1 and None not in keys:
        key = next(iter(keys))
        assert key is not None
        first = min(members, key=lambda mem: mem.file_id)
        title = first.title or key[0]
        target = owner(key)
        if target is None:
            return Rename(title, key[1])
        if target != film.id:
            return MergeInto(target)
        return Retitle(title) if title != film.title else Noop()

    ordered = sorted(members, key=lambda mem: mem.file_id)
    stay: list[int] = []
    moves: list[int] = []
    detached: list[int] = []
    for mem in ordered:
        if mem.key is None:
            detached.append(mem.file_id)
        elif owner(mem.key) == film.id:
            stay.append(mem.file_id)
        else:
            moves.append(mem.file_id)
    merge_into: Key | None = None
    if not stay:
        moved_keys = [mem.key for mem in ordered if mem.key is not None]
        if moved_keys:
            merge_into = _majority(moved_keys, owner)
    return Split(tuple(stay), tuple(moves), tuple(detached), merge_into)


def _majority(keys: Sequence[Key], owner: Lookup) -> Key:
    """Most files wins; a tie goes to the lowest film id (a film still to be created ranks last,
    then the key met first)."""
    counts = Counter(keys)
    first_seen = {key: index for index, key in reversed(list(enumerate(keys)))}

    def rank(key: Key) -> tuple[int, float, int]:
        film_id = owner(key)
        return (-counts[key], float("inf") if film_id is None else film_id, first_seen[key])

    return min(counts, key=rank)


# --- protected films: keep the key a later parse will produce -------------------------------


@dataclass(frozen=True)
class Alias:
    """The new key is free: write an ``ItemAlias`` to the protected film."""


@dataclass(frozen=True)
class MergePending:
    """A pending/unmatched film owns the key: merge it into the protected film."""

    film_id: int


@dataclass(frozen=True)
class Conflict:
    """Another protected film owns the key: write nothing (logged, listed by parse_report)."""

    film_id: int


type ProtectedAction = Alias | MergePending | Conflict


def plan_protected_key(
    film_id: int, film_key: Key, key: Key, *, found: int | None, found_protected: bool
) -> ProtectedAction | None:
    """``found`` = ``find_film`` answer for ``key``; ``found_protected``: that film is protected."""
    if key == film_key or found == film_id:
        return None
    if found is None:
        return Alias()
    return Conflict(found) if found_protected else MergePending(found)
