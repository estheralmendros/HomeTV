"""Filename parser. Pure: never touches the filesystem.

Parsing decisions:
- guessit strips ``Vol N`` into ``volume`` the same way it strips ``Part N``; both are
  re-appended to the title (``"{title} Vol {n}"`` / ``"{title} Part {n}"``) so table row 10
  (``Kill Bill Vol 1``) holds.
- For episodes a missing episode number is reported before a missing season, so date-based
  names without a season folder (table row 29) are ``no_episode_number``; files that have an
  episode number but no season (row 30) are ``no_season``.
"""

import re
from dataclasses import dataclass
from datetime import date
from pathlib import PurePosixPath
from typing import Any, Literal

from babelfish import Error as BabelfishError
from babelfish import Language
from guessit import guessit

LibraryType = Literal["films", "shows"]
IgnoreReason = Literal["extra", "trailer", "sample", "non_video", "hidden"]
UnparseableReason = Literal["no_title", "no_season", "no_episode_number"]
SidecarFormat = Literal["srt", "ass", "ssa", "vtt", "idx", "sup"]


@dataclass(frozen=True)
class ParsedFilm:
    title: str
    year: int | None


@dataclass(frozen=True)
class MultiPart:
    title: str
    year: int | None
    part: int


@dataclass(frozen=True)
class ParsedEpisode:
    show_title: str
    show_year: int | None
    season: int
    episodes: tuple[int, ...]


@dataclass(frozen=True)
class Ignored:
    reason: IgnoreReason


@dataclass(frozen=True)
class Unparseable:
    reason: UnparseableReason
    detail: str


@dataclass(frozen=True)
class SidecarInfo:
    language: str
    forced: bool
    hearing_impaired: bool
    format: SidecarFormat
    kind: Literal["text", "image"]


ParseResult = ParsedFilm | MultiPart | ParsedEpisode | Ignored | Unparseable

VIDEO_EXTENSIONS = frozenset(
    {".mkv", ".mp4", ".m4v", ".avi", ".mov", ".ts", ".m2ts", ".webm", ".wmv", ".mpg", ".mpeg"}
)
SIDECAR_EXTENSIONS = frozenset({".srt", ".ass", ".ssa", ".vtt", ".idx", ".sup"})
_EXTRA_DIRS = frozenset({"extras", "featurettes", "behind the scenes", "deleted scenes"})

_MULTIPART_RE = re.compile(
    r"(?i)(^|[ ._\-\[(])(?P<tok>cd|dis[ck]|part|pt)[ ._\-]?(?P<n>\d{1,2})($|[ ._\-\])])"
)
_EXPLICIT_SEASON_RE = re.compile(
    r"(?i)(\bs\d{1,3}[ ._\-]?e\d{1,4}|\b\d{1,2}x\d{2,4}\b|\bseason[ ._\-]?\d{1,3}\b"
    r"|\btemporada[ ._\-]?\d{1,3}\b)"
)
_SEASON_DIR_RE = re.compile(r"(?i)^(?:season|series|temporada)[ ._\-]?(\d{1,3})$")
_SHORT_SEASON_DIR_RE = re.compile(r"(?i)^s(\d{1,3})$")
_EPISODE_RANGE_RE = re.compile(r"(?i)\bs\d{1,3}[ ._\-]?e(\d{1,4})-e?(\d{1,4})\b")
_SHOW_FOLDER_YEAR_RE = re.compile(r"^(?P<title>.*?)\s*\((?P<year>\d{4})\)$")
_SPACES_RE = re.compile(r"\s+")


