defmodule StackverseBackendWeb.AccountController do
  use Phoenix.Controller, formats: [:json]

  alias StackverseBackend.{Accounts, Problem, Validation}
  alias StackverseBackendWeb.{AdminJSON, PageJSON}
  alias StackverseBackendWeb.ControllerSupport, as: Support

  def list(conn, _params) do
    with {:ok, _caller} <- Support.require_role(conn, "admin"),
         {:ok, %{page: page, size: size}} <- Support.paging(conn),
         {:ok, q} <- Support.optional_single(conn, "q"),
         :ok <- Support.max_length(conn, q, 100, "q"),
         {:ok, status} <- Support.optional_enum(conn, "status", ~w[active blocked]) do
      result = Accounts.list(q, status, page, size)
      items = Enum.map(result.items, &AdminJSON.user/1)
      json(conn, PageJSON.response(items, page, size, result.total))
    else
      {:error, conn} -> conn
    end
  end

  def get(conn, %{"username" => username}) do
    with {:ok, _caller} <- Support.require_role(conn, "admin") do
      case Accounts.get(username) do
        nil -> Problem.send(conn, 404, "Not Found")
        account -> json(conn, AdminJSON.user(account))
      end
    else
      {:error, conn} -> conn
    end
  end

  def set_status(conn, %{"username" => username}) do
    with {:ok, caller} <- Support.require_role(conn, "admin"),
         {:ok, input} <-
           Support.validate(conn, Validation.validate_user_status(conn.body_params)) do
      cond do
        input.status == "blocked" and username == caller.username ->
          Problem.send(conn, 409, "Conflict", "Admins cannot block themselves.")

        true ->
          case Accounts.set_status(caller.username, username, input.status, input.reason) do
            {:ok, account} -> json(conn, AdminJSON.user(account))
            {:error, :not_found} -> Problem.send(conn, 404, "Not Found")
          end
      end
    else
      {:error, conn} -> conn
    end
  end
end
