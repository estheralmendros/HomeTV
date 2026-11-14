"""``python -m hometv.parse_report``: read-only dry run over a seeded database."""

from alembic import command

from hometv.db.engine import Database, alembic_config, run_migrations
from hometv.parse_report import main
from tests.factories import add_file, add_film
from tests.stubs import capture_writes


def _settings(make_settings, tmp_path):
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    return make_settings(data_dir=data_dir)


def _run(settings) -> tuple[int, list[str]]:
    out: list[str] = []
    return main(settings, write=out.append), out


def _parsed(title, year=None):
    return {"kind": "film", "title": title, "year": year, "part": None}


def test_main_prints_differing_rows_and_writes_nothing(make_settings, tmp_path):
    settings = _settings(make_settings, tmp_path)
    db = Database(settings.db_path)
    run_migrations(db)
    with db.write_session() as s:
        pending = add_film(s, "doy mis ojos", year=2003, files=0, match_status="pending")
        a = add_file(s, film_id=pending.id, rel="Te doy mis ojos (2003).mkv", library="films")
        a.parsed = _parsed("doy mis ojos", 2003)
        matched = add_film(s, "Dune", year=2021, files=0, match_status="matched")
        b = add_file(s, film_id=matched.id, rel="Dune (2021).mkv", library="films")
        b.parsed = _parsed("Dune", 2021)
        manual = add_file(s, film_id=matched.id, rel="Manual (1999).mkv", library="films")
        manual.parsed = _parsed("x", 1)
        manual.manual_match = {"tmdb_id": 1}
        add_file(s, rel="Spider-Man.2002.1080p.mkv", library="films")  # unlinked, parsed NULL
    db.dispose()

    db = Database(settings.db_path)
    try:
        with capture_writes(db) as writes:
            code, out = _run(settings)
    finally:
        db.dispose()
    assert code == 0
    assert writes == []
    assert out[0] == "status\tstored\tnew\tpath"
    assert (
        "pending\tfilm|doy mis ojos|2003|-\tfilm|Te doy mis ojos|2003|-\tTe doy mis ojos (2003).mkv"
        in out
    )
    assert not any("Dune" in line for line in out)
    assert any(line.startswith("manual\t") and "Manual (1999).mkv" in line for line in out)
    assert any(
        line.startswith("unlinked\t-\tfilm|Spider-Man|2002|-\t") and line.endswith("1080p.mkv")
        for line in out
    )
    assert out[-1].startswith("# 4 film files, 3 differ:")


def test_main_works_with_no_rows(make_settings, tmp_path):
    settings = _settings(make_settings, tmp_path)
    db = Database(settings.db_path)
    run_migrations(db)
    db.dispose()
    code, out = _run(settings)
    assert code == 0
    assert out[-1] == (
        "# 0 film files, 0 differ: pending 0, unmatched 0, matched 0, manual 0, unlinked 0"
    )


def test_main_works_on_a_revision_0007_database(make_settings, tmp_path):
    settings = _settings(make_settings, tmp_path)
    db = Database(settings.db_path)
    cfg = alembic_config(db)
    with db.write_engine.connect() as conn:
        cfg.attributes["connection"] = conn
        command.upgrade(cfg, "0007")
        conn.exec_driver_sql("INSERT INTO library (id, type) VALUES (1, 'films')")
        conn.exec_driver_sql(
            "INSERT INTO media_file (id, library_id, root_path, path, relative_path,"
            " size_bytes, mtime_ns) VALUES (1, 1, '/m', '/m/Te doy mis ojos (2003).mkv',"
            " 'Te doy mis ojos (2003).mkv', 1, 1)"
        )
        conn.commit()
    db.dispose()
    code, out = _run(settings)
    assert code == 0
    assert out[1] == "unlinked\t-\tfilm|Te doy mis ojos|2003|-\tTe doy mis ojos (2003).mkv"
    assert out[-1].startswith("# 1 film files, 1 differ:")


def test_main_without_a_database_exits_1(make_settings, tmp_path):
    settings = _settings(make_settings, tmp_path)
    code, out = _run(settings)
    assert (code, out) == (1, [])
    assert not settings.db_path.exists()
