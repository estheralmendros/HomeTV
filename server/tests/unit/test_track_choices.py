"""Track choice / subtitle style schemas and the pure column mappings (both directions)."""

import importlib.util
from pathlib import Path
from typing import Any, get_args

import pytest
from pydantic import ValidationError
from sqlalchemy import CheckConstraint

from hometv import subtitle_style
from hometv.api.schemas import (
    AudioChoice,
    SubtitleChoice,
    SubtitleStyle,
    SubtitleStyleRequest,
    TrackChoice,
)
from hometv.catalog import choice_out
from hometv.db import models as m
from hometv.track_choices import choice_columns

_SPA_TRACK = {
    "mode": "track",
    "language": "spa",
    "title": None,
    "forced": False,
    "hearing_impaired": False,
    "source": "embedded",
    "kind": "text",
}
_OFF = {
    "mode": "off",
    "language": None,
    "title": None,
    "forced": False,
    "hearing_impaired": False,
    "source": None,
    "kind": None,
}


@pytest.mark.parametrize(
    "body",
    [
        pytest.param({}, id="empty"),
        pytest.param({"audio": None, "subtitles": None}, id="both-null"),
        pytest.param({"subtitles": {"mode": "off", "language": "spa"}}, id="off-language"),
        pytest.param({"subtitles": {"mode": "off", "title": "x"}}, id="off-title"),
        pytest.param({"subtitles": {"mode": "off", "kind": "text"}}, id="off-kind"),
        pytest.param({"subtitles": {"mode": "off", "source": "external"}}, id="off-source"),
        pytest.param({"subtitles": {"mode": "off", "forced": True}}, id="off-forced"),
        pytest.param({"subtitles": {"mode": "off", "hearing_impaired": True}}, id="off-sdh"),
        pytest.param(
            {"subtitles": {"mode": "track", "source": "embedded", "kind": "text"}}, id="no-language"
        ),
        pytest.param(
            {"subtitles": {"mode": "track", "language": "spa", "kind": "text"}}, id="no-source"
        ),
        pytest.param(
            {"subtitles": {"mode": "track", "language": "spa", "source": "embedded"}}, id="no-kind"
        ),
        pytest.param({"subtitles": {**_SPA_TRACK, "source": "web"}}, id="unknown-source"),
        pytest.param({"subtitles": {**_SPA_TRACK, "kind": "bitmap"}}, id="unknown-kind"),
        pytest.param({"subtitles": {**_SPA_TRACK, "mode": "on"}}, id="unknown-mode"),
        pytest.param({"subtitles": {**_SPA_TRACK, "forced": "yes"}}, id="forced-string"),
        pytest.param({"subtitles": {**_SPA_TRACK, "forced": 1}}, id="forced-int"),
        pytest.param({"subtitles": {**_SPA_TRACK, "language": "  "}}, id="blank-language"),
        pytest.param({"subtitles": {**_SPA_TRACK, "extra": 1}}, id="subtitle-extra-key"),
        pytest.param({"audio": {"language": "eng", "channels": 0}}, id="channels-0"),
        pytest.param({"audio": {"language": "eng", "channels": 33}}, id="channels-33"),
        pytest.param({"audio": {"language": "eng", "channels": True}}, id="channels-bool"),
        pytest.param({"audio": {"language": "eng", "channels": "6"}}, id="channels-string"),
        pytest.param({"audio": {"language": "eng", "channels": 6.0}}, id="channels-float"),
        pytest.param({"audio": {"language": ""}}, id="audio-empty-language"),
        pytest.param({"audio": {"language": "a" * 17}}, id="language-17"),
        pytest.param({"audio": {"language": "eng", "title": "t" * 1001}}, id="title-1001"),
        pytest.param({"audio": {"language": "eng", "extra": 1}}, id="audio-extra-key"),
        pytest.param({"audio": {"language": "eng"}, "extra": 1}, id="extra-key"),
    ],
)
def test_track_choice_rejects(body):
    with pytest.raises(ValidationError):
        TrackChoice.model_validate(body)


def test_track_choice_accepts_and_normalizes():
    choice = TrackChoice.model_validate(
        {
            "audio": {"language": " ENG ", "title": "t" * 1000, "channels": 32},
            "subtitles": {
                "mode": "track",
                "language": "Pt-BR",
                "source": "external",
                "kind": "image",
            },
        }
    )
    assert choice.audio == AudioChoice(language="eng", title="t" * 1000, channels=32)
    assert choice.subtitles is not None
    assert choice.subtitles.language == "pt-br"
    assert choice.subtitles.forced is False and choice.subtitles.title is None
    off = TrackChoice.model_validate({"subtitles": {"mode": "off"}})
    assert off.audio is None
    assert off.model_dump()["subtitles"] == _OFF  # responses carry every key


def _choices() -> list[Any]:
    return [
        pytest.param(TrackChoice(audio=AudioChoice(language="eng", channels=6)), id="audio-only"),
        pytest.param(
            TrackChoice(audio=AudioChoice(language="und", title="Comentarios")), id="audio-titled"
        ),
        pytest.param(TrackChoice(subtitles=SubtitleChoice(mode="off")), id="subtitles-off-only"),
        pytest.param(
            TrackChoice(
                audio=AudioChoice(language="eng", title=None, channels=2),
                subtitles=SubtitleChoice(
                    mode="track",
                    language="spa",
                    title="Forzados",
                    forced=True,
                    hearing_impaired=True,
                    source="external",
                    kind="image",
                ),
            ),
            id="both-parts",
        ),
    ]


