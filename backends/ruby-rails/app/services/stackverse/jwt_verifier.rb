module Stackverse
  class JwtVerifier
    require "jwt"

    class UnknownKeyId < JWT::DecodeError; end

    require "net/http"
    require "uri"

    def initialize
      @jwks = nil
      @jwks_mutex = Mutex.new
    end

    def verify(token)
      payload = decode_with_current_keys(token)
      username = payload["preferred_username"]
      raise JWT::DecodeError, "missing preferred_username" unless username.is_a?(String) && username.present?

      raw_roles = payload.dig("realm_access", "roles")
      roles = raw_roles.is_a?(Array) ? raw_roles.select { |role| role.is_a?(String) } : []
      Caller.new(
        username,
        roles,
        payload["name"].is_a?(String) ? payload["name"] : nil,
        payload["email"].is_a?(String) ? payload["email"] : nil
      )
    end

    private

    def decode_with_current_keys(token)
      header = JWT.decode(token, nil, false).last
      key = public_key(header["kid"])
      decode(token, key)
    rescue UnknownKeyId
      refresh_jwks
      header = JWT.decode(token, nil, false).last
      key = public_key(header["kid"])
      decode(token, key)
    end

    def decode(token, key)
      JWT.decode(
        token,
        key,
        true,
        algorithms: [ "RS256" ],
        iss: Stackverse.config.oidc_issuer_uri,
        verify_iss: true,
        aud: Stackverse.config.oidc_audience,
        verify_aud: true
      ).first
    end

    def public_key(kid)
      key = jwks.fetch("keys", []).find { |entry| entry["kid"] == kid }
      raise UnknownKeyId, "unknown key id" unless key

      JWT::JWK.import(key).public_key
    end

    def jwks
      cached = @jwks
      return cached if cached

      @jwks_mutex.synchronize do
        @jwks ||= fetch_json(jwks_uri)
      end
    end

    def refresh_jwks
      @jwks_mutex.synchronize do
        @jwks = nil
      end
    end

    def jwks_uri
      return Stackverse.config.oidc_jwks_uri if Stackverse.config.oidc_jwks_uri

      discovery = fetch_json("#{Stackverse.config.oidc_issuer_uri}/.well-known/openid-configuration")
      discovery.fetch("jwks_uri")
    rescue StandardError => e
      EventLog.error(
        "dependency_call_failed",
        "failure",
        "OIDC discovery failed",
        dependency: "keycloak",
        error_code: e.class.name.demodulize.underscore
      )
      raise
    end

    def fetch_json(url)
      uri = URI.parse(url)
      response = Net::HTTP.start(uri.host, uri.port, use_ssl: uri.scheme == "https", read_timeout: 5, open_timeout: 5) do |http|
        http.get(uri.request_uri)
      end
      raise "HTTP #{response.code}" unless response.is_a?(Net::HTTPSuccess)

      JSON.parse(response.body)
    end
  end
end
