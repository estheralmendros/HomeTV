"""Per-profile subtitle style: the allowed values and defaults (single source).

Pure module. ``db/models.py`` builds the ``profile.subtitle_*`` CHECKs from these aliases and
``api/schemas.py`` types ``SubtitleStyle`` with them; only migration ``0007`` spells the values
as literals (it never imports app code; a unit test keeps both in step). How each renderer draws
a value lives in the clients.
"""

from dataclasses import dataclass
from typing import Final, Literal

SubtitleSizeValue = Literal["small", "medium", "large"]
SubtitleColorValue = Literal["white", "yellow", "green", "cyan", "magenta", "orange"]
SubtitleEdgeValue = Literal["outline", "shadow", "none"]
SubtitleEdgeColorValue = Literal["black", "dark_gray", "white"]
SubtitlePositionValue = Literal["low", "middle", "high"]


@dataclass(frozen=True, slots=True)
class StyleDefaults:
    size: SubtitleSizeValue = "medium"
    color: SubtitleColorValue = "white"
    edge: SubtitleEdgeValue = "outline"
    edge_color: SubtitleEdgeColorValue = "black"
    position: SubtitlePositionValue = "low"


DEFAULT_STYLE: Final = StyleDefaults()
"""Today's look: every existing profile gets it."""

STYLE_FIELDS: Final = ("size", "color", "edge", "edge_color", "position")
"""``SubtitleStyle`` field names; the ``profile`` column of each is ``subtitle_<field>``."""