@pytest.mark.parametrize("choice", _choices())
def test_choice_columns_round_trip_through_choice_out(choice):
    columns = choice_columns(choice)
    assert tuple(columns) == m.TRACK_CHOICE_PART_COLUMNS
    row = m.TrackChoice(profile_id=1, film_id=1, **columns)
    assert choice_out(row) == choice


def test_choice_columns_null_parts_write_nulls_and_false_flags():
    audio_only = choice_columns(TrackChoice(audio=AudioChoice(language="eng")))
    assert audio_only == {
        "audio_language": "eng",
        "audio_title": None,
        "audio_channels": None,
        "subtitle_mode": None,
        "subtitle_language": None,
        "subtitle_title": None,
        "subtitle_forced": False,  # NOT NULL columns
        "subtitle_hearing_impaired": False,
        "subtitle_source": None,
        "subtitle_kind": None,
    }
    off = choice_columns(TrackChoice(subtitles=SubtitleChoice(mode="off")))
    assert off["subtitle_mode"] == "off" and off["audio_language"] is None
    assert off["subtitle_forced"] is False and off["subtitle_language"] is None


# --- Subtitle style ---------------------------------------------------------------------------

_STYLE = {
    "size": "large",
    "color": "yellow",
    "edge": "shadow",
    "edge_color": "dark_gray",
    "position": "high",
}


def test_subtitle_style_defaults_are_todays_look():
    assert SubtitleStyle().model_dump() == {
        "size": "medium",
        "color": "white",
        "edge": "outline",
        "edge_color": "black",
        "position": "low",
    }


@pytest.mark.parametrize("model", [SubtitleStyle, SubtitleStyleRequest])
@pytest.mark.parametrize("field", list(_STYLE))
def test_subtitle_style_rejects_unknown_values(model, field):
    with pytest.raises(ValidationError):
        model.model_validate({**_STYLE, field: "purple"})


@pytest.mark.parametrize("model", [SubtitleStyle, SubtitleStyleRequest])
def test_subtitle_style_rejects_extra_keys(model):
    with pytest.raises(ValidationError):
        model.model_validate({**_STYLE, "font": "serif"})


@pytest.mark.parametrize("field", list(_STYLE))
def test_subtitle_style_request_requires_every_key(field):
    body = {k: v for k, v in _STYLE.items() if k != field}
    with pytest.raises(ValidationError):
        SubtitleStyleRequest.model_validate(body)


def test_subtitle_style_request_accepts_a_full_body_and_the_clash():
    assert SubtitleStyleRequest.model_validate(_STYLE).model_dump() == _STYLE
    clash = {**_STYLE, "color": "white", "edge": "outline", "edge_color": "white"}
    assert SubtitleStyleRequest.model_validate(clash).model_dump() == clash


def _migration_module() -> Any:
    path = (
        Path(m.__file__).parent / "migrations" / "versions" / "0007_track_choice_subtitle_style.py"
    )
    spec = importlib.util.spec_from_file_location("migration_0007", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_migration_style_columns_equal_the_subtitle_style_module():
    aliases = {
        "subtitle_size": subtitle_style.SubtitleSizeValue,
        "subtitle_color": subtitle_style.SubtitleColorValue,
        "subtitle_edge": subtitle_style.SubtitleEdgeValue,
        "subtitle_edge_color": subtitle_style.SubtitleEdgeColorValue,
        "subtitle_position": subtitle_style.SubtitlePositionValue,
    }
    expected = tuple(
        (f"subtitle_{field}", getattr(subtitle_style.DEFAULT_STYLE, field), get_args(alias))
        for field, alias in zip(subtitle_style.STYLE_FIELDS, aliases.values(), strict=True)
    )
    assert tuple(aliases) == tuple(f"subtitle_{f}" for f in subtitle_style.STYLE_FIELDS)
    assert expected == _migration_module()._STYLE_COLUMNS
    assert tuple(SubtitleStyle.model_fields) == subtitle_style.STYLE_FIELDS


@pytest.mark.parametrize(
    ("column", "alias"),
    [
        ("subtitle_size", subtitle_style.SubtitleSizeValue),
        ("subtitle_color", subtitle_style.SubtitleColorValue),
        ("subtitle_edge", subtitle_style.SubtitleEdgeValue),
        ("subtitle_edge_color", subtitle_style.SubtitleEdgeColorValue),
        ("subtitle_position", subtitle_style.SubtitlePositionValue),
    ],
)
def test_profile_style_checks_are_built_from_the_aliases(column, alias):
    checks = {
        str(c.name).removeprefix("ck_profile_"): str(c.sqltext)
        for c in m.Profile.__table__.constraints
        if isinstance(c, CheckConstraint)
    }
    values = ",".join(f"'{value}'" for value in get_args(alias))
    assert checks[f"{column}_values"] == f"{column} IN ({values})"
