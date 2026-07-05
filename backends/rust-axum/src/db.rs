use std::fs;
use std::path::Path;

use anyhow::Context;
use sqlx::postgres::PgPoolOptions;
use sqlx::{Acquire, Executor, PgPool};
use uuid::Uuid;

use crate::config::Config;
use crate::error::now_utc;

const MIGRATION_LOCK: i64 = 0x5741_434b;
const MIGRATIONS: &[(&str, &str)] = &[(
    "0001_schema.sql",
    include_str!("../migrations/0001_schema.sql"),
)];

pub async fn connect(config: &Config) -> anyhow::Result<PgPool> {
    let pool = PgPoolOptions::new()
        .max_connections(10)
        .connect(&config.database_url())
        .await?;
    migrate(&pool).await?;
    Ok(pool)
}

async fn migrate(pool: &PgPool) -> anyhow::Result<()> {
    let mut connection = pool.acquire().await?;
    sqlx::query("select pg_advisory_lock($1)")
        .bind(MIGRATION_LOCK)
        .execute(&mut *connection)
        .await?;

    let result = async {
        sqlx::query(
            "create table if not exists schema_migrations (version text primary key, applied_at timestamptz not null)",
        )
        .execute(&mut *connection)
        .await?;

        for (name, sql) in MIGRATIONS {
            let applied: bool = sqlx::query_scalar(
                "select exists (select 1 from schema_migrations where version = $1)",
            )
            .bind(name)
            .fetch_one(&mut *connection)
            .await?;
            if applied {
                continue;
            }
            let mut tx = connection.begin().await?;
            tx.execute(*sql).await.with_context(|| format!("migration {name}"))?;
            sqlx::query("insert into schema_migrations (version, applied_at) values ($1, $2)")
                .bind(name)
                .bind(now_utc())
                .execute(&mut *tx)
                .await?;
            tx.commit().await?;
            tracing::info!(
                event = "db_migration_applied",
                outcome = "success",
                migration = *name,
                "Applied database migration"
            );
        }
        anyhow::Ok(())
    }
    .await;

    let unlock = sqlx::query("select pg_advisory_unlock($1)")
        .bind(MIGRATION_LOCK)
        .execute(&mut *connection)
        .await;
    if let Err(err) = unlock {
        tracing::warn!(error = %err, "failed to release migration advisory lock");
    }
    result
}

pub async fn seed_messages(pool: &PgPool, dir: &str) -> anyhow::Result<()> {
    let mut files = fs::read_dir(dir)
        .with_context(|| format!("message seed directory not found: {dir}"))?
        .filter_map(Result::ok)
        .filter(|entry| entry.path().extension().and_then(|ext| ext.to_str()) == Some("json"))
        .collect::<Vec<_>>();
    files.sort_by_key(|entry| entry.file_name());

    for entry in files {
        let path = entry.path();
        seed_language(pool, &path).await?;
    }
    Ok(())
}

async fn seed_language(pool: &PgPool, path: &Path) -> anyhow::Result<()> {
    let language = path
        .file_stem()
        .and_then(|stem| stem.to_str())
        .context("seed file has no language stem")?
        .to_string();
    let raw = fs::read_to_string(path)?;
    let texts: std::collections::BTreeMap<String, String> = serde_json::from_str(&raw)?;
    let now = now_utc();
    let mut tx = pool.begin().await?;
    let mut inserted = 0_u64;
    for (key, text) in &texts {
        let result = sqlx::query(
            r#"insert into messages (id, key, language, text, description, created_at, updated_at)
               values ($1, $2, $3, $4, null, $5, $5)
               on conflict (key, language) do nothing"#,
        )
        .bind(Uuid::new_v4())
        .bind(key)
        .bind(&language)
        .bind(text)
        .bind(now)
        .execute(&mut *tx)
        .await?;
        inserted += result.rows_affected();
    }
    tx.commit().await?;
    tracing::info!(
        event = "message_seed_imported",
        outcome = "success",
        language = %language,
        inserted = inserted,
        skipped = texts.len() as u64 - inserted,
        "Message seed imported"
    );
    Ok(())
}

pub async fn ready(pool: &PgPool) -> bool {
    sqlx::query("select 1").fetch_one(pool).await.is_ok()
}

pub fn pg_not_found(err: &sqlx::Error) -> bool {
    matches!(err, sqlx::Error::RowNotFound)
}

pub fn pg_unique_violation(err: &sqlx::Error) -> bool {
    err.as_database_error()
        .and_then(|db| db.code())
        .is_some_and(|code| code == "23505")
}
