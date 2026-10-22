from datetime import UTC, datetime

import pytest
from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from sqlalchemy import inspect, select, text
from sqlalchemy.exc import IntegrityError

from hometv.db import models as m
from hometv.db.engine import HEAD_REVISION, Database, run_migrations

EXPECTED_TABLES = {
    "library",
    "media_file",
    "keyframe_index",
    "item_alias",
    "media_file_episode",
    "subtitle_file",
    "film",
    "show",
    "season",
    "episode",
    "person",
    "cast_credit",
    "genre",
    "film_genre",
    "show_genre",
    "profile",
    "playback_progress",
    "unmatched_entry",
    "scan_run",
}


@pytest.fixture
def db(tmp_path):
    database = Database(tmp_path / "hometv.db")
    run_migrations(database)
    yield database
    database.dispose()


def test_migration_creates_every_table(db):
    tables = set(inspect(db.read_engine).get_table_names())
    assert tables >= EXPECTED_TABLES
    assert "alembic_version" in tables


def test_migration_is_at_head_and_idempotent(db):
    run_migrations(db)
    with db.read_engine.connect() as conn:
        assert MigrationContext.configure(conn).get_current_revision() == HEAD_REVISION == "0001"


@pytest.mark.filterwarnings("ignore:.*expression-based index:UserWarning")
@pytest.mark.filterwarnings("ignore:.*expression-based index")
def test_orm_metadata_matches_migration(db):
    with db.read_engine.connect() as conn:
        ctx = MigrationContext.configure(conn, opts={"compare_type": True})
        assert compare_metadata(ctx, m.Base.metadata) == []


def test_downgrade_and_upgrade_round_trip(db):
    from alembic import command

    from hometv.db.engine import alembic_config

    cfg = alembic_config(db)
    command.downgrade(cfg, "base")
    assert set(inspect(db.read_engine).get_table_names()) <= {"alembic_version"}
    run_migrations(db)
    assert set(inspect(db.read_engine).get_table_names()) >= EXPECTED_TABLES


def test_all_data_model_columns_exist(db):
    insp = inspect(db.read_engine)
    cols = {t: {c["name"] for c in insp.get_columns(t)} for t in EXPECTED_TABLES}
    assert {
        "library_id",
        "root_path",
        "path",
        "relative_path",
        "size_bytes",
        "mtime_ns",
        "container",
        "duration_seconds",
        "video_codec",
        "width",
        "height",
        "hdr",
        "probe",
        "probe_error",
        "parsed",
        "manual_match",
        "film_id",
        "part_number",
        "missing_since",
        "added_at",
        "updated_at",
    } <= cols["media_file"]
    assert {
        "library_id",
        "match_status",
        "parsed_title",
        "parsed_title_norm",
        "parsed_year",
        "tmdb_id",
        "imdb_id",
        "title",
        "original_title",
        "sort_title",
        "search_text",
        "year",
        "release_date",
        "overview",
        "tagline",
        "runtime_minutes",
        "vote_average",
        "poster_path",
        "backdrop_path",
        "added_at",
        "metadata_updated_at",
    } <= cols["film"]
    show = cols["show"]
    assert {"first_air_date", "status", "content_updated_at", "added_at", "sort_title"} <= show
    assert not {"release_date", "tagline", "runtime_minutes", "imdb_id"} & show
    assert {
        "show_id",
        "season_number",
        "name",
        "overview",
        "poster_path",
        "air_date",
        "metadata_fetched_at",
    } <= cols["season"]
    assert {
        "season_id",
        "show_id",
        "season_number",
        "episode_number",
        "title",
        "overview",
        "still_path",
        "air_date",
        "runtime_minutes",
        "added_at",
    } <= cols["episode"]
    assert {"media_file_id", "source", "times", "created_at"} <= cols["keyframe_index"]
    assert {
        "library_id",
        "parsed_title_norm",
        "parsed_year",
        "film_id",
        "show_id",
        "created_at",
    } <= cols["item_alias"]
    assert {
        "media_file_id",
        "path",
        "language",
        "forced",
        "hearing_impaired",
        "format",
        "kind",
        "size_bytes",
        "mtime_ns",
    } <= cols["subtitle_file"]
    assert {"tmdb_id", "name", "profile_path"} <= cols["person"]
    assert {"person_id", "film_id", "show_id", "character", "order"} <= cols["cast_credit"]
    assert {"id", "name"} <= cols["genre"]
    assert {
        "name",
        "avatar_id",
        "pin_hash",
        "is_admin",
        "admin_token_version",
        "created_at",
    } <= cols["profile"]
    assert {
        "profile_id",
        "film_id",
        "episode_id",
        "media_file_id",
        "position_seconds",
        "duration_seconds",
        "watched",
        "watched_at",
        "updated_at",
    } <= cols["playback_progress"]
    assert {"media_file_id", "film_id", "show_id", "reason", "detail", "created_at"} <= cols[
        "unmatched_entry"
    ]
    assert {
        "trigger",
        "state",
        "phase",
        "started_at",
        "finished_at",
        "counts",
        "processed",
        "total",
        "errors",
        "error",
    } <= cols["scan_run"]


