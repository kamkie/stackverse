defmodule StackverseBackend.Jwks do
  @moduledoc false

  alias StackverseBackend.Log

  def key(kid) do
    uri = jwks_uri()

    keys =
      case :persistent_term.get({__MODULE__, uri}, nil) do
        nil ->
          keys = fetch_keys!(uri)
          :persistent_term.put({__MODULE__, uri}, keys)
          keys

        keys ->
          keys
      end

    case Enum.find(keys, &(Map.get(&1, "kid") == kid)) do
      nil ->
        keys = fetch_keys!(uri)
        :persistent_term.put({__MODULE__, uri}, keys)

        keys
        |> Enum.find(&(Map.get(&1, "kid") == kid))
        |> case do
          nil -> :error
          key -> {:ok, JOSE.JWK.from_map(key)}
        end

      key ->
        {:ok, JOSE.JWK.from_map(key)}
    end
  end

  defp jwks_uri do
    settings = Application.fetch_env!(:stackverse_backend, :settings)

    case settings[:oidc_jwks_uri] do
      value when is_binary(value) and value != "" ->
        value

      _ ->
        issuer = settings[:oidc_issuer_uri]
        started = System.monotonic_time(:millisecond)

        case Req.get("#{issuer}/.well-known/openid-configuration") do
          {:ok, %{status: 200, body: %{"jwks_uri" => uri}}} ->
            uri

          {:ok, %{status: status}} ->
            log_keycloak_failure(started, "oidc_discovery_#{status}")
            raise "OIDC discovery failed with status #{status}"

          {:error, error} ->
            log_keycloak_failure(started, "oidc_discovery_failed")
            raise "OIDC discovery failed: #{inspect(error)}"
        end
    end
  end

  defp fetch_keys!(uri) do
    started = System.monotonic_time(:millisecond)

    case Req.get(uri) do
      {:ok, %{status: 200, body: %{"keys" => keys}}} when is_list(keys) ->
        keys

      {:ok, %{status: status}} ->
        log_keycloak_failure(started, "jwks_#{status}")
        raise "JWKS fetch failed with status #{status}"

      {:error, error} ->
        log_keycloak_failure(started, "jwks_fetch_failed")
        raise "JWKS fetch failed: #{inspect(error)}"
    end
  end

  defp log_keycloak_failure(started, code) do
    Log.event(:error, "dependency_call_failed", "failure", "Keycloak JWKS/OIDC call failed",
      dependency: "keycloak",
      duration_ms: System.monotonic_time(:millisecond) - started,
      error_code: code
    )
  end
end
