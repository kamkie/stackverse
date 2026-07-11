mod support;

use std::io::Read;
use std::net::TcpListener;
use std::process::{Child, Command, Output, Stdio};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use support::{FakeRedis, RecordingHttpServer};

const PROCESS_TIMEOUT: Duration = Duration::from_secs(10);
const POLL_INTERVAL: Duration = Duration::from_millis(25);

#[tokio::test]
async fn startup_uses_env_config_emits_json_lifecycle_event_and_redacts_secrets() {
    let redis = FakeRedis::spawn(Some("redis-password-must-not-leak")).await;
    let port = unused_port();
    let mut command = gateway_command(&redis.url);
    command
        .env("PORT", port.to_string())
        .env(
            "BACKEND_URL",
            "http://backend-user:backend-password-must-not-leak@backend.example:8080/base?backend-token-must-not-leak=yes",
        )
        .env(
            "FRONTEND_URL",
            "http://frontend-user:frontend-password-must-not-leak@frontend.example:5173/?frontend-token-must-not-leak=yes",
        )
        .env("SPA_ROOT", "unused-spa-root")
        .env(
            "OIDC_ISSUER_URI",
            "https://identity.example/realms/stackverse/",
        )
        .env(
            "OIDC_INTERNAL_ISSUER_URI",
            "http://keycloak.internal:8080/realms/stackverse/",
        )
        .env("OIDC_CLIENT_ID", "test-gateway")
        .env("OIDC_CLIENT_SECRET", "oidc-secret-must-not-leak")
        .env(
            "PUBLIC_URL",
            "https://public-user:public-password-must-not-leak@gateway.example:8443/base?public-token-must-not-leak=yes",
        )
        .env("LOG_LEVEL", "[")
        .env("LOG_FORMAT", "json")
        .env("OTEL_SDK_DISABLED", "true");

    let mut process = RunningProcess::spawn(command);
    let healthy = wait_for_health(port).await;
    let output = process.finish(true);
    let logs = combined_output(&output);
    assert!(healthy, "gateway did not become healthy:\n{logs}");
    assert!(logs.contains("\"event\":\"application_start\""), "{logs}");
    assert!(logs.contains("Rust Axum gateway listening"), "{logs}");
    assert!(logs.contains("backend.example:8080/base"), "{logs}");
    assert!(logs.contains("frontend.example:5173"), "{logs}");
    assert!(logs.contains("gateway.example:8443/base"), "{logs}");
    for secret in [
        "redis-password-must-not-leak",
        "oidc-secret-must-not-leak",
        "backend-user",
        "backend-password-must-not-leak",
        "backend-token-must-not-leak",
        "frontend-user",
        "frontend-password-must-not-leak",
        "frontend-token-must-not-leak",
        "public-user",
        "public-password-must-not-leak",
        "public-token-must-not-leak",
    ] {
        assert!(
            !logs.contains(secret),
            "secret leaked in logs: {secret}\n{logs}"
        );
    }
    assert!(
        logs.contains(
            redis
                .url
                .split('@')
                .next_back()
                .unwrap()
                .trim_end_matches("/0")
        ),
        "{logs}"
    );
}

#[tokio::test]
async fn telemetry_enabled_exports_logs_and_text_format_remains_human_readable() {
    let redis = FakeRedis::spawn(None).await;
    let collector = RecordingHttpServer::spawn().await;
    let port = unused_port();
    let mut command = gateway_command(&redis.url);
    command
        .env("TOKIO_WORKER_THREADS", "1")
        .env("PORT", port.to_string())
        .env("LOG_LEVEL", "info")
        .env("LOG_FORMAT", "text")
        .env("OTEL_SDK_DISABLED", "false")
        .env("OTEL_EXPORTER_OTLP_ENDPOINT", &collector.endpoint)
        .env(
            "OTEL_EXPORTER_OTLP_LOGS_ENDPOINT",
            format!("{}/v1/logs", collector.endpoint),
        )
        .env(
            "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
            format!("{}/v1/traces", collector.endpoint),
        );

    let mut process = RunningProcess::spawn(command);
    let healthy = wait_for_health(port).await;
    let exported = healthy && wait_for_path(&collector, "/v1/logs").await;
    let output = process.finish(true);
    let logs = combined_output(&output);
    assert!(
        healthy,
        "single-worker gateway did not become healthy:\n{logs}"
    );
    assert!(logs.contains("Rust Axum gateway listening"), "{logs}");
    assert!(!logs.contains("\"fields\""), "{logs}");
    let paths = collector.paths().await;
    assert!(exported, "OTLP log was not exported: {paths:?}\n{logs}");
}

#[tokio::test]
async fn bind_failure_never_reports_a_successful_start_in_either_logging_mode() {
    let redis = FakeRedis::spawn(None).await;
    let collector = RecordingHttpServer::spawn().await;
    let occupied = TcpListener::bind(("0.0.0.0", 0)).unwrap();
    let port = occupied.local_addr().unwrap().port();

    for (format, otel_disabled) in [("json", "true"), ("text", "false")] {
        let mut command = gateway_command(&redis.url);
        command
            .env("PORT", port.to_string())
            .env("LOG_FORMAT", format)
            .env("OTEL_SDK_DISABLED", otel_disabled)
            .env("OTEL_EXPORTER_OTLP_ENDPOINT", &collector.endpoint)
            .env(
                "OTEL_EXPORTER_OTLP_LOGS_ENDPOINT",
                format!("{}/v1/logs", collector.endpoint),
            )
            .env(
                "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
                format!("{}/v1/traces", collector.endpoint),
            );

        let output = run(command).await;
        assert!(!output.status.success());
        let logs = combined_output(&output);
        assert!(!logs.contains("\"event\":\"application_start\""), "{logs}");
        assert!(!logs.contains("Rust Axum gateway listening"), "{logs}");
    }
}

