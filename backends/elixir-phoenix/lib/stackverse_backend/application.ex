defmodule StackverseBackend.Application do
  @moduledoc false

  use Application

  alias StackverseBackend.{Log, Repo, Seed}
  alias StackverseBackendWeb.Endpoint

  @impl true
  def start(_type, _args) do
    configure_logger()

    children =
      if Application.get_env(:stackverse_backend, :start_repo, true) do
        [Repo]
      else
        []
      end

    {:ok, supervisor} =
      Supervisor.start_link(children, strategy: :one_for_one, name: StackverseBackend.Supervisor)

    if Application.get_env(:stackverse_backend, :start_runtime_tasks, true) do
      migrate()
      Seed.import!()
    end

    if Application.get_env(:stackverse_backend, Endpoint)[:server] do
      {:ok, _pid} = Supervisor.start_child(supervisor, Endpoint)
    end

    settings = Application.get_env(:stackverse_backend, :settings, [])

    if settings != [] do
      Log.event(
        :info,
        "application_start",
        "success",
        "Stackverse backend (elixir-phoenix) listening",
        port: settings[:port],
        db_host: settings[:db_host],
        db_port: settings[:db_port],
        db_name: settings[:db_name],
        oidc_issuer: settings[:oidc_issuer_uri],
        oidc_jwks_uri: settings[:oidc_jwks_uri] || "(via OIDC discovery)",
        seed_messages_dir: settings[:seed_messages_dir],
        log_level: settings[:log_level],
        log_format: settings[:log_format],
        otel_enabled: settings[:otel_enabled]
      )
    end

    {:ok, supervisor}
  end

  @impl true
  def prep_stop(state) do
    Log.event(:info, "application_stop", "success", "Stackverse backend stopping")
    state
  end

  defp migrate do
    path = Application.app_dir(:stackverse_backend, "priv/repo/migrations")

    Repo
    |> Ecto.Migrator.run(path, :up, all: true)
    |> Enum.each(fn version ->
      Log.event(:info, "db_migration_applied", "success", "Applied migration #{version}",
        migration: version
      )
    end)
  end

  defp configure_logger do
    settings = Application.get_env(:stackverse_backend, :settings, [])
    level = settings[:log_level] || "info"
    format = settings[:log_format] || "json"

    Logger.configure(level: logger_level(level))

    formatter =
      if format == "json" do
        Logger.Formatter.new(
          format: {StackverseBackend.JsonLogFormatter, :format},
          metadata: :all
        )
      else
        Logger.Formatter.new(
          format: "$time [$level] $message $metadata\n",
          metadata: :all
        )
      end

    :ok = :logger.update_handler_config(:default, :formatter, formatter)
  end

  defp logger_level("debug"), do: :debug
  defp logger_level("info"), do: :info
  defp logger_level("warn"), do: :warning
  defp logger_level("warning"), do: :warning
  defp logger_level("error"), do: :error
  defp logger_level(_level), do: :info
end
