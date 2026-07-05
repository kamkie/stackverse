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
