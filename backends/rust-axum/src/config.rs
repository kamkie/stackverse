#[derive(Debug, Clone)]
pub struct Config {
    pub port: String,
    pub db_host: String,
    pub db_port: String,
    pub db_name: String,
    pub db_user: String,
    pub db_password: String,
    pub oidc_issuer_uri: String,
    pub oidc_jwks_uri: Option<String>,
    pub seed_messages_dir: String,
    pub log_level: String,
    pub log_format: String,
    pub otel_disabled: bool,
}

impl Config {
    pub const AUDIENCE: &'static str = "stackverse-api";

    pub fn load() -> Self {
        Self {
            port: env("PORT", "8080"),
            db_host: env("DB_HOST", "localhost"),
            db_port: env("DB_PORT", "5432"),
            db_name: env("DB_NAME", "stackverse"),
            db_user: env("DB_USER", "stackverse"),
            db_password: env("DB_PASSWORD", "stackverse"),
            oidc_issuer_uri: env("OIDC_ISSUER_URI", "http://localhost:8180/realms/stackverse"),
            oidc_jwks_uri: optional_env("OIDC_JWKS_URI"),
            seed_messages_dir: env("SEED_MESSAGES_DIR", "../../spec/messages"),
            log_level: env("LOG_LEVEL", "info"),
            log_format: env("LOG_FORMAT", "json"),
            otel_disabled: env("OTEL_SDK_DISABLED", "true") != "false",
        }
    }

    pub fn database_url(&self) -> String {
        format!(
            "postgres://{}:{}@{}:{}/{}",
            self.db_user, self.db_password, self.db_host, self.db_port, self.db_name
        )
    }
}

fn env(name: &str, fallback: &str) -> String {
    std::env::var(name).unwrap_or_else(|_| fallback.to_string())
}

fn optional_env(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|value| !value.is_empty())
}

#[cfg(test)]
mod tests {
    use super::Config;

    #[test]
    fn database_url_uses_configured_connection_fields() {
        let config = Config {
            port: "8080".to_string(),
            db_host: "db".to_string(),
            db_port: "6543".to_string(),
            db_name: "stackverse_test".to_string(),
            db_user: "tester".to_string(),
            db_password: "secret".to_string(),
            oidc_issuer_uri: "http://idp/realms/stackverse".to_string(),
            oidc_jwks_uri: None,
            seed_messages_dir: "../../spec/messages".to_string(),
            log_level: "info".to_string(),
            log_format: "json".to_string(),
            otel_disabled: true,
        };

        assert_eq!(
            config.database_url(),
            "postgres://tester:secret@db:6543/stackverse_test"
        );
    }
}
