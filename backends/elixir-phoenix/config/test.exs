import Config

database_tests? = System.get_env("STACKVERSE_DB_TESTS") == "true"

config :stackverse_backend,
  start_repo: database_tests?,
  start_runtime_tasks: false

config :stackverse_backend, StackverseBackend.Repo,
  username: System.get_env("DB_USER", "stackverse"),
  password: System.get_env("DB_PASSWORD", "stackverse"),
  hostname: System.get_env("DB_HOST", "localhost"),
  database: System.get_env("DB_NAME", "stackverse_test"),
  port: String.to_integer(System.get_env("DB_PORT", "5432")),
  pool: Ecto.Adapters.SQL.Sandbox,
  pool_size: 5

config :stackverse_backend, StackverseBackendWeb.Endpoint, server: false

config :logger, level: :warning
