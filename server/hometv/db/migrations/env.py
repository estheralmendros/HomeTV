"""Alembic environment.

Programmatic use (app lifespan / tests): ``hometv.db.engine.run_migrations`` passes an open
connection via ``config.attributes["connection"]``. CLI use (``uv run alembic upgrade head``
from ``server/``): the URL comes from ``sqlalchemy.url`` or, if unset, from the HomeTV
settings (``{data_dir}/hometv.db``).
"""

from alembic import context
from sqlalchemy import create_engine

from hometv.db.engine import _on_connect
from hometv.db.models import Base

config = context.config
target_metadata = Base.metadata


def _url() -> str:
    url = config.get_main_option("sqlalchemy.url")
    if url:
        return url
    from hometv.config import load_settings

    return f"sqlite:///{load_settings().db_path}"


def _configure(connection) -> None:
    context.configure(
        connection=connection,
        target_metadata=target_metadata,
        render_as_batch=True,
        compare_type=True,
    )


def run_migrations_offline() -> None:
    context.configure(
        url=_url(), target_metadata=target_metadata, literal_binds=True, render_as_batch=True
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    connection = config.attributes.get("connection")
    if connection is not None:
        _configure(connection)
        with context.begin_transaction():
            context.run_migrations()
        return
    engine = create_engine(_url())
    from sqlalchemy import event

    event.listen(engine, "connect", _on_connect)
    with engine.connect() as conn:
        _configure(conn)
        with context.begin_transaction():
            context.run_migrations()
        conn.commit()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
