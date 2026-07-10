use serde::Serialize;
use sqlx::FromRow;

#[derive(Serialize, FromRow)]
#[serde(rename_all = "camelCase")]
pub(super) struct TagCount {
    pub(super) tag: String,
    pub(super) count: i64,
}
