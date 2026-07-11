use std::path::PathBuf;

use anyhow::{Context, anyhow};
use url::Url;

#[derive(Debug, Clone)]
pub struct Config {
    pub port: String,
    pub backend_url: Url,
    pub frontend_url: Option<Url>,
    pub spa_root: Option<PathBuf>,
    pub redis_url: String,
    pub oidc_issuer_uri: String,
    pub oidc_internal_issuer_uri: String,
    pub oidc_client_id: String,
    pub oidc_client_secret: String,
    pub public_url: Url,
    pub log_level: String,
    pub log_format: String,
    pub otel_disabled: bool,
}

impl Config {
    pub fn load() -> anyhow::Result<Self> {
        let backend_url = parse_url(&env("BACKEND_URL", "http://localhost:8080"), "BACKEND_URL")?;
        let frontend_url = optional_env("FRONTEND_URL")
            .map(|raw| parse_url(&raw, "FRONTEND_URL"))
            .transpose()?;
        let public_url = parse_url(&env("PUBLIC_URL", "http://localhost:8000"), "PUBLIC_URL")?;
        let issuer = trim_trailing_slash(&env(
            "OIDC_ISSUER_URI",
            "http://localhost:8180/realms/stackverse",
        ));
        let internal = optional_env("OIDC_INTERNAL_ISSUER_URI")
            .map(|value| trim_trailing_slash(&value))
            .unwrap_or_else(|| issuer.clone());
        let spa_root = optional_env("SPA_ROOT").map(PathBuf::from);

        Ok(Self {
            port: env("PORT", "8000"),
            backend_url,
            frontend_url,
            spa_root,
            redis_url: env("REDIS_URL", "redis://localhost:6379"),
            oidc_issuer_uri: issuer,
            oidc_internal_issuer_uri: internal,
            oidc_client_id: env("OIDC_CLIENT_ID", "stackverse-gateway"),
            oidc_client_secret: env("OIDC_CLIENT_SECRET", "stackverse-secret"),
            public_url,
            log_level: env("LOG_LEVEL", "info"),
            log_format: env("LOG_FORMAT", "json"),
            otel_disabled: env("OTEL_SDK_DISABLED", "true") != "false",
        })
    }

    pub fn cookies_secure(&self) -> bool {
        self.public_url.scheme().eq_ignore_ascii_case("https")
    }

    pub fn redirect_uri(&self) -> String {
        let mut redirect = self.public_url.clone();
        redirect.set_path("/auth/callback");
        redirect.set_query(None);
        redirect.set_fragment(None);
        redirect.to_string()
    }

    pub fn redis_endpoint_for_logs(&self) -> String {
        Url::parse(&self.redis_url)
            .ok()
            .and_then(|url| {
                let host = url.host_str()?;
                let port = url.port_or_known_default().unwrap_or(6379);
                Some(format!("{host}:{port}"))
            })
            .unwrap_or_else(|| {
                if self.redis_url.contains('@') {
                    String::new()
                } else {
                    self.redis_url.clone()
                }
            })
    }

    pub fn url_for_logs(url: &Url) -> String {
        let mut sanitized = url.clone();
        let _ = sanitized.set_password(None);
        let _ = sanitized.set_username("");
        sanitized.set_query(None);
        sanitized.set_fragment(None);
        sanitized.to_string()
    }
}

fn parse_url(raw: &str, name: &str) -> anyhow::Result<Url> {
    let parsed = Url::parse(raw).with_context(|| format!("{name} must be an absolute URL"))?;
    if parsed.scheme().is_empty() || parsed.host_str().is_none() {
        return Err(anyhow!("{name} must be an absolute URL"));
    }
    Ok(parsed)
}

fn env(name: &str, fallback: &str) -> String {
    std::env::var(name).unwrap_or_else(|_| fallback.to_string())
}

fn optional_env(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|value| !value.is_empty())
}

fn trim_trailing_slash(value: &str) -> String {
    value.trim_end_matches('/').to_string()
}

#[cfg(test)]
mod tests {
    use super::Config;

    #[test]
    fn redirect_uri_uses_public_url_callback_path() {
        let config = Config {
            port: "8000".to_string(),
            backend_url: "http://backend:8080".parse().unwrap(),
            frontend_url: None,
            spa_root: None,
            redis_url: "redis://localhost:6379".to_string(),
            oidc_issuer_uri: "http://localhost:8180/realms/stackverse".to_string(),
            oidc_internal_issuer_uri: "http://keycloak:8080/realms/stackverse".to_string(),
            oidc_client_id: "stackverse-gateway".to_string(),
            oidc_client_secret: "stackverse-secret".to_string(),
            public_url: "https://example.test/app?x=1".parse().unwrap(),
            log_level: "info".to_string(),
            log_format: "json".to_string(),
            otel_disabled: true,
        };

        assert_eq!(config.redirect_uri(), "https://example.test/auth/callback");
        assert!(config.cookies_secure());
    }

    #[test]
    fn url_for_logs_removes_credentials_query_and_fragment() {
        let url = "https://api-user:api-password@backend.example/base?token=secret#fragment"
            .parse()
            .unwrap();

        assert_eq!(Config::url_for_logs(&url), "https://backend.example/base");
    }
}