#[tokio::test]
async fn invalid_absolute_url_configuration_fails_before_dependencies_start() {
    for (name, value) in [
        ("BACKEND_URL", "/relative/backend"),
        ("PUBLIC_URL", "mailto:gateway@example.com"),
    ] {
        let mut command = base_command();
        command.env(name, value);
        let output = run(command).await;
        assert!(!output.status.success());
        let logs = combined_output(&output);
        assert!(
            logs.contains(&format!("{name} must be an absolute URL")),
            "{logs}"
        );
    }
}

fn gateway_command(redis_url: &str) -> Command {
    let mut command = base_command();
    command
        .env("REDIS_URL", redis_url)
        .env("BACKEND_URL", "http://127.0.0.1:1")
        .env("PUBLIC_URL", "http://localhost:8000")
        .env("OIDC_ISSUER_URI", "http://localhost:8180/realms/stackverse")
        .env("OIDC_CLIENT_ID", "stackverse-gateway")
        .env("OIDC_CLIENT_SECRET", "test-only-secret");
    command
}

fn base_command() -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_stackverse-gateway-rust"));
    for name in [
        "PORT",
        "REDIS_URL",
        "BACKEND_URL",
        "FRONTEND_URL",
        "SPA_ROOT",
        "OIDC_ISSUER_URI",
        "OIDC_INTERNAL_ISSUER_URI",
        "OIDC_CLIENT_ID",
        "OIDC_CLIENT_SECRET",
        "PUBLIC_URL",
        "LOG_LEVEL",
        "LOG_FORMAT",
        "OTEL_SDK_DISABLED",
        "TOKIO_WORKER_THREADS",
    ] {
        command.env_remove(name);
    }
    for (name, _) in std::env::vars_os() {
        let upper = name.to_string_lossy().to_ascii_uppercase();
        if upper.starts_with("OTEL_")
            || matches!(
                upper.as_str(),
                "HTTP_PROXY" | "HTTPS_PROXY" | "ALL_PROXY" | "NO_PROXY"
            )
        {
            command.env_remove(name);
        }
    }
    command
}

async fn run(command: Command) -> Output {
    let mut process = RunningProcess::spawn(command);
    let completed = tokio::time::timeout(PROCESS_TIMEOUT, async {
        loop {
            if process.has_exited() {
                break;
            }
            tokio::time::sleep(POLL_INTERVAL).await;
        }
    })
    .await
    .is_ok();
    let output = process.finish(!completed);
    assert!(
        completed,
        "gateway process timed out:\n{}",
        combined_output(&output)
    );
    output
}

async fn wait_for_health(port: u16) -> bool {
    stackverse_gateway_rust::install_tls_provider();
    let client = reqwest::Client::builder()
        .no_proxy()
        .timeout(Duration::from_millis(500))
        .build()
        .unwrap();
    tokio::time::timeout(PROCESS_TIMEOUT, async {
        loop {
            if let Ok(response) = client
                .get(format!("http://127.0.0.1:{port}/healthz"))
                .send()
                .await
                && response.status().is_success()
            {
                return true;
            }
            tokio::time::sleep(POLL_INTERVAL).await;
        }
    })
    .await
    .unwrap_or(false)
}

async fn wait_for_path(server: &RecordingHttpServer, expected: &str) -> bool {
    tokio::time::timeout(PROCESS_TIMEOUT, async {
        loop {
            if server.paths().await.iter().any(|path| path == expected) {
                return true;
            }
            tokio::time::sleep(POLL_INTERVAL).await;
        }
    })
    .await
    .unwrap_or(false)
}

fn unused_port() -> u16 {
    TcpListener::bind(("127.0.0.1", 0))
        .unwrap()
        .local_addr()
        .unwrap()
        .port()
}

struct RunningProcess {
    child: Option<Child>,
    stdout: Option<JoinHandle<Vec<u8>>>,
    stderr: Option<JoinHandle<Vec<u8>>>,
}

impl RunningProcess {
    fn spawn(mut command: Command) -> Self {
        command.stdout(Stdio::piped()).stderr(Stdio::piped());
        let mut child = command.spawn().unwrap();
        let stdout = drain(child.stdout.take().unwrap());
        let stderr = drain(child.stderr.take().unwrap());
        Self {
            child: Some(child),
            stdout: Some(stdout),
            stderr: Some(stderr),
        }
    }

    fn has_exited(&mut self) -> bool {
        self.child.as_mut().unwrap().try_wait().unwrap().is_some()
    }

    fn finish(&mut self, terminate: bool) -> Output {
        let mut child = self.child.take().unwrap();
        if terminate && child.try_wait().unwrap().is_none() {
            let _ = child.kill();
        }
        let status = child.wait().unwrap();
        let stdout = self.stdout.take().unwrap().join().unwrap();
        let stderr = self.stderr.take().unwrap().join().unwrap();
        Output {
            status,
            stdout,
            stderr,
        }
    }
}

impl Drop for RunningProcess {
    fn drop(&mut self) {
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
        if let Some(stdout) = self.stdout.take() {
            let _ = stdout.join();
        }
        if let Some(stderr) = self.stderr.take() {
            let _ = stderr.join();
        }
    }
}

fn drain<R: Read + Send + 'static>(mut reader: R) -> JoinHandle<Vec<u8>> {
    thread::spawn(move || {
        let mut output = Vec::new();
        reader.read_to_end(&mut output).unwrap();
        output
    })
}

fn combined_output(output: &Output) -> String {
    format!(
        "{}{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    )
}
