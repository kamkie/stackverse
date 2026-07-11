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

#[cfg(test)]
mod tests {
    use std::fs;

    use sqlx::PgPool;
    use uuid::Uuid;

    use super::{migrate, pg_not_found, pg_unique_violation, ready, seed_messages};
    use crate::test_support::MIGRATOR;

    #[sqlx::test(migrator = "MIGRATOR")]
    async fn message_seed_is_idempotent_and_preserves_runtime_edits(pool: PgPool) {
        let dir = std::env::temp_dir().join(format!("stackverse-rust-seed-{}", Uuid::new_v4()));
        fs::create_dir_all(&dir).unwrap();
        fs::write(
            dir.join("en.json"),
            r#"{"ui.greeting":"Hello","validation.title.required":"Title is required"}"#,
        )
        .unwrap();
        fs::write(dir.join("pl.json"), r#"{"ui.greeting":"Cześć"}"#).unwrap();

        seed_messages(&pool, dir.to_str().unwrap()).await.unwrap();
        assert_eq!(
            sqlx::query_scalar::<_, i64>("select count(*) from messages")
                .fetch_one(&pool)
                .await
                .unwrap(),
            3
        );
        sqlx::query(
            "update messages set text = 'Customized' where key = 'ui.greeting' and language = 'en'",
        )
        .execute(&pool)
        .await
        .unwrap();

        seed_messages(&pool, dir.to_str().unwrap()).await.unwrap();
        let text: String = sqlx::query_scalar(
            "select text from messages where key = 'ui.greeting' and language = 'en'",
        )
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(text, "Customized");
        fs::remove_dir_all(dir).unwrap();
    }

    #[sqlx::test(migrator = "MIGRATOR")]
    async fn readiness_migration_and_postgres_error_classification_use_real_boundaries(
        pool: PgPool,
    ) {
        assert!(ready(&pool).await);
        assert!(pg_not_found(&sqlx::Error::RowNotFound));

        let now = crate::error::now_utc();
        sqlx::query(
            "insert into user_accounts (username, first_seen, last_seen, status) values ('alice', $1, $1, 'active')",
        )
        .bind(now)
        .execute(&pool)
        .await
        .unwrap();
        let duplicate = sqlx::query(
            "insert into user_accounts (username, first_seen, last_seen, status) values ('alice', $1, $1, 'active')",
        )
        .bind(now)
        .execute(&pool)
        .await
        .unwrap_err();
        assert!(pg_unique_violation(&duplicate));
        assert!(!pg_not_found(&duplicate));

        sqlx::query(
            "create table if not exists schema_migrations (version text primary key, applied_at timestamptz not null)",
        )
        .execute(&pool)
        .await
        .unwrap();
        sqlx::query(
            "insert into schema_migrations (version, applied_at) values ('0001_schema.sql', $1) on conflict do nothing",
        )
        .bind(now)
        .execute(&pool)
        .await
        .unwrap();
        migrate(&pool).await.unwrap();
    }

    #[sqlx::test(migrator = "MIGRATOR")]
    async fn missing_seed_directory_has_actionable_context(pool: PgPool) {
        let missing =
            std::env::temp_dir().join(format!("missing-stackverse-seed-{}", Uuid::new_v4()));
        let error = seed_messages(&pool, missing.to_str().unwrap())
            .await
            .unwrap_err();
        assert!(
            error
                .to_string()
                .contains("message seed directory not found")
        );
    }
}
