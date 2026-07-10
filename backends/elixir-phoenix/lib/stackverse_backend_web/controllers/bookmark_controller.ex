defmodule StackverseBackendWeb.BookmarkController do
  use Phoenix.Controller, formats: [:json]

  alias StackverseBackend.{Bookmarks, Cursor, Problem, Validation}
  alias StackverseBackendWeb.{BookmarkJSON, PageJSON}
  alias StackverseBackendWeb.ControllerSupport, as: Support

  def list_v1(conn, _params) do
    conn = Support.v1_headers(conn)

    with {:ok, %{page: page, size: size}} <- Support.paging(conn),
         {:ok, filters} <- filters(conn) do
      result = Bookmarks.list(filters, page, size)

      items = Enum.map(result.items, &BookmarkJSON.response/1)
      json(conn, PageJSON.response(items, page, size, result.total))
    else
      {:error, conn} -> conn
    end
  end

  def list_v2(conn, _params) do
    with {:ok, %{size: size}} <- Support.paging(conn),
         {:ok, filters} <- filters(conn),
         {:ok, cursor} <- Support.cursor_param(conn) do
      rows = Bookmarks.list_cursor(filters, cursor, size)
      items = Enum.take(rows, size)

      body =
        %{
          items: Enum.map(items, &BookmarkJSON.response/1),
          nextCursor:
            if(length(rows) > size,
              do:
                items
                |> List.last()
                |> then(&Cursor.encode(%{created_at: &1["created_at"], id: &1["id"]})),
              else: nil
            )
        }
        |> Problem.omit_nil()

      json(conn, body)
    else
      {:error, conn} -> conn
    end
  end

  def create(conn, _params) do
    with {:ok, caller} <- Support.require_caller(conn),
         {:ok, input} <- Support.validate(conn, Validation.validate_bookmark(conn.body_params)) do
      {id, bookmark} = Bookmarks.create(caller.username, input)

      conn
      |> put_status(201)
      |> put_resp_header("location", "/api/v1/bookmarks/#{id}")
      |> json(BookmarkJSON.response(bookmark))
    else
      {:error, conn} -> conn
    end
  end

  def get(conn, %{"id" => raw_id}) do
    with {:ok, id} <- Support.path_uuid(conn, raw_id) do
      caller = conn.assigns[:caller]
      bookmark = Bookmarks.get(id)

      if bookmark && Bookmarks.visible_to?(bookmark, caller && caller.username) do
        json(conn, BookmarkJSON.response(bookmark))
      else
        Problem.send(conn, 404, "Not Found")
      end
    else
      {:error, conn} -> conn
    end
  end

  def update(conn, %{"id" => raw_id}) do
    with {:ok, caller} <- Support.require_caller(conn),
         {:ok, id} <- Support.path_uuid(conn, raw_id),
         {:ok, input} <- Support.validate(conn, Validation.validate_bookmark(conn.body_params)) do
      case Bookmarks.update(caller.username, id, input) do
        {:ok, bookmark} -> json(conn, BookmarkJSON.response(bookmark))
        {:error, :not_found} -> Problem.send(conn, 404, "Not Found")
        {:error, {:conflict, detail}} -> Problem.send(conn, 409, "Conflict", detail)
      end
    else
      {:error, conn} -> conn
    end
  end

  def delete(conn, %{"id" => raw_id}) do
    with {:ok, caller} <- Support.require_caller(conn),
         {:ok, id} <- Support.path_uuid(conn, raw_id) do
      case Bookmarks.delete(caller.username, id) do
        :not_found -> Problem.send(conn, 404, "Not Found")
        :ok -> send_resp(conn, 204, "")
      end
    else
      {:error, conn} -> conn
    end
  end

  def list_tags(conn, _params) do
    with {:ok, caller} <- Support.require_caller(conn) do
      tags =
        caller.username
        |> Bookmarks.list_tags()
        |> Enum.map(&%{tag: &1["tag"], count: &1["count"]})

      json(conn, %{tags: tags})
    else
      {:error, conn} -> conn
    end
  end

  defp filters(conn) do
    with {:ok, q} <- Support.optional_single(conn, "q"),
         :ok <- Support.max_length(conn, q, 200, "q"),
         {:ok, visibility} <- Support.optional_enum(conn, "visibility", ~w[private public]),
         {:ok, tags} <-
           Support.validate(
             conn,
             Validation.validate_query_tags(Problem.query_values(conn.query_string, "tag"))
           ) do
      cond do
        visibility == "public" ->
          {:ok, %{caller: nil, visibility: visibility, tags: tags, q: q || ""}}

        conn.assigns[:caller] ->
          {:ok,
           %{caller: conn.assigns.caller.username, visibility: visibility, tags: tags, q: q || ""}}

        true ->
          {:error, Problem.send(conn, 401, "Unauthorized", "Authentication is required.")}
      end
    end
  end
end
