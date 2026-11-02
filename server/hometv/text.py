"""Title normalization helpers (pure).

- ``normalize_title``: NFKD, strip diacritics, casefold, ``&`` -> ``and``, remove punctuation,
  collapse spaces (TMDB matching).
- ``sort_title``: leading article removed, then ``normalize_title``.
- ``search_text``: ``normalize_title(title) + " " + normalize_title(original_title)``.
- ``str_or_none`` / ``is_blank``: TMDB string fields (blank or non-string -> missing).
- ``empty_to_none``: config values where ``""`` (e.g. an empty env var) means unset.
- ``undecodable``: a file name that is not valid UTF-8 (``os.fsdecode`` surrogate escapes); the
  scanner, the folder browser and change detection skip such names.
"""

import re
import unicodedata
from typing import Any

_ARTICLE_RE = re.compile(r"^\s*(?:(?:the|a|an|el|la|los|las|un|una|le|les)\s+|l['’]\s*)", re.I)
_SPACES_RE = re.compile(r"\s+")


def normalize_title(value: str) -> str:
    decomposed = unicodedata.normalize("NFKD", value)
    stripped = "".join(ch for ch in decomposed if not unicodedata.combining(ch))
    folded = stripped.casefold().replace("&", " and ")
    # Punctuation (P*) and symbols (S*) are removed, not replaced by a space:
    # "Spider-Man" -> "spiderman", "L'Âge" -> "lage".
    cleaned = "".join(ch for ch in folded if unicodedata.category(ch)[0] not in ("P", "S"))
    return _SPACES_RE.sub(" ", cleaned).strip()


def sort_title(title: str) -> str:
    match = _ARTICLE_RE.match(title)
    if match and title[match.end() :].strip():
        title = title[match.end() :]
    return normalize_title(title)


def search_text(title: str, original_title: str | None) -> str:
    parts = [normalize_title(title), normalize_title(original_title or "")]
    return " ".join(p for p in parts if p)


def str_or_none(value: Any) -> str | None:
    """``value`` when it is a string with non-blank content, else ``None``."""
    return value if isinstance(value, str) and value.strip() else None


def is_blank(value: Any) -> bool:
    """``None`` or a blank string (a TMDB field that needs the fallback language)."""
    return value is None or (isinstance(value, str) and not value.strip())


def empty_to_none(value: Any) -> Any:
    return None if value == "" else value


def undecodable(name: str) -> bool:
    """True when ``name`` holds surrogate escapes (bytes that are not valid UTF-8)."""
    try:
        name.encode("utf-8")
    except UnicodeEncodeError:
        return True
    return False
