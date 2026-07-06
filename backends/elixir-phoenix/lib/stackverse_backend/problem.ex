defmodule StackverseBackend.Problem do
  @moduledoc false

  import Plug.Conn
  import Phoenix.Controller, only: [json: 2]

  alias StackverseBackend.{I18n, Log}

  def send(conn, status, title, detail \\ nil, errors \\ nil) do
    body =
      %{
        type: "about:blank",
        title: title,
        status: status,
        detail: detail,
        errors: errors
      }
      |> omit_nil()

    conn
    |> put_status(status)
    |> put_resp_content_type("application/problem+json")
    |> json(body)
  end

  def validation(conn, violations) do
    Log.event(:info, "input_validation_failed", "failure", "Request validation failed",
      error_code: "validation_failed",
      fields: violations |> Enum.map(& &1.field) |> Enum.join(",")
    )

    language = request_language(conn)

    errors =
      Enum.map(violations, fn violation ->
        %{
          field: violation.field,
          messageKey: violation.message_key,
          message: I18n.localize(violation.message_key, language)
        }
      end)

    send(conn, 400, "Bad Request", "Request validation failed.", errors)
  end

  def request_language(conn) do
    lang =
      conn.query_string
      |> query_values("lang")
      |> List.first()

    accept_language =
      conn
      |> get_req_header("accept-language")
      |> List.first()

    I18n.resolve_language(lang, accept_language)
  end

  def omit_nil(map) do
    map
    |> Enum.reject(fn {_key, value} -> is_nil(value) end)
    |> Map.new()
  end

  def query_values(query_string, key) do
    query_string
    |> String.split("&", trim: true)
    |> Enum.flat_map(fn pair ->
      [raw_key | rest] = String.split(pair, "=", parts: 2)
      raw_value = Enum.at(rest, 0, "")

      if form_decode(raw_key) == key do
        [form_decode(raw_value)]
      else
        []
      end
    end)
  end

  defp form_decode(value) do
    value
    |> String.replace("+", " ")
    |> URI.decode_www_form()
  rescue
    _ -> value
  end
end
