use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use chrono::{DateTime, Utc};
use redis::AsyncCommands;
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

pub const SESSION_TTL: Duration = Duration::from_secs(8 * 60 * 60);
pub const STATE_TTL: Duration = Duration::from_secs(5 * 60);

const SESSION_KEY_PREFIX: &str = "stackverse:session:";
const STATE_KEY_PREFIX: &str = "stackverse:oauth-state:";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionData {
    pub username: String,
    pub access_token: String,
    pub refresh_token: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub id_token: String,
    pub expires_at: DateTime<Utc>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OAuthState {
    pub code_verifier: String,
    pub created_at: DateTime<Utc>,
}

#[async_trait]
pub trait SessionStore: Send + Sync {
    async fn load_session(&self, key: &str) -> anyhow::Result<Option<SessionData>>;
    async fn save_session(
        &self,
        key: &str,
        data: &SessionData,
        ttl: Duration,
    ) -> anyhow::Result<()>;
    async fn delete_session(&self, key: &str) -> anyhow::Result<()>;
    async fn save_oauth_state(
        &self,
        state: &str,
        data: &OAuthState,
        ttl: Duration,
    ) -> anyhow::Result<()>;
    async fn consume_oauth_state(&self, state: &str) -> anyhow::Result<Option<OAuthState>>;
    async fn ping(&self) -> anyhow::Result<()>;
}

pub type DynSessionStore = Arc<dyn SessionStore>;

pub struct RedisSessionStore {
    client: redis::Client,
}

impl RedisSessionStore {
    pub async fn new(redis_url: &str) -> anyhow::Result<Self> {
        let url = if redis_url.contains("://") {
            redis_url.to_string()
        } else {
            format!("redis://{redis_url}")
        };
        let client = redis::Client::open(url)?;
        let mut connection = client.get_multiplexed_async_connection().await?;
        let _: String = redis::cmd("PING").query_async(&mut connection).await?;
        Ok(Self { client })
    }

    async fn connection(&self) -> anyhow::Result<redis::aio::MultiplexedConnection> {
        Ok(self.client.get_multiplexed_async_connection().await?)
    }
}

#[async_trait]
impl SessionStore for RedisSessionStore {
    async fn load_session(&self, key: &str) -> anyhow::Result<Option<SessionData>> {
        let mut connection = self.connection().await?;
        let raw: Option<String> = connection.get(format!("{SESSION_KEY_PREFIX}{key}")).await?;
        raw.map(|value| serde_json::from_str(&value).map_err(Into::into))
            .transpose()
    }

    async fn save_session(
        &self,
        key: &str,
        data: &SessionData,
        ttl: Duration,
    ) -> anyhow::Result<()> {
        let raw = serde_json::to_string(data)?;
        let mut connection = self.connection().await?;
        let _: () = connection
            .set_ex(format!("{SESSION_KEY_PREFIX}{key}"), raw, ttl.as_secs())
            .await?;
        Ok(())
    }

    async fn delete_session(&self, key: &str) -> anyhow::Result<()> {
        let mut connection = self.connection().await?;
        let _: () = connection.del(format!("{SESSION_KEY_PREFIX}{key}")).await?;
        Ok(())
    }

    async fn save_oauth_state(
        &self,
        state: &str,
        data: &OAuthState,
        ttl: Duration,
    ) -> anyhow::Result<()> {
        let raw = serde_json::to_string(data)?;
        let mut connection = self.connection().await?;
        let _: () = connection
            .set_ex(format!("{STATE_KEY_PREFIX}{state}"), raw, ttl.as_secs())
            .await?;
        Ok(())
    }

    async fn consume_oauth_state(&self, state: &str) -> anyhow::Result<Option<OAuthState>> {
        let mut connection = self.connection().await?;
        let raw: Option<String> = redis::cmd("GETDEL")
            .arg(format!("{STATE_KEY_PREFIX}{state}"))
            .query_async(&mut connection)
            .await?;
        raw.map(|value| serde_json::from_str(&value).map_err(Into::into))
            .transpose()
    }

    async fn ping(&self) -> anyhow::Result<()> {
        let mut connection = self.connection().await?;
        let _: String = redis::cmd("PING").query_async(&mut connection).await?;
        Ok(())
    }
}

#[derive(Clone, Default)]
pub struct MemorySessionStore {
    state: Arc<Mutex<MemoryState>>,
}

#[derive(Default)]
struct MemoryState {
    sessions: HashMap<String, SessionData>,
    oauth_states: HashMap<String, OAuthState>,
    fail: bool,
}

impl MemorySessionStore {
    pub fn new() -> Self {
        Self::default()
    }

    pub async fn put_session(&self, key: &str, data: SessionData) {
        self.state
            .lock()
            .await
            .sessions
            .insert(key.to_string(), data);
    }

    pub async fn has_session(&self, key: &str) -> bool {
        self.state.lock().await.sessions.contains_key(key)
    }

    #[cfg(test)]
    pub async fn set_fail(&self, fail: bool) {
        self.state.lock().await.fail = fail;
    }

    async fn check(&self) -> anyhow::Result<()> {
        if self.state.lock().await.fail {
            anyhow::bail!("memory store forced failure");
        }
        Ok(())
    }
}

#[async_trait]
impl SessionStore for MemorySessionStore {
    async fn load_session(&self, key: &str) -> anyhow::Result<Option<SessionData>> {
        self.check().await?;
        Ok(self.state.lock().await.sessions.get(key).cloned())
    }

    async fn save_session(
        &self,
        key: &str,
        data: &SessionData,
        _ttl: Duration,
    ) -> anyhow::Result<()> {
        self.check().await?;
        self.state
            .lock()
            .await
            .sessions
            .insert(key.to_string(), data.clone());
        Ok(())
    }

    async fn delete_session(&self, key: &str) -> anyhow::Result<()> {
        self.check().await?;
        self.state.lock().await.sessions.remove(key);
        Ok(())
    }

    async fn save_oauth_state(
        &self,
        state: &str,
        data: &OAuthState,
        _ttl: Duration,
    ) -> anyhow::Result<()> {
        self.check().await?;
        self.state
            .lock()
            .await
            .oauth_states
            .insert(state.to_string(), data.clone());
        Ok(())
    }

    async fn consume_oauth_state(&self, state: &str) -> anyhow::Result<Option<OAuthState>> {
        self.check().await?;
        Ok(self.state.lock().await.oauth_states.remove(state))
    }

    async fn ping(&self) -> anyhow::Result<()> {
        self.check().await
    }
}