def test_wal_mode_after_migration(db):
    with db.read_engine.connect() as conn:
        assert conn.exec_driver_sql("PRAGMA journal_mode").scalar() == "wal"


# --- Constraints -----------------------------------------------------------------------------


def _library(s, type_="films"):
    lib = m.Library(type=type_)
    s.add(lib)
    s.flush()
    return lib


def _film(s, lib, title="Dune", year=2021, tmdb_id=None):
    film = m.Film(
        library_id=lib.id,
        parsed_title=title,
        parsed_title_norm=title.lower(),
        parsed_year=year,
        title=title,
        sort_title=title.lower(),
        search_text=title.lower(),
        year=year,
        tmdb_id=tmdb_id,
    )
    s.add(film)
    s.flush()
    return film


def _show(s, lib, title="Lost"):
    show = m.Show(
        library_id=lib.id,
        parsed_title=title,
        parsed_title_norm=title.lower(),
        title=title,
        sort_title=title.lower(),
        search_text=title.lower(),
    )
    s.add(show)
    s.flush()
    return show


def test_defaults_are_applied(db):
    with db.write_session() as s:
        lib = _library(s)
        film = _film(s, lib)
        profile = m.Profile(name="Kids", avatar_id="avatar-01", is_admin=False)
        s.add(profile)
        s.flush()
        assert film.match_status == "pending"
        assert film.added_at is not None
        assert profile.admin_token_version == 1
        assert lib.created_at.tzinfo is not None  # timestamps read back as aware UTC


def test_library_type_check(db):
    with pytest.raises(IntegrityError), db.write_session() as s:
        _library(s, "music")


def test_library_type_unique(db):
    with pytest.raises(IntegrityError), db.write_session() as s:
        _library(s, "films")
        _library(s, "films")


def test_profile_pin_check(db):
    with pytest.raises(IntegrityError), db.write_session() as s:
        s.add(m.Profile(name="Admin", avatar_id="avatar-01", is_admin=True, pin_hash=None))
        s.flush()
    with pytest.raises(IntegrityError), db.write_session() as s:
        s.add(m.Profile(name="Kid", avatar_id="avatar-01", is_admin=False, pin_hash="h"))
        s.flush()


def test_profile_name_unique_case_insensitive(db):
    with db.write_session() as s:
        s.add(m.Profile(name="Esther", avatar_id="avatar-01", is_admin=False))
    with pytest.raises(IntegrityError), db.write_session() as s:
        s.add(m.Profile(name="ESTHER", avatar_id="avatar-02", is_admin=False))
        s.flush()


def test_profile_ids_never_reused(db):
    with db.write_session() as s:
        p = m.Profile(name="A", avatar_id="avatar-01", is_admin=False)
        s.add(p)
        s.flush()
        first_id = p.id
    with db.write_session() as s:
        s.execute(text("DELETE FROM profile"))
    with db.write_session() as s:
        p = m.Profile(name="B", avatar_id="avatar-01", is_admin=False)
        s.add(p)
        s.flush()
        assert p.id == first_id + 1


def test_film_tmdb_id_partial_unique(db):
    with db.write_session() as s:
        lib = _library(s)
        _film(s, lib, "A", tmdb_id=None)
        _film(s, lib, "B", tmdb_id=None)  # NULLs allowed many times
        _film(s, lib, "C", tmdb_id=42)
    with pytest.raises(IntegrityError), db.write_session() as s:
        lib = s.scalars(select(m.Library)).one()
        _film(s, lib, "D", tmdb_id=42)


def test_item_alias_unique_with_null_year(db):
    with db.write_session() as s:
        lib = _library(s)
        film = _film(s, lib)
        s.add(
            m.ItemAlias(
                library_id=lib.id, parsed_title_norm="dune", parsed_year=None, film_id=film.id
            )
        )
    with pytest.raises(IntegrityError), db.write_session() as s:
        lib = s.scalars(select(m.Library)).one()
        film = s.scalars(select(m.Film)).one()
        s.add(
            m.ItemAlias(
                library_id=lib.id, parsed_title_norm="dune", parsed_year=None, film_id=film.id
            )
        )
        s.flush()


def test_item_alias_exactly_one_target(db):
    with pytest.raises(IntegrityError), db.write_session() as s:
        lib = _library(s)
        s.add(m.ItemAlias(library_id=lib.id, parsed_title_norm="x"))
        s.flush()


