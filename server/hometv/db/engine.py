"""SQLite engines, sessions and the process-wide write lock (PRD §6 Concurrency model).

Two engines on the same file (SQLAlchemy's documented pysqlite recipe):

- both: a ``connect`` event puts pysqlite in driver autocommit mode
  (``isolation_level = None``: it never emits BEGIN/COMMIT itself) and applies the pragmas;
- ``read_engine``: a ``begin`` event emits ``BEGIN`` (deferred; one WAL snapshot per session);
- ``write_engine`` (``pool_size=1, max_overflow=0``): a ``begin`` event emits
  ``BEGIN IMMEDIATE`` so the RESERVED lock is taken when the transaction starts.

Rules: every write goes through ``Database.write_session()``; readers use
``Database.read_session()`` and never take the lock. Never hold a write session across an
``await``, a subprocess, an HTTP request or a directory walk.
"""

import threading
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any

from sqlalchemy import Engine, create_engine, event, text
from sqlalchemy.orm import Session
from sqlalchemy.pool import QueuePool

WRITE_LOCK = threading.Lock()
"""Process-wide lock serializing write transactions (one writer at a time)."""

PRAGMAS = (
    "PRAGMA journal_mode=WAL",
    "PRAGMA foreign_keys=ON",
    "PRAGMA busy_timeout=5000",
    "PRAGMA synchronous=NORMAL",
)


def write_lock_held() -> bool:
    return WRITE_LOCK.locked()


def _on_connect(dbapi_connection: Any, _record: Any) -> None:
    dbapi_connection.isolation_level = None
    cursor = dbapi_connection.cursor()
    try:
        for pragma in PRAGMAS:
            cursor.execute(pragma)
    finally:
        cursor.close()


def _make_engine(url: str, begin_sql: str, **kwargs: Any) -> Engine:
    engine = create_engine(url, connect_args={"check_same_thread": False}, **kwargs)
    event.listen(engine, "connect", _on_connect)

    @event.listens_for(engine, "begin")
    def _begin(conn: Any) -> None:
        conn.exec_driver_sql(begin_sql)

    return engine


class Database:
    """Engines + session factories for ``{data_dir}/hometv.db``."""

    def __init__(self, path: Path | str) -> None:
        self.path = Path(path)
        url = f"sqlite:///{self.path}"
        self.read_engine = _make_engine(
            url, "BEGIN", poolclass=QueuePool, pool_size=8, max_overflow=8
        )
        self.write_engine = _make_engine(
            url, "BEGIN IMMEDIATE", poolclass=QueuePool, pool_size=1, max_overflow=0
        )

    @contextmanager
    def read_session(self) -> Iterator[Session]:
        """Read-only session (deferred BEGIN, consistent snapshot); rolled back on exit."""
        with Session(self.read_engine, expire_on_commit=False) as session:
            yield session

    @contextmanager
    def write_session(self) -> Iterator[Session]:
        """One short write transaction under the process-wide lock.

        Commits on exit, rolls back on exception.
        """
        with (
            WRITE_LOCK,
            Session(self.write_engine, expire_on_commit=False) as session,
            session.begin(),
        ):
            yield session

    def ping(self) -> bool:
        try:
            with self.read_engine.connect() as conn:
                conn.execute(text("SELECT 1")).scalar()
        except Exception:
            return False
        return True

    def dispose(self) -> None:
        self.read_engine.dispose()
        self.write_engine.dispose()


# --- Migrations ----------------------------------------------------------------------------

MIGRATIONS_DIR = Path(__file__).resolve().parent / "migrations"
HEAD_REVISION = "0004"


def alembic_config(database: "Database | None" = None) -> Any:
    from alembic.config import Config

    cfg = Config()
    cfg.set_main_option("script_location", str(MIGRATIONS_DIR))
    if database is not None:
        cfg.set_main_option("sqlalchemy.url", f"sqlite:///{database.path}")
    return cfg


def run_migrations(database: Database) -> None:
    """``alembic upgrade head`` on the write engine, under the write lock."""
    from alembic import command

    cfg = alembic_config(database)
    with WRITE_LOCK, database.write_engine.connect() as conn:
        cfg.attributes["connection"] = conn
        command.upgrade(cfg, "head")
        conn.commit()
