import Config

config :stackverse_backend,
  ecto_repos: [StackverseBackend.Repo],
  start_repo: config_env() != :test,
  start_runtime_tasks: config_env() != :test

config :stackverse_backend, StackverseBackendWeb.Endpoint,
  adapter: Bandit.PhoenixAdapter,
  render_errors: [
    formats: [json: StackverseBackendWeb.ErrorJSON],
    layout: false
  ],
  pubsub_server: false,
  server: false

config :logger, :console,
  format: "[$level] $message\n",
  metadata: :all

config :phoenix, :json_library, Jason

import_config "#{config_env()}.exs"
