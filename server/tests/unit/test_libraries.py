"""Pure library-folder rules (hometv.libraries): normalize, seed choice, validation, removals,
browse listing. Filesystem access goes through a fake ``FolderFs``."""

import errno
import os
import posixpath
import stat

import pytest

from hometv.api.errors import ApiError
from hometv.libraries import (
    FolderError,
    LibraryFolderSet,
    browse_listing,
    choose_seed,
    normalize_folder,
    same_folders,
    split_removals,
    validate_change,
)


class FakeFs:
    """In-memory tree: ``dirs``/``files`` (absolute paths), ``unreadable`` dirs, symlinks
    ``links`` (link path -> target path; a link to a dir lists as a dir)."""

    def __init__(self, dirs=(), files=(), unreadable=(), links=None, stat_errors=None):
        self.dirs = set(dirs)
        self.files = set(files)
        self.unreadable = set(unreadable)
        self.links = dict(links or {})
        self.stat_errors = dict(stat_errors or {})
        for path in [*self.dirs, *self.files, *self.links]:
            parent = posixpath.dirname(path)
            while parent not in self.dirs:
                self.dirs.add(parent)
                parent = posixpath.dirname(parent)

    def realpath(self, path: str) -> str:
        for link, target in self.links.items():
            if path == link or path.startswith(link + "/"):
                return self.realpath(target + path[len(link) :])
        return path

    def stat(self, path: str) -> os.stat_result:
        if path in self.stat_errors:
            raise OSError(self.stat_errors[path], os.strerror(self.stat_errors[path]), path)
        real = self.realpath(path)
        if real in self.dirs:
            return os.stat_result((stat.S_IFDIR | 0o755, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        if real in self.files:
            return os.stat_result((stat.S_IFREG | 0o644, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        parent = posixpath.dirname(real)
        if parent in self.files:
            raise NotADirectoryError(errno.ENOTDIR, "Not a directory", path)
        raise FileNotFoundError(errno.ENOENT, "No such file or directory", path)

    def scandir(self, path: str) -> "FakeScandir":
        real = self.realpath(path)
        if real in self.unreadable:
            raise PermissionError(errno.EACCES, "Permission denied", path)
        if real not in self.dirs:
            raise FileNotFoundError(errno.ENOENT, "No such file or directory", path)
        children = [
            p for p in [*self.dirs, *self.files] if p != "/" and posixpath.dirname(p) == real
        ]
        children += [link for link in self.links if posixpath.dirname(link) == real]
        return FakeScandir(
            [
                FakeEntry(posixpath.basename(c), posixpath.join(path, posixpath.basename(c)), self)
                for c in children
            ]
        )

    @property
    def folder_fs(self):
        from hometv.libraries import FolderFs

        return FolderFs(stat=self.stat, scandir=self.scandir, realpath=self.realpath)


class FakeEntry:
    def __init__(self, name: str, path: str, fs: FakeFs) -> None:
        self.name = name
        self.path = path
        self._fs = fs

    def is_dir(self, follow_symlinks: bool = True) -> bool:
        return self._fs.realpath(self.path) in self._fs.dirs

    def is_symlink(self) -> bool:
        return self.path in self._fs.links


class FakeScandir:
    def __init__(self, entries) -> None:
        self.entries = entries

    def __enter__(self):
        return iter(self.entries)

    def __exit__(self, *exc) -> None:
        return None


# --- normalize_folder / same_folders ------------------------------------------------------------


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("/media/Films", "/media/Films"),
        ("  /media/Films/  ", "/media/Films"),
        ("/media//Films/./x/..", "/media/Films"),
        ("media/x", None),
        ("~/films", None),
        ("./films", None),
        ("", None),
    ],
)
def test_normalize_folder(raw, expected):
    assert normalize_folder(raw) == expected


def test_same_folders_ignores_order_and_normalization():
    current = LibraryFolderSet(films=("/m/a", "/m/b"), shows=("/m/tv",))
    assert same_folders(current, ["/m/b/", "/m/a"], ["/m/tv"])
    assert not same_folders(current, ["/m/a"], ["/m/tv"])
    assert not same_folders(current, ["/m/a", "/m/b"], ["/m/tv", "/m/x"])
    assert not same_folders(current, ["/m/a", "/m/b", "rel"], ["/m/tv"])
    assert not same_folders(current, ["/m/a", "/m/tv"], ["/m/b"])


def test_folder_set_roots_films_first_in_list_order():
    folders = LibraryFolderSet(films=("/b", "/a"), shows=("/tv",))
    assert folders.roots() == [("/b", "films"), ("/a", "films"), ("/tv", "shows")]


# --- choose_seed ----------------------------------------------------------------------------------


def test_choose_seed_prefers_configured_folders():
    plan = choose_seed(
        LibraryFolderSet(films=("/m/a", "/m/b")), [("films", "/old"), ("shows", "/tv")]
    )
    assert plan.source == "config"
    assert plan.folders == LibraryFolderSet(films=("/m/a", "/m/b"), shows=())
    assert plan.dropped == ()


def test_choose_seed_falls_back_to_media_file_roots_films_first():
    plan = choose_seed(LibraryFolderSet(), [("shows", "/media/TV"), ("films", "/media/Films")])
    assert plan.source == "media_files"
    assert plan.folders == LibraryFolderSet(films=("/media/Films",), shows=("/media/TV",))


def test_choose_seed_empty():
    plan = choose_seed(LibraryFolderSet(), [])
    assert (plan.source, plan.folders, plan.dropped) == ("empty", LibraryFolderSet(), ())


@pytest.mark.parametrize(
    ("configured", "existing", "films", "shows", "dropped"),
    [
        # duplicates in one list (after normalization)
        (LibraryFolderSet(films=("/m/a", "/m/a/")), [], ("/m/a",), (), (("films", "/m/a"),)),
        # across lists: the films entry wins
        (
            LibraryFolderSet(films=("/m/a",), shows=("/m/a", "/m/tv")),
            [],
            ("/m/a",),
            ("/m/tv",),
            (("shows", "/m/a"),),
        ),
        # media_files source
        (
            LibraryFolderSet(),
            [("films", "/m/x"), ("shows", "/m/x"), ("shows", "/m/tv")],
            ("/m/x",),
            ("/m/tv",),
            (("shows", "/m/x"),),
        ),
    ],
)
def test_choose_seed_dedupes_first_wins(configured, existing, films, shows, dropped):
    plan = choose_seed(configured, existing)
    assert plan.folders == LibraryFolderSet(films=films, shows=shows)
    assert plan.dropped == dropped


# --- validate_change ------------------------------------------------------------------------------

DATA = "/srv/data"
HLS = "/srv/hls"


def _validate(fs: FakeFs, films, shows, current=None, data=DATA, hls=HLS):
    current = current or LibraryFolderSet()
    return validate_change(current, films, shows, data_dir=data, hls_dir=hls, fs=fs.folder_fs)


def _codes(errors: list[FolderError]) -> list[tuple[str, str, str, str | None]]:
    return [(e.library, e.path, e.code, e.other) for e in errors]


def test_valid_change_has_no_errors():
    fs = FakeFs(dirs={"/media/F", "/media/T", DATA, HLS})
    assert _validate(fs, ["/media/F"], ["/media/T"]) == []


@pytest.mark.parametrize(
    ("films", "fs", "expected"),
    [
        (["media/x"], FakeFs(), ("films", "media/x", "not_absolute", None)),
        (["~/x"], FakeFs(), ("films", "~/x", "not_absolute", None)),
        (["/media/nope"], FakeFs(dirs={"/media"}), ("films", "/media/nope", "not_found", None)),
        (
            ["/media/file.mkv/sub"],
            FakeFs(files={"/media/file.mkv"}),
            ("films", "/media/file.mkv/sub", "not_found", None),
        ),
        (
            ["/media/file.mkv"],
            FakeFs(files={"/media/file.mkv"}),
            ("films", "/media/file.mkv", "not_directory", None),
        ),
        (
            ["/media/locked"],
            FakeFs(dirs={"/media/locked"}, unreadable={"/media/locked"}),
            ("films", "/media/locked", "not_readable", None),
        ),
        (
            ["/media/x/"],
            FakeFs(stat_errors={"/media/x": errno.EACCES}),
            ("films", "/media/x", "not_readable", None),
        ),
        (["/srv"], FakeFs(dirs={DATA, HLS}), ("films", "/srv", "contains_data_dir", None)),
        ([DATA], FakeFs(dirs={DATA, HLS}), ("films", DATA, "contains_data_dir", None)),
        (
            [f"{DATA}/media"],
            FakeFs(dirs={f"{DATA}/media", HLS}),
            ("films", f"{DATA}/media", "inside_data_dir", None),
        ),
        (
            ["/x"],
            FakeFs(dirs={"/x/hls", DATA}),
            ("films", "/x", "contains_hls_dir", None),
        ),
        (
            [f"{HLS}/m"],
            FakeFs(dirs={f"{HLS}/m", DATA}),
            ("films", f"{HLS}/m", "inside_hls_dir", None),
        ),
    ],
)
def test_validate_change_single_entry_codes(films, fs, expected):
    hls = "/x/hls" if expected[2] == "contains_hls_dir" else HLS
    assert _codes(_validate(fs, films, [], hls=hls)) == [expected]


def test_already_saved_missing_folder_is_not_checked():
    fs = FakeFs(dirs={"/media/New", DATA, HLS})
    current = LibraryFolderSet(films=("/media/Unplugged",))
    assert _validate(fs, ["/media/Unplugged", "/media/New"], [], current=current) == []
    # also when it moves to the other library: it is not "added" (already stored)
    assert _validate(fs, ["/media/New"], ["/media/Unplugged"], current=current) == []


def test_duplicate_reported_on_the_later_entry_also_across_libraries():
    fs = FakeFs(dirs={"/media/A", DATA, HLS})
    assert _codes(_validate(fs, ["/media/A", "/media/A/"], [])) == [
        ("films", "/media/A", "duplicate", "/media/A")
    ]
    assert _codes(_validate(fs, ["/media/A"], ["/media/A"])) == [
        ("shows", "/media/A", "duplicate", "/media/A")
    ]


def test_overlaps_folder_nested_either_way_and_symlink_alias():
    fs = FakeFs(dirs={"/media/A/B", DATA, HLS}, links={"/media/alias": "/media/A"})
    assert _codes(_validate(fs, ["/media/A"], ["/media/A/B"])) == [
        ("shows", "/media/A/B", "overlaps_folder", "/media/A")
    ]
    assert _codes(_validate(fs, ["/media/A/B", "/media/A"], [])) == [
        ("films", "/media/A", "overlaps_folder", "/media/A/B")
    ]
    assert _codes(_validate(fs, ["/media/A"], ["/media/alias"])) == [
        ("shows", "/media/alias", "overlaps_folder", "/media/A")
    ]


def test_symlink_alias_of_data_dir_is_caught():
    fs = FakeFs(dirs={DATA, HLS}, links={"/media/d": DATA})
    assert _codes(_validate(fs, ["/media/d"], [])) == [
        ("films", "/media/d", "contains_data_dir", None)
    ]


def test_only_the_first_failing_code_per_entry_and_every_entry_reported():
    fs = FakeFs(dirs={"/media/A", DATA, HLS})
    errors = _validate(fs, ["/media/A", "/media/missing", "rel"], ["/media/A"])
    assert _codes(errors) == [
        ("films", "/media/missing", "not_found", None),
        ("films", "rel", "not_absolute", None),
        ("shows", "/media/A", "duplicate", "/media/A"),
    ]


def test_series_folder_replaced_by_parent_or_subfolder_is_valid():
    fs = FakeFs(dirs={"/media/series/Naruto", DATA, HLS})
    current = LibraryFolderSet(shows=("/media",))
    assert _validate(fs, [], ["/media/series"], current=current) == []
    current = LibraryFolderSet(shows=("/media/series",))
    assert _validate(fs, [], ["/media/series/Naruto"], current=current) == []
    assert _validate(fs, [], ["/media"], current=current) == []


# --- split_removals -------------------------------------------------------------------------------


def test_split_removals_table():
    current = LibraryFolderSet(
        films=("/m/f/MKV", "/m/f/Old", "/m/Docs"), shows=("/m/tv/TV", "/m/Kids")
    )
    removed, covered = split_removals(
        current, films=["/m/f", "/m/Kids"], shows=["/m/tv", "/m/Docs2"]
    )
    assert covered == [("films", "/m/f/MKV", "/m/f"), ("films", "/m/f/Old", "/m/f")]
    # a Series folder inside an added Series folder is never covered; "/m/Kids" moved: neither
    assert removed == [("films", "/m/Docs"), ("shows", "/m/tv/TV")]


def test_split_removals_series_inside_added_series_is_removed_and_other_library_is_neither():
    current = LibraryFolderSet(films=("/m/F", "/m/X"), shows=("/m/series/Naruto", "/m/Y"))
    removed, covered = split_removals(current, films=["/m/Y"], shows=["/m/series", "/m/X"])
    assert covered == []
    assert removed == [("films", "/m/F"), ("shows", "/m/series/Naruto")]


def test_split_removals_inside_added_folder_of_other_library_only_is_removed():
    current = LibraryFolderSet(films=("/m/all/films",))
    removed, covered = split_removals(current, films=[], shows=["/m/all"])
    assert (removed, covered) == ([("films", "/m/all/films")], [])


def test_split_removals_covered_only_by_an_added_films_folder():
    current = LibraryFolderSet(films=("/m", "/m/MKV"))  # legacy nested, "/m" kept
    removed, covered = split_removals(current, films=["/m"], shows=[])
    assert (removed, covered) == ([("films", "/m/MKV")], [])


# --- browse_listing -------------------------------------------------------------------------------


def _browse_fs() -> FakeFs:
    return FakeFs(
        dirs={
            "/media/Disco2/Películas",
            "/media/Disco2/documentales",
            "/media/Disco2/Anime",
            "/media/Disco2/.hidden",
            "/outside/secret",
            "/media/locked",
        },
        files={"/media/Disco2/movie.mkv"},
        unreadable={"/media/locked"},
        links={
            "/media/Disco2/escape": "/outside",
            "/media/Disco2/inside": "/media/Disco2/Anime",
            "/media/Disco2/broken": "/nowhere",
        },
    )


def test_browse_default_is_the_base_with_no_parent():
    fs = _browse_fs()
    result = browse_listing("/media", None, fs=fs.folder_fs)
    assert result.path == "/media" and result.parent is None
    assert [e.name for e in result.entries] == ["Disco2", "locked"]


def test_browse_subfolder_lists_sorted_dirs_only_without_hidden_or_escapes():
    fs = _browse_fs()
    result = browse_listing("/media", "/media/Disco2/", fs=fs.folder_fs)
    assert result.path == "/media/Disco2"
    assert result.parent == "/media"
    assert [(e.name, e.path) for e in result.entries] == [
        ("Anime", "/media/Disco2/Anime"),
        ("documentales", "/media/Disco2/documentales"),
        ("inside", "/media/Disco2/inside"),
        ("Películas", "/media/Disco2/Películas"),
    ]


@pytest.mark.parametrize(
    ("path", "status", "code"),
    [
        ("/media/../outside", 403, "outside_browse_root"),
        ("/outside", 403, "outside_browse_root"),
        ("/media/Disco2/escape", 403, "outside_browse_root"),
        ("/media/nope", 404, "folder_not_found"),
        ("/media/Disco2/movie.mkv", 404, "folder_not_found"),
        ("/media/locked", 403, "folder_not_readable"),
        ("media/Disco2", 422, "validation_error"),
    ],
)
def test_browse_errors(path, status, code):
    with pytest.raises(ApiError) as exc:
        browse_listing("/media", path, fs=_browse_fs().folder_fs)
    assert (exc.value.status_code, exc.value.code) == (status, code)
