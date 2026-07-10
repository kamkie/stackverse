defmodule StackverseBackendWeb.MessageController do
  use Phoenix.Controller, formats: [:json]

  import Plug.Conn

  alias StackverseBackend.{I18n, Messages, Problem, Validation}
  alias StackverseBackendWeb.{MessageJSON, PageJSON}
  alias StackverseBackendWeb.ControllerSupport, as: Support

  def list(conn, _params) do
    with {:ok, %{page: page, size: size}} <- Support.paging(conn),
         {:ok, key} <- Support.optional_single(conn, "key"),
         {:ok, language} <- Support.optional_single(conn, "language"),
         {:ok, q} <- Support.optional_single(conn, "q"),
         :ok <- Support.max_length(conn, q, 200, "q") do
      result = Messages.list(key, language, q, page, size)
      items = Enum.map(result.items, &MessageJSON.response/1)
      Support.etag_json(conn, 200, PageJSON.response(items, page, size, result.total))
    else
      {:error, conn} -> conn
    end
  end

  def bundle(conn, _params) do
    language =
      conn.query_string
      |> Problem.query_values("lang")
      |> List.first()
      |> I18n.resolve_language(conn |> get_req_header("accept-language") |> List.first())

    conn
    |> put_resp_header("content-language", language)
    |> Support.etag_json(200, %{language: language, messages: I18n.bundle(language)})
  end

  def get(conn, %{"id" => raw_id}) do
    with {:ok, id} <- Support.path_uuid(conn, raw_id) do
      case Messages.get(id) do
        nil -> Problem.send(conn, 404, "Not Found")
        message -> Support.etag_json(conn, 200, MessageJSON.response(message))
      end
    else
      {:error, conn} -> conn
    end
  end

  def create(conn, _params) do
    with {:ok, caller} <- Support.require_role(conn, "admin"),
         {:ok, input} <- Support.validate(conn, Validation.validate_message(conn.body_params)) do
      case Messages.create(caller.username, input) do
        {:ok, message} ->
          conn
          |> put_status(201)
          |> put_resp_header("location", "/api/v1/messages/#{message["id"]}")
          |> json(MessageJSON.response(message))

        {:error, :duplicate_message} ->
          duplicate_problem(conn)
      end
    else
      {:error, conn} -> conn
    end
  end

  def update(conn, %{"id" => raw_id}) do
    with {:ok, caller} <- Support.require_role(conn, "admin"),
         {:ok, id} <- Support.path_uuid(conn, raw_id),
         {:ok, input} <- Support.validate(conn, Validation.validate_message(conn.body_params)) do
      case Messages.update(caller.username, id, input) do
        {:ok, message} -> json(conn, MessageJSON.response(message))
        {:error, :not_found} -> Problem.send(conn, 404, "Not Found")
        {:error, :duplicate_message} -> duplicate_problem(conn)
      end
    else
      {:error, conn} -> conn
    end
  end

  def delete(conn, %{"id" => raw_id}) do
    with {:ok, caller} <- Support.require_role(conn, "admin"),
         {:ok, id} <- Support.path_uuid(conn, raw_id) do
      case Messages.delete(caller.username, id) do
        {:ok, _message} -> send_resp(conn, 204, "")
        {:error, :not_found} -> Problem.send(conn, 404, "Not Found")
      end
    else
      {:error, conn} -> conn
    end
  end

  defp duplicate_problem(conn) do
    Problem.send(conn, 409, "Conflict", "A message with this key and language already exists.")
  end
end
