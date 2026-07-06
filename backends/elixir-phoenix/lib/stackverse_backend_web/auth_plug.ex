defmodule StackverseBackendWeb.AuthPlug do
  @moduledoc false

  import Plug.Conn

  alias StackverseBackend.{Auth, I18n, Log, Problem}

  def init(opts), do: opts

  def call(conn, _opts) do
    conn = assign(conn, :caller, nil)

    case get_req_header(conn, "authorization") do
      ["Bearer " <> token] ->
        authenticate(conn, token)

      [] ->
        conn

      _ ->
        reject_invalid(conn)
    end
  end

  defp authenticate(conn, token) do
    case Auth.verify_bearer(token) do
      {:ok, caller} ->
        case Auth.record_seen(caller.username) do
          "blocked" ->
            Log.event(
              :warning,
              "blocked_user_rejected",
              "denied",
              "Refused a request from a blocked account",
              actor: caller.username
            )

            language = Problem.request_language(conn)

            conn
            |> Problem.send(403, "Forbidden", I18n.localize("error.account.blocked", language))
            |> halt()

          _ ->
            assign(conn, :caller, caller)
        end

      :error ->
        reject_invalid(conn)
    end
  rescue
    _ -> reject_invalid(conn)
  end

  defp reject_invalid(conn) do
    Log.event(:info, "jwt_validation_failed", "failure", "Rejected a bearer token",
      error_code: "invalid_token"
    )

    conn
    |> Problem.send(401, "Unauthorized", "Missing or invalid bearer token.")
    |> halt()
  end
end
