defmodule StackverseBackend.Auth do
  @moduledoc false

  alias StackverseBackend.{Jwks, Repo}

  @app_roles ~w[admin moderator]

  def verify_bearer(token) do
    with {:ok, kid} <- token_kid(token),
         {:ok, key} <- Jwks.key(kid),
         {true, %JOSE.JWT{fields: claims}, _jws} <-
           JOSE.JWT.verify_strict(key, ["RS256"], token),
         :ok <- validate_claims(claims),
         username when is_binary(username) <- claims["preferred_username"] do
      roles =
        claims
        |> get_in(["realm_access", "roles"])
        |> case do
          values when is_list(values) -> Enum.filter(values, &is_binary/1)
          _ -> []
        end

      {:ok,
       %{
         username: username,
         roles: roles,
         name: claims["name"],
         email: claims["email"]
       }}
    else
      _ -> :error
    end
  rescue
    _ -> :error
  end

  defp token_kid(token) do
    with [header | _rest] <- String.split(token, ".", parts: 3),
         {:ok, decoded} <- Base.url_decode64(header, padding: false),
         {:ok, %{"kid" => kid}} when is_binary(kid) <- Jason.decode(decoded) do
      {:ok, kid}
    else
      _ -> :error
    end
  end

  def app_roles(roles) do
    roles
    |> Enum.filter(&(&1 in @app_roles))
    |> Enum.sort()
  end

  def has_role?(%{roles: roles}, role), do: role in roles
  def has_role?(_caller, _role), do: false

  def record_seen(username) do
    %{rows: [[status]]} =
      Repo.query!(
        """
        insert into user_accounts (username, first_seen, last_seen, status)
        values ($1, $2, $2, 'active')
        on conflict (username) do update set last_seen = excluded.last_seen
        returning status
        """,
        [username, DateTime.utc_now()]
      )

    status
  end

  defp validate_claims(claims) do
    settings = Application.fetch_env!(:stackverse_backend, :settings)
    now = System.system_time(:second)

    cond do
      claims["iss"] != settings[:oidc_issuer_uri] ->
        :error

      not audience?(claims["aud"], settings[:oidc_audience]) ->
        :error

      not is_integer(claims["exp"]) or claims["exp"] <= now ->
        :error

      is_integer(claims["nbf"]) and claims["nbf"] > now ->
        :error

      true ->
        :ok
    end
  end

  defp audience?(audience, expected) when is_binary(audience), do: audience == expected
  defp audience?(audience, expected) when is_list(audience), do: expected in audience
  defp audience?(_audience, _expected), do: false
end
