import sqlite3
import threading
import time

import pytest
from sqlalchemy import event, text

from hometv.db.engine import WRITE_LOCK, Database


@pytest.fixture
def db(tmp_path):
    database = Database(tmp_path / "hometv.db")
    with database.write_session() as s:
        s.execute(text("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)"))
    yield database
    database.dispose()


def _capture(engine):
    statements: list[str] = []

    @event.listens_for(engine, "before_cursor_execute")
    def _before(conn, cursor, statement, parameters, context, executemany):
        statements.append(statement)

    return statements


@pytest.mark.parametrize("which", ["read_engine", "write_engine"])
def test_pragmas_applied_on_every_connection(db, which):
    engine = getattr(db, which)
    with engine.connect() as conn:
        assert conn.exec_driver_sql("PRAGMA journal_mode").scalar() == "wal"
        assert conn.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1
        assert conn.exec_driver_sql("PRAGMA busy_timeout").scalar() == 5000
        assert conn.exec_driver_sql("PRAGMA synchronous").scalar() == 1  # NORMAL


def test_write_engine_pool_is_single_connection(db):
    assert db.write_engine.pool.size() == 1
    assert db.write_engine.pool._max_overflow == 0


def test_write_session_issues_begin_immediate(db):
    statements = _capture(db.write_engine)
    with db.write_session() as s:
        s.execute(text("INSERT INTO t (v) VALUES ('a')"))
    assert statements[0] == "BEGIN IMMEDIATE"


def test_read_session_issues_deferred_begin(db):
    statements = _capture(db.read_engine)
    with db.read_session() as s:
        s.execute(text("SELECT 1")).scalar()
    assert statements[0] == "BEGIN"


def test_second_connection_gets_busy_while_write_session_open(db, tmp_path):
    with db.write_session() as s:
        s.execute(text("SELECT 1"))  # transaction started with BEGIN IMMEDIATE
        other = sqlite3.connect(tmp_path / "hometv.db", timeout=0, isolation_level=None)
        try:
            with pytest.raises(sqlite3.OperationalError, match=r"locked|busy"):
                other.execute("BEGIN IMMEDIATE")
        finally:
            other.close()


def test_reserved_lock_taken_at_start_not_at_first_write(db, tmp_path):
    other = sqlite3.connect(tmp_path / "hometv.db", timeout=0, isolation_level=None)
    try:
        with db.write_session() as s:
            # Any statement starts the transaction; RESERVED is already held.
            s.execute(text("SELECT count(*) FROM t")).scalar()
            with pytest.raises(sqlite3.OperationalError):
                other.execute("BEGIN IMMEDIATE")
    finally:
        other.close()


def test_write_session_commits_on_exit(db):
    with db.write_session() as s:
        s.execute(text("INSERT INTO t (v) VALUES ('kept')"))
    with db.read_session() as s:
        assert s.execute(text("SELECT v FROM t")).scalars().all() == ["kept"]


def test_write_session_rolls_back_on_exception(db):
    with pytest.raises(RuntimeError), db.write_session() as s:
        s.execute(text("INSERT INTO t (v) VALUES ('lost')"))
        raise RuntimeError("boom")
    with db.read_session() as s:
        assert s.execute(text("SELECT count(*) FROM t")).scalar() == 0
    assert not WRITE_LOCK.locked()


def test_write_lock_is_process_wide_and_held_for_the_transaction(db):
    order: list[str] = []
    entered = threading.Event()

    def first():
        with db.write_session() as s:
            entered.set()
            s.execute(text("INSERT INTO t (v) VALUES ('1')"))
            time.sleep(0.2)
            order.append("first-done")

    def second():
        entered.wait()
        assert WRITE_LOCK.locked()
        with db.write_session() as s:
            order.append("second-start")
            s.execute(text("INSERT INTO t (v) VALUES ('2')"))

    t1, t2 = threading.Thread(target=first), threading.Thread(target=second)
    t1.start()
    t2.start()
    t1.join(5)
    t2.join(5)
    assert order == ["first-done", "second-start"]
    assert not WRITE_LOCK.locked()


def test_readers_do_not_take_the_lock(db):
    with db.write_session() as w:
        w.execute(text("INSERT INTO t (v) VALUES ('uncommitted')"))
        with db.read_session() as r:  # would deadlock if readers took the lock
            assert r.execute(text("SELECT count(*) FROM t")).scalar() == 0


def test_foreign_keys_enforced(db):
    with db.write_session() as s:
        s.execute(text("CREATE TABLE p (id INTEGER PRIMARY KEY)"))
        s.execute(
            text(
                "CREATE TABLE c (id INTEGER PRIMARY KEY, "
                "p_id INTEGER NOT NULL REFERENCES p(id) ON DELETE CASCADE)"
            )
        )
        s.execute(text("INSERT INTO p (id) VALUES (1)"))
        s.execute(text("INSERT INTO c (p_id) VALUES (1)"))
    with db.write_session() as s:
        s.execute(text("DELETE FROM p WHERE id = 1"))
    with db.read_session() as s:
        assert s.execute(text("SELECT count(*) FROM c")).scalar() == 0
    with pytest.raises(Exception, match="FOREIGN KEY"), db.write_session() as s:
        s.execute(text("INSERT INTO c (p_id) VALUES (99)"))


def test_health_check(db, tmp_path):
    assert db.ping() is True
