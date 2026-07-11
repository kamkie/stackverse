defmodule StackverseBackend.TestHTTP do
  @moduledoc false

  import Plug.Conn

  alias StackverseBackend.TestAuth
  alias StackverseBackendWeb.Endpoint

  def auth_conn(key, username, roles \\ []) do
    Phoenix.ConnTest.build_conn()
    |> put_req_header("authorization", "Bearer #{TestAuth.token(key, username, roles)}")
  end

  def json_request(conn, method, path, body) when method in [:post, :put] do
    conn
    |> put_req_header("content-type", "application/json")
    |> Phoenix.ConnTest.dispatch(Endpoint, method, path, Jason.encode!(body))
  end
end
