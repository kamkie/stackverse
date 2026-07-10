defmodule StackverseBackendWeb.HealthController do
  use Phoenix.Controller, formats: [:json]

  alias StackverseBackend.{Problem, Repo}

  def healthz(conn, _params), do: send_resp(conn, 200, "")

  def readyz(conn, _params) do
    case Repo.query("select 1", []) do
      {:ok, _result} -> send_resp(conn, 200, "")
      {:error, _error} -> send_resp(conn, 503, "")
    end
  end

  def not_found(conn, _params), do: Problem.send(conn, 404, "Not Found")
end
