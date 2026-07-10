defmodule StackverseBackendWeb.StatsController do
  use Phoenix.Controller, formats: [:json]

  alias StackverseBackend.Stats
  alias StackverseBackendWeb.ControllerSupport, as: Support

  def get(conn, _params) do
    with {:ok, _caller} <- Support.require_role(conn, "moderator") do
      Support.etag_json(conn, 200, Stats.get())
    else
      {:error, conn} -> conn
    end
  end
end
