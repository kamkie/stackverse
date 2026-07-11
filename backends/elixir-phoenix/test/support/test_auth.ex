defmodule StackverseBackend.TestAuth do
  @moduledoc false

  alias StackverseBackend.Jwks

  @issuer "https://issuer.test/realms/stackverse"
  @jwks_uri "https://issuer.test/realms/stackverse/protocol/openid-connect/certs"
  @audience "stackverse-api"
  @kid "stackverse-test-key"

  def install! do
    original = Application.get_env(:stackverse_backend, :settings)

    Application.put_env(:stackverse_backend, :settings,
      oidc_issuer_uri: @issuer,
      oidc_jwks_uri: @jwks_uri,
      oidc_audience: @audience
    )

    key = JOSE.JWK.generate_key({:rsa, 2048})
    {_fields, public_key} = key |> JOSE.JWK.to_public() |> JOSE.JWK.to_map()
    :persistent_term.put({Jwks, @jwks_uri}, [Map.put(public_key, "kid", @kid)])

    {key, original}
  end

  def uninstall!(original) do
    :persistent_term.erase({Jwks, @jwks_uri})

    if is_nil(original) do
      Application.delete_env(:stackverse_backend, :settings)
    else
      Application.put_env(:stackverse_backend, :settings, original)
    end
  end

  def token(key, username, roles \\ [], overrides \\ %{}) do
    now = System.system_time(:second)

    claims =
      %{
        "iss" => @issuer,
        "aud" => @audience,
        "exp" => now + 3_600,
        "iat" => now,
        "preferred_username" => username,
        "name" => String.capitalize(username),
        "email" => "#{username}@example.test",
        "realm_access" => %{"roles" => roles}
      }
      |> Map.merge(overrides)

    key
    |> JOSE.JWT.sign(%{"alg" => "RS256", "kid" => @kid}, claims)
    |> JOSE.JWS.compact()
    |> elem(1)
  end
end
