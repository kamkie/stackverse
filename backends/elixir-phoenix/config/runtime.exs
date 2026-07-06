import Config

if config_env() != :test do
  env = fn name, fallback ->
    case System.get_env(name) do
      nil ->
        fallback

      value ->
        value = String.trim(value)
        if value == "", do: fallback, else: value
    end
  end

  int_env = fn name, fallback ->
    value = env.(name, Integer.to_string(fallback))

    case Integer.parse(value) do
      {int, ""} -> int
      _ -> raise "#{name} must be an integer"
    end
  end

  port = int_env.("PORT", 8080)

  config :stackverse_backend, StackverseBackend.Repo,
    username: env.("DB_USER", "stackverse"),
    password: env.("DB_PASSWORD", "stackverse"),
    hostname: env.("DB_HOST", "localhost"),
    database: env.("DB_NAME", "stackverse"),
    port: int_env.("DB_PORT", 5432),
    pool_size: int_env.("DB_POOL_SIZE", 10),
    migration_lock: :pg_advisory_lock

  config :stackverse_backend, StackverseBackendWeb.Endpoint,
    http: [ip: {0, 0, 0, 0}, port: port],
    secret_key_base:
      env.(
        "SECRET_KEY_BASE",
        "stackverse-elixir-phoenix-local-secret-key-base-change-me-000000000000"
      ),
    server: true

  config :stackverse_backend, :settings,
    port: port,
    db_host: env.("DB_HOST", "localhost"),
    db_port: int_env.("DB_PORT", 5432),
    db_name: env.("DB_NAME", "stackverse"),
    oidc_issuer_uri: env.("OIDC_ISSUER_URI", "http://localhost:8180/realms/stackverse"),
    oidc_jwks_uri: System.get_env("OIDC_JWKS_URI"),
    oidc_audience: env.("OIDC_AUDIENCE", "stackverse-api"),
    seed_messages_dir:
      env.(
        "SEED_MESSAGES_DIR",
        Path.expand("../../../spec/messages", __DIR__)
      ),
    log_level: String.downcase(env.("LOG_LEVEL", "info")),
    log_format: String.downcase(env.("LOG_FORMAT", "json")),
    otel_enabled: String.downcase(env.("OTEL_SDK_DISABLED", "true")) == "false"
end