def parse(
    relative_path: PurePosixPath,
    library_type: LibraryType,
    sibling_stems: frozenset[str] = frozenset(),
) -> ParseResult:
    parts = relative_path.parts
    if any(p.startswith(".") for p in parts):
        return Ignored("hidden")
    if relative_path.suffix.lower() not in VIDEO_EXTENSIONS:
        return Ignored("non_video")

    dirs = [d.lower() for d in parts[:-1]]
    if any(d in _EXTRA_DIRS for d in dirs):
        return Ignored("extra")
    if "trailers" in dirs:
        return Ignored("trailer")

    stem = relative_path.stem
    lower_stem = stem.lower()
    if lower_stem.endswith("-trailer"):
        return Ignored("trailer")
    if (
        lower_stem == "sample"
        or lower_stem.endswith(("-sample", ".sample", " sample"))
        or "sample" in dirs
    ):
        return Ignored("sample")

    if library_type == "films":
        return _parse_film(relative_path, sibling_stems)
    return _parse_episode(relative_path)


# --- Films ---------------------------------------------------------------------------------


def _parse_film(path: PurePosixPath, sibling_stems: frozenset[str]) -> ParseResult:
    stem = path.stem
    matches = list(_MULTIPART_RE.finditer(stem))
    if matches:
        m = matches[-1]
        tok = m.group("tok").lower()
        n = int(m.group("n"))
        if tok in ("cd", "disc", "disk") or _has_part_sibling(stem, m, sibling_stems):
            stripped = (stem[: m.start()] + " " + stem[m.end() :]).strip()
            result = _film_title_year(PurePosixPath(*path.parts[:-1], stripped + path.suffix))
            if isinstance(result, Unparseable):
                return result
            return MultiPart(result.title, result.year, n)
    return _film_title_year(path)


def _has_part_sibling(stem: str, m: re.Match[str], sibling_stems: frozenset[str]) -> bool:
    if m.group("tok").lower() not in ("part", "pt"):
        return False
    prefix = stem[: m.start("n")].lower()
    suffix = stem[m.end("n") :].lower()
    n = int(m.group("n"))
    for sibling in sibling_stems:
        s = sibling.lower()
        if not (s.startswith(prefix) and s.endswith(suffix)):
            continue
        middle = s[len(prefix) : len(s) - len(suffix)]
        if re.fullmatch(r"\d{1,2}", middle) and int(middle) != n:
            return True
    return False


def _film_title_year(path: PurePosixPath) -> ParsedFilm | Unparseable:
    g = guessit(path.name, {"type": "movie"})
    title, year = _title(g), _valid_year(g.get("year"))
    if title and year is not None:
        return ParsedFilm(title, year)
    if len(path.parts) >= 2:
        f = guessit(path.parts[-2], {"type": "movie"})
        folder_title, folder_year = _title(f), _valid_year(f.get("year"))
        if folder_title and folder_year is not None:
            return ParsedFilm(folder_title, folder_year)
    if not title:
        return Unparseable("no_title", f"no title in {path.name!r}")
    return ParsedFilm(title, None)


def _title(g: dict[str, Any]) -> str | None:
    title = _first(g.get("title"))
    if not isinstance(title, str):
        return None
    title = _SPACES_RE.sub(" ", title).strip()
    if not title:
        return None
    volume = _first(g.get("volume"))
    if isinstance(volume, int):
        title = f"{title} Vol {volume}"
    part = _first(g.get("part"))
    if isinstance(part, int):
        title = f"{title} Part {part}"
    return title


def _valid_year(value: Any) -> int | None:
    year = _first(value)
    if isinstance(year, int) and 1900 <= year <= date.today().year + 1:
        return year
    return None


def _first(value: Any) -> Any:
    if isinstance(value, list):
        return value[0] if value else None
    return value


# --- Episodes ------------------------------------------------------------------------------


