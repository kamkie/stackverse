defmodule StackverseBackendWeb.ControllerSupport do
  @moduledoc false

  import Plug.Conn

  alias StackverseBackend.{Auth, Cursor, Log, Problem}

  @v1_deprecation "@1782864000"
  @v1_sunset "Thu, 01 Jul 2027 00:00:00 GMT"
  @v1_successor "</api/v2/bookmarks>; rel=\"successor-version\""

  def v1_headers(conn) do
    conn
    |> put_resp_header("deprecation", @v1_deprecation)
    |> put_resp_header("sunset", @v1_sunset)
    |> put_resp_header("link", @v1_successor)
  end

  def require_caller(conn) do
    case conn.assigns[:caller] do
      nil -> {:error, Problem.send(conn, 401, "Unauthorized", "Authentication is required.")}
      caller -> {:ok, caller}
    end
  end

  def require_role(conn, role) do
    with {:ok, caller} <- require_caller(conn) do
      if Auth.has_role?(caller, role) do
        {:ok, caller}
      else
        Log.event(:info, "authz_denied", "denied", "Denied a request lacking the required role",
          actor: caller.username
        )

        {:error,
         Problem.send(
           conn,
           403,
           "Forbidden",
           "You do not have the role required for this operation."
         )}
      end
    end
  end

  def validate(_conn, {:ok, value}), do: {:ok, value}
  def validate(conn, {:error, errors}), do: {:error, Problem.validation(conn, errors)}

  def paging(conn) do
    with {:ok, page_raw} <- optional_single(conn, "page"),
         {:ok, size_raw} <- optional_single(conn, "size"),
         {:ok, page} <- parse_int(conn, page_raw, 0, "page"),
         {:ok, size} <- parse_int(conn, size_raw, 20, "size") do
      cond do
        page < 0 ->
          {:error, Problem.send(conn, 400, "Bad Request", "page must not be negative")}

        size < 1 or size > 100 ->
          {:error, Problem.send(conn, 400, "Bad Request", "size must be between 1 and 100")}

        true ->
          {:ok, %{page: page, size: size}}
      end
    end
  end

  def optional_enum(conn, name, values) do
    case optional_single(conn, name) do
      {:ok, nil} ->
        {:ok, nil}

      {:ok, value} ->
        if value in values do
          {:ok, value}
        else
          {:error, Problem.send(conn, 400, "Bad Request", "unknown #{name}: #{value}")}
        end

      {:error, conn} ->
        {:error, conn}
    end
  end

  def optional_single(conn, name) do
    case Problem.query_values(conn.query_string, name) do
      [] -> {:ok, nil}
      [value] -> {:ok, value}
      _ -> {:error, Problem.send(conn, 400, "Bad Request", "#{name} must not be repeated")}
    end
  end

  def max_length(_conn, nil, _max, _name), do: :ok

  def max_length(conn, value, max, name) do
    if String.length(value) <= max do
      :ok
    else
      {:error,
       Problem.send(conn, 400, "Bad Request", "#{name} must be at most #{max} characters")}
    end
  end

  def datetime_param(conn, name) do
    case optional_single(conn, name) do
      {:ok, nil} ->
        {:ok, nil}

      {:ok, raw} ->
        case DateTime.from_iso8601(raw) do
          {:ok, value, _offset} ->
            {:ok, value}

          _ ->
            {:error,
             Problem.send(conn, 400, "Bad Request", "#{name} must be an RFC 3339 date-time")}
        end

      {:error, conn} ->
        {:error, conn}
    end
  end

  def path_uuid(conn, value) do
    if Regex.match?(~r/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i, value) do
      {:ok, String.downcase(value)}
    else
      {:error, Problem.send(conn, 404, "Not Found")}
    end
  end

  def cursor_param(conn) do
    case optional_single(conn, "cursor") do
      {:ok, nil} ->
        {:ok, nil}

      {:ok, raw} ->
        case Cursor.decode(raw) do
          {:ok, cursor} ->
            {:ok, cursor}

          :error ->
            {:error,
             Problem.send(conn, 400, "Bad Request", "The cursor is malformed or unresolvable.")}
        end

      {:error, conn} ->
        {:error, conn}
    end
  end

  def etag_json(conn, status, payload) do
    body = Jason.encode!(payload)
    etag = "\"" <> Base.url_encode64(:crypto.hash(:sha256, body), padding: false) <> "\""

    conn =
      conn
      |> put_resp_header("etag", etag)
      |> put_resp_header("cache-control", "no-cache")

    if etag_matches?(conn, etag) do
      send_resp(conn, 304, "")
    else
      conn
      |> put_status(status)
      |> put_resp_content_type("application/json")
      |> send_resp(status, body)
    end
  end

  def optional_body_string(body, key) when is_map(body) do
    case Map.get(body, key) do
      value when is_binary(value) -> value
      _ -> nil
    end
  end

  def optional_body_string(_body, _key), do: nil

  def required_status(conn, body) when is_map(body) do
    case Map.get(body, "status") do
      value when value in ["active", "blocked"] -> {:ok, value}
      _value -> {:error, Problem.send(conn, 400, "Bad Request", "status is required")}
    end
  end

  def required_status(conn, _body),
    do: {:error, Problem.send(conn, 400, "Bad Request", "status is required")}

  defp parse_int(_conn, nil, fallback, _name), do: {:ok, fallback}
  defp parse_int(_conn, "", fallback, _name), do: {:ok, fallback}

  defp parse_int(conn, value, _fallback, name) do
    case Integer.parse(value) do
      {int, ""} -> {:ok, int}
      _ -> {:error, Problem.send(conn, 400, "Bad Request", "#{name} must be an integer")}
    end
  end

  defp etag_matches?(conn, etag) do
    conn
    |> get_req_header("if-none-match")
    |> Enum.flat_map(&String.split(&1, ","))
    |> Enum.map(&String.trim/1)
    |> Enum.any?(&(&1 == etag))
  end
end
