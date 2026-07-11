mod support;

use std::time::Duration;

use chrono::{TimeDelta, Utc};
use stackverse_gateway_rust::session::{OAuthState, RedisSessionStore, SessionData, SessionStore};

use support::FakeRedis;

#[tokio::test]
async fn redis_store_scopes_serializes_expires_and_deletes_session_state() {
    let redis = FakeRedis::spawn(Some("test-password")).await;
    let store = RedisSessionStore::new(&redis.url).await.unwrap();
    let now = Utc::now();
    let session = SessionData {
        username: "moderator".to_string(),
        access_token: "access-token".to_string(),
        refresh_token: "refresh-token".to_string(),
        id_token: "id-token".to_string(),
        expires_at: now + TimeDelta::minutes(5),
        created_at: now,
        updated_at: now,
    };

    store
        .save_session("session-1", &session, Duration::from_secs(37))
        .await
        .unwrap();
    let loaded = store.load_session("session-1").await.unwrap().unwrap();
    assert_eq!(loaded.username, "moderator");
    assert_eq!(loaded.access_token, "access-token");
    assert_eq!(loaded.refresh_token, "refresh-token");
    assert_eq!(loaded.id_token, "id-token");
    assert_eq!(loaded.expires_at, session.expires_at);

    let oauth_state = OAuthState {
        code_verifier: "pkce-verifier".to_string(),
        created_at: now,
    };
    store
        .save_oauth_state("login-1", &oauth_state, Duration::from_secs(13))
        .await
        .unwrap();
    let consumed = store.consume_oauth_state("login-1").await.unwrap().unwrap();
    assert_eq!(consumed.code_verifier, "pkce-verifier");
    assert!(
        store
            .consume_oauth_state("login-1")
            .await
            .unwrap()
            .is_none()
    );

    store.delete_session("session-1").await.unwrap();
    assert!(store.load_session("session-1").await.unwrap().is_none());
    store.ping().await.unwrap();

    let commands = redis.commands().await;
    assert!(commands.iter().any(|command| {
        command.first().is_some_and(|name| name == "AUTH")
            && command.last().is_some_and(|value| value == "test-password")
    }));
    assert!(commands.iter().any(|command| {
        command.first().is_some_and(|name| name == "SETEX")
            && command
                .get(1)
                .is_some_and(|key| key == "stackverse:session:session-1")
            && command.get(2).is_some_and(|ttl| ttl == "37")
    }));
    assert!(commands.iter().any(|command| {
        command.first().is_some_and(|name| name == "SETEX")
            && command
                .get(1)
                .is_some_and(|key| key == "stackverse:oauth-state:login-1")
            && command.get(2).is_some_and(|ttl| ttl == "13")
    }));
    assert!(commands.iter().any(|command| {
        command.first().is_some_and(|name| name == "GETDEL")
            && command
                .get(1)
                .is_some_and(|key| key == "stackverse:oauth-state:login-1")
    }));
}

#[tokio::test]
async fn redis_store_reports_corrupt_tickets_and_consumes_corrupt_login_state_once() {
    let redis = FakeRedis::spawn(None).await;
    redis
        .set_raw("stackverse:session:corrupt", b"not-json".to_vec())
        .await;
    redis
        .set_raw("stackverse:oauth-state:corrupt", b"not-json".to_vec())
        .await;
    let store = RedisSessionStore::new(&redis.url).await.unwrap();

    assert!(store.load_session("corrupt").await.is_err());
    assert!(store.consume_oauth_state("corrupt").await.is_err());
    assert!(!redis.contains_key("stackverse:oauth-state:corrupt").await);
    assert!(
        store
            .consume_oauth_state("corrupt")
            .await
            .unwrap()
            .is_none()
    );
}