def _parse_episode(path: PurePosixPath) -> ParseResult:
    name = path.name
    explicit_season = bool(_EXPLICIT_SEASON_RE.search(name))
    options: dict[str, Any] = {"type": "episode"}
    if not explicit_season:
        options["episode_prefer_number"] = True
    g = guessit(name, options)

    # Show title / year
    if len(path.parts) >= 2:
        folder = path.parts[0].strip()
        fm = _SHOW_FOLDER_YEAR_RE.match(folder)
        if fm:
            show_title, show_year = fm.group("title").strip(), int(fm.group("year"))
        else:
            show_title, show_year = folder, None
        show_title = _SPACES_RE.sub(" ", show_title)
    else:
        raw = _first(g.get("title"))
        show_title = _SPACES_RE.sub(" ", raw).strip() if isinstance(raw, str) else ""
        show_year = _valid_year(g.get("year"))

    # Episodes (reported before the season, see module docstring)
    episodes = _episodes(g, name)
    if not episodes:
        return Unparseable("no_episode_number", f"no episode number in {name!r}")

    # Season
    season: int | None = None
    if explicit_season:
        value = _first(g.get("season"))
        season = value if isinstance(value, int) else None
    else:
        season = _season_from_dirs(path.parts[:-1])
    if season is None:
        return Unparseable("no_season", f"no season marker or season folder for {name!r}")

    if not show_title:
        return Unparseable("no_title", f"no show title in {str(path)!r}")
    return ParsedEpisode(show_title, show_year, season, episodes)


def _episodes(g: dict[str, Any], name: str) -> tuple[int, ...]:
    raw = g.get("episode")
    values = raw if isinstance(raw, list) else [raw]
    numbers = {v for v in values if isinstance(v, int)}
    if numbers:
        for rm in _EPISODE_RANGE_RE.finditer(name):
            lo, hi = sorted((int(rm.group(1)), int(rm.group(2))))
            numbers.update(range(lo, hi + 1))
    return tuple(sorted(numbers))


def _season_from_dirs(dirs: tuple[str, ...]) -> int | None:
    for d in reversed(dirs):
        d = d.strip()
        m = _SEASON_DIR_RE.match(d) or _SHORT_SEASON_DIR_RE.match(d)
        if m:
            return int(m.group(1))
        if d.lower() == "specials":
            return 0
    return None


# --- Sidecars ------------------------------------------------------------------------------


def parse_sidecar(
    video_relative_path: PurePosixPath, sidecar_relative_path: PurePosixPath
) -> SidecarInfo | None:
    if video_relative_path.parent != sidecar_relative_path.parent:
        return None
    ext = sidecar_relative_path.suffix.lower()
    if ext not in SIDECAR_EXTENSIONS:
        return None  # includes `.sub`: only ever an `.idx` companion
    name = sidecar_relative_path.name
    prefix = video_relative_path.stem + "."
    if not name.startswith(prefix):
        return None
    middle = name[len(prefix) : len(name) - len(ext)]
    tags = [t for t in middle.split(".") if t] if middle else []

    language: str | None = None
    forced = hearing_impaired = False
    for tag in tags:
        low = tag.lower()
        if low == "forced":
            forced = True
        elif low in ("sdh", "cc", "hi"):
            hearing_impaired = True
        elif low == "default":
            continue
        elif language is None:
            language = _language_code(tag)

    fmt: SidecarFormat = ext[1:]  # type: ignore[assignment]
    kind: Literal["text", "image"] = "image" if fmt in ("idx", "sup") else "text"
    return SidecarInfo(language or "und", forced, hearing_impaired, fmt, kind)


def _language_code(tag: str) -> str | None:
    """Resolve a sidecar tag as ISO 639-1/639-2, IETF or English name -> ISO 639-2/B."""
    attempts = (
        lambda t: Language.fromietf(t),
        lambda t: Language.fromalpha3b(t.lower()),
        lambda t: Language.fromalpha3t(t.lower()),
        lambda t: Language.fromname(t.capitalize()),
    )
    for attempt in attempts:
        try:
            lang = attempt(tag)
        except (BabelfishError, ValueError, KeyError):
            continue
        try:
            return lang.alpha3b
        except BabelfishError:
            return lang.alpha3
    return None
