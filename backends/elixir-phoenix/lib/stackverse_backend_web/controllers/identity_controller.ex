defmodule StackverseBackendWeb.IdentityController do
  use Phoenix.Controller, formats: [:json]

  alias StackverseBackend.{Auth, Problem}
  alias StackverseBackendWeb.ControllerSupport, as: Support

  def me(conn, _params) do
    with {:ok, caller} <- Support.require_caller(conn) do
      caller
      |> Map.take([:username, :name, :email])
      |> Map.put(:roles, Auth.app_roles(caller.roles))
      |> then(&json(conn, Problem.omit_nil(&1)))
    else
      {:error, conn} -> conn
    end
  end
end