def test_cast_credit_exactly_one_target(db):
    with pytest.raises(IntegrityError), db.write_session() as s:
        lib = _library(s)
        film = _film(s, lib)
        show = _show(s, _library(s, "shows"))
        person = m.Person(tmdb_id=1, name="P")
        s.add(person)
        s.flush()
        s.add(m.CastCredit(person_id=person.id, film_id=film.id, show_id=show.id, order=0))
        s.flush()


def test_progress_exactly_one_target_and_unique_per_profile(db):
    with db.write_session() as s:
        lib = _library(s)
        film = _film(s, lib)
        p = m.Profile(name="A", avatar_id="avatar-01", is_admin=False)
        s.add(p)
        s.flush()
        s.add(
            m.PlaybackProgress(
                profile_id=p.id, film_id=film.id, position_seconds=1, duration_seconds=10
            )
        )
    with pytest.raises(IntegrityError), db.write_session() as s:
        p = s.scalars(select(m.Profile)).one()
        film = s.scalars(select(m.Film)).one()
        s.add(
            m.PlaybackProgress(
                profile_id=p.id, film_id=film.id, position_seconds=2, duration_seconds=10
            )
        )
        s.flush()
    with pytest.raises(IntegrityError), db.write_session() as s:
        p = s.scalars(select(m.Profile)).one()
        s.add(m.PlaybackProgress(profile_id=p.id, position_seconds=2, duration_seconds=10))
        s.flush()


def test_unmatched_entry_partial_unique_per_file_and_reason(db):
    with db.write_session() as s:
        lib = _library(s)
        now = datetime.now(UTC)
        mf = m.MediaFile(
            library_id=lib.id,
            root_path="/r",
            path="/r/a.mkv",
            relative_path="a.mkv",
            size_bytes=1,
            mtime_ns=1,
            added_at=now,
            updated_at=now,
        )
        s.add(mf)
        s.flush()
        s.add(m.UnmatchedEntry(media_file_id=mf.id, reason="no_title"))
        s.add(m.UnmatchedEntry(media_file_id=mf.id, reason="probe_failed"))
    with pytest.raises(IntegrityError), db.write_session() as s:
        mf = s.scalars(select(m.MediaFile)).one()
        s.add(m.UnmatchedEntry(media_file_id=mf.id, reason="no_title"))
        s.flush()


def test_media_file_film_set_null_and_cascades(db):
    with db.write_session() as s:
        lib = _library(s)
        film = _film(s, lib)
        now = datetime.now(UTC)
        mf = m.MediaFile(
            library_id=lib.id,
            root_path="/r",
            path="/r/a.mkv",
            relative_path="a.mkv",
            size_bytes=1,
            mtime_ns=1,
            film_id=film.id,
            added_at=now,
            updated_at=now,
        )
        s.add(mf)
        s.flush()
        s.add(m.KeyframeIndex(media_file_id=mf.id, source="ffprobe", times=b"\x00"))
        s.add(
            m.SubtitleFile(
                media_file_id=mf.id,
                path="/r/a.en.srt",
                format="srt",
                kind="text",
                size_bytes=1,
                mtime_ns=1,
            )
        )
    with db.write_session() as s:
        s.execute(text("DELETE FROM film"))
    with db.read_session() as s:
        mf = s.scalars(select(m.MediaFile)).one()
        assert mf.film_id is None
    with db.write_session() as s:
        s.execute(text("DELETE FROM media_file"))
    with db.read_session() as s:
        assert s.scalar(text("SELECT count(*) FROM keyframe_index")) == 0
        assert s.scalar(text("SELECT count(*) FROM subtitle_file")) == 0


def test_episode_unique_per_season_and_show_cascade(db):
    with db.write_session() as s:
        show = _show(s, _library(s, "shows"))
        season = m.Season(show_id=show.id, season_number=1)
        s.add(season)
        s.flush()
        s.add(m.Episode(season_id=season.id, show_id=show.id, season_number=1, episode_number=1))
    with pytest.raises(IntegrityError), db.write_session() as s:
        season = s.scalars(select(m.Season)).one()
        s.add(
            m.Episode(
                season_id=season.id, show_id=season.show_id, season_number=1, episode_number=1
            )
        )
        s.flush()
    with db.write_session() as s:
        s.execute(text("DELETE FROM show"))
    with db.read_session() as s:
        assert s.scalar(text("SELECT count(*) FROM season")) == 0
        assert s.scalar(text("SELECT count(*) FROM episode")) == 0


def test_scan_run_json_defaults(db):
    with db.write_session() as s:
        run = m.ScanRun(trigger="manual", state="running", phase="walking")
        s.add(run)
        s.flush()
        assert run.counts == {}
        assert run.errors == []
        assert run.processed == 0
