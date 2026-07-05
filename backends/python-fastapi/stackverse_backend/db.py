from __future__ import annotations

from contextlib import contextmanager
import os
from pathlib import Path
from typing import Iterator, Sequence

from psycopg import Connection
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from .config import config
from .logging_setup import log_event, logger

pool = ConnectionPool(config.db_conninfo, min_size=1, max_size=10, open=False, kwargs={"row_factory": dict_row})


def open_pool() -> None:
    pool.open(wait=True)


def close_pool() -> None:
    pool.close()


def query(sql: str, params: Sequence[object] = ()) -> list[dict]:
    with pool.connection() as conn:
        return list(conn.execute(sql, params).fetchall())


def one(sql: str, params: Sequence[object] = ()) -> dict | None:
    rows = query(sql, params)
    return rows[0] if rows else None


def execute(sql: str, params: Sequence[object] = ()) -> None:
    with pool.connection() as conn:
        conn.execute(sql, params)


@contextmanager
def transaction() -> Iterator[Connection]:
    with pool.connection() as conn:
        with conn.transaction():
            yield conn


def run_migrations() -> None:
    migrations_dir = _migrations_dir()
    with transaction() as conn:
        conn.execute("select pg_advisory_lock(hashtext('stackverse-python-fastapi-migrations'))")
        try:
            conn.execute(
                """
                create table if not exists schema_migrations (
                    name text primary key,
                    applied_at timestamptz not null default now()
                )
                """
            )
            for migration in sorted(migrations_dir.glob("*.sql")):
                already = conn.execute(
                    "select 1 from schema_migrations where name = %s",
                    (migration.name,),
                ).fetchone()
                if already:
                    continue
                logger.debug("Applying migration %s", migration.name)
                conn.execute(migration.read_text(encoding="utf-8"))
                conn.execute("insert into schema_migrations (name) values (%s)", (migration.name,))
                log_event(
                    "info",
                    "db_migration_applied",
                    "success",
                    f"Applied migration {migration.name}",
                    migration=migration.name,
                )
        finally:
            conn.execute("select pg_advisory_unlock(hashtext('stackverse-python-fastapi-migrations'))")


def _migrations_dir() -> Path:
    candidates = [
        Path(os.getenv("MIGRATIONS_DIR", "")) if os.getenv("MIGRATIONS_DIR") else None,
        Path.cwd() / "migrations",
        Path(__file__).resolve().parent.parent / "migrations",
    ]
    for candidate in candidates:
        if candidate is not None and any(candidate.glob("*.sql")):
            return candidate
    raise RuntimeError("Migration directory not found - set MIGRATIONS_DIR or run from the backend directory")
