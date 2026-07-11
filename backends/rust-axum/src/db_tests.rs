use std::fs;
use std::path::{Path, PathBuf};

use sqlx::PgPool;
use uuid::Uuid;

use super::{migrate, pg_not_found, pg_unique_violation, ready, seed_messages};
use crate::test_support::MIGRATOR;

struct TestDir(PathBuf);

impl TestDir {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!("stackverse-rust-seed-{}", Uuid::new_v4()));
        fs::create_dir_all(&path).unwrap();
        Self(path)
    }

    fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for TestDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

#[sqlx::test(migrator = "MIGRATOR")]
async fn message_seed_is_idempotent_and_preserves_runtime_edits(pool: PgPool) {
    let dir = TestDir::new();
    fs::write(
        dir.path().join("en.json"),
        r#"{"ui.greeting":"Hello","validation.title.required":"Title is required"}"#,
    )
    .unwrap();
    fs::write(dir.path().join("pl.json"), r#"{"ui.greeting":"Cześć"}"#).unwrap();

    seed_messages(&pool, dir.path().to_str().unwrap())
        .await
        .unwrap();
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

    seed_messages(&pool, dir.path().to_str().unwrap())
        .await
        .unwrap();
    let text: String = sqlx::query_scalar(
        "select text from messages where key = 'ui.greeting' and language = 'en'",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(text, "Customized");
}

#[sqlx::test(migrator = "MIGRATOR")]
async fn readiness_migration_and_postgres_error_classification_use_real_boundaries(pool: PgPool) {
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
    let missing = std::env::temp_dir().join(format!("missing-stackverse-seed-{}", Uuid::new_v4()));
    let error = seed_messages(&pool, missing.to_str().unwrap())
        .await
        .unwrap_err();
    assert!(
        error
            .to_string()
            .contains("message seed directory not found")
    );
}
