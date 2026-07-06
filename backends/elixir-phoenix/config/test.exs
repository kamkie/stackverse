import Config

config :stackverse_backend,
  start_repo: false,
  start_runtime_tasks: false

config :stackverse_backend, StackverseBackendWeb.Endpoint, server: false

config :logger, level: :warning
