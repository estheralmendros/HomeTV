"""``changes.scope`` (prd.md FR-4): ``ScanScope``, ``root_of``, ``scope_for``, ``merge_scopes``."""

import os

import pytest

from hometv.changes.scope import (
    MAX_PENDING_SCOPES,
    ScanScope,
    merge_scopes,
    root_of,
    scope_for,
)
from hometv.text import undecodable

P = "/media/películas"
S = "/media/series"
M = "/media/MKV"
BAD = os.fsdecode(b"\xff.mkv")


def test_undecodable_detects_surrogate_escapes():
    assert undecodable(BAD)
    assert not undecodable("Dune (2021).mkv")


def test_scanner_reexports_undecodable():
    from hometv import scanner

    assert scanner.undecodable is undecodable


@pytest.mark.parametrize(
    ("scope", "path"),
    [
        (ScanScope(P, "A", True), f"{P}/A"),
        (ScanScope(S, "", False), S),
        (ScanScope(S, "", True), S),
    ],
)
def test_path(scope, path):
    assert scope.path == path


@pytest.mark.parametrize(
    ("scope", "path", "inside"),
    [
        (ScanScope(P, "A", True), f"{P}/A/Dune.mkv", True),
        (ScanScope(P, "A", True), f"{P}/A/x/y/Dune.mkv", True),
        (ScanScope(P, "A", True), f"{P}/AB/Dune.mkv", False),
        (ScanScope(P, "A", True), f"{P}/B/Dune.mkv", False),
        (ScanScope(S, "", False), f"{S}/The Office S01E01.mkv", True),
        (ScanScope(S, "", False), f"{S}/Breaking Bad/x.mkv", False),
        (ScanScope(S, "", True), f"{S}/Breaking Bad/x.mkv", True),
        (ScanScope(S, "", True), f"{S}x/a.mkv", False),
        (ScanScope(S, "", True), f"{P}/a.mkv", False),
    ],
)
def test_contains(scope, path, inside):
    assert scope.contains(path) is inside


@pytest.mark.parametrize(
    "scope", [ScanScope(P, "A", True), ScanScope(S, "", False), ScanScope(S, "", True)]
)
def test_json_round_trip(scope):
    data = scope.to_json()
    assert data == {"root": scope.root, "path": scope.path, "recursive": scope.recursive}
    assert ScanScope.from_json(data) == scope


@pytest.mark.parametrize(
    ("path", "roots", "expected"),
    [
        (f"{P}/A/x.mkv", [P, S], P),
        (P, [P, S], P),
        ("/media/películasX/a.mkv", [P, S], None),
        ("/other/a.mkv", [P, S], None),
        ("/media/series/Show/x.mkv", ["/media", S], S),
        ("/media/MKV/x.mkv", ["/media", S], "/media"),
    ],
)
def test_root_of_longest_match(path, roots, expected):
    assert root_of(path, roots) == expected


@pytest.mark.parametrize(
    ("root", "path", "is_dir", "expected"),
    [
        (P, P, True, None),
        (P, f"{P}/A", True, ScanScope(P, "A", True)),
        (S, f"{S}/The Office S01E01.mkv", False, ScanScope(S, "", False)),
        (S, f"{S}/The Office S01E01.es.srt", False, ScanScope(S, "", False)),
        (P, f"{P}/A/Dune.mkv", False, ScanScope(P, "A", True)),
        (P, f"{P}/A/Old", True, ScanScope(P, "A", True)),
        (S, f"{S}/Breaking Bad/T5/e.mkv", False, ScanScope(S, "Breaking Bad", True)),
        (P, f"{P}/.Film.mkv.Xy12", False, None),
        (P, f"{P}/A/.hidden/x.mkv", False, None),
        (P, f"{P}/{BAD}", False, None),
        (P, f"{P}/A/{BAD}", False, None),
        (P, "/elsewhere/x.mkv", False, None),
        ("/media", "/media/series/Show/x.mkv", False, ScanScope("/media", "series", True)),
    ],
)
def test_scope_for(root, path, is_dir, expected):
    assert scope_for(root, path, is_dir) == expected


def test_merge_dedupes_and_sorts():
    scopes = [ScanScope(S, "B", True), ScanScope(P, "A", True), ScanScope(S, "B", True)]
    assert merge_scopes(scopes) == [ScanScope(P, "A", True), ScanScope(S, "B", True)]


def test_merge_whole_root_absorbs_its_root_only():
    scopes = [
        ScanScope(S, "B", True),
        ScanScope(S, "", False),
        ScanScope(S, "", True),
        ScanScope(P, "A", True),
    ]
    assert merge_scopes(scopes) == [ScanScope(P, "A", True), ScanScope(S, "", True)]


def test_merge_widens_the_root_with_most_scopes_until_under_cap():
    scopes = [ScanScope(P, f"D{i:02d}", True) for i in range(40)]
    scopes += [ScanScope(S, f"S{i:02d}", True) for i in range(11)]
    assert len(scopes) == MAX_PENDING_SCOPES + 1
    merged = merge_scopes(scopes)
    assert merged == [ScanScope(P, "", True), *[ScanScope(S, f"S{i:02d}", True) for i in range(11)]]


def test_merge_widens_several_roots_when_needed():
    scopes = [ScanScope(P, f"D{i}", True) for i in range(3)]
    scopes += [ScanScope(S, f"S{i}", True) for i in range(3)]
    scopes += [ScanScope(M, "", False)]
    assert merge_scopes(scopes, cap=2) == [
        ScanScope(M, "", False),
        ScanScope(P, "", True),
        ScanScope(S, "", True),
    ]


def test_merge_stops_when_every_root_is_whole():
    # More distinct roots than the cap: nothing more can be widened (stop rule).
    scopes = [ScanScope(f"/r{i}", "", True) for i in range(4)]
    assert merge_scopes(scopes, cap=2) == sorted(scopes)
