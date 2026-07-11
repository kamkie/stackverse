require "test_helper"
require "openssl"

class StackverseJwtVerifierTest < ActiveSupport::TestCase
  ISSUER = "https://identity.example.test/realms/stackverse"
  AUDIENCE = "stackverse-api"

  test "verifies signature issuer audience identity and role claims from jwks" do
    key = OpenSSL::PKey::RSA.generate(2048)
    token = signed_token(
      key,
      "current-key",
      { "preferred_username" => "alice", "realm_access" => { "roles" => [ "moderator", 42, "offline_access" ] } }
    )
    verifier = Stackverse::JwtVerifier.new

    caller = with_config do
      with_stubbed_method(verifier, :fetch_json, { "keys" => [ exported_jwk(key, "current-key") ] }) do
        verifier.verify(token)
      end
    end

    assert_equal "alice", caller.username
    assert_equal %w[moderator offline_access], caller.roles
    assert_equal [ "moderator" ], caller.app_roles
  end

  test "refreshes cached jwks once when a token uses a newly rotated key" do
    old_key = OpenSSL::PKey::RSA.generate(2048)
    new_key = OpenSSL::PKey::RSA.generate(2048)
    verifier = Stackverse::JwtVerifier.new
    verifier.instance_variable_set(:@jwks, { "keys" => [ exported_jwk(old_key, "old-key") ] })
    token = signed_token(new_key, "new-key", { "preferred_username" => "rotated-user" })
    fetches = 0

    caller = with_config do
      with_stubbed_method(verifier, :fetch_json, lambda { |_url|
        fetches += 1
        { "keys" => [ exported_jwk(new_key, "new-key") ] }
      }) do
        verifier.verify(token)
      end
    end

    assert_equal "rotated-user", caller.username
    assert_equal 1, fetches
  end

  test "rejects the wrong audience and missing preferred username" do
    key = OpenSSL::PKey::RSA.generate(2048)
    verifier = Stackverse::JwtVerifier.new
    jwks = { "keys" => [ exported_jwk(key, "key") ] }

    with_config do
      with_stubbed_method(verifier, :fetch_json, jwks) do
        wrong_audience = signed_token(key, "key", { "preferred_username" => "alice" }, audience: "another-api")
        assert_raises(JWT::InvalidAudError) { verifier.verify(wrong_audience) }

        missing_username = signed_token(key, "key", {})
        assert_raises(JWT::DecodeError) { verifier.verify(missing_username) }
      end
    end
  end

  test "discovers the jwks endpoint when no explicit override is configured" do
    key = OpenSSL::PKey::RSA.generate(2048)
    verifier = Stackverse::JwtVerifier.new
    token = signed_token(key, "discovered-key", { "preferred_username" => "discovered-user" })
    config = Stackverse::Configuration.new(
      "OIDC_ISSUER_URI" => ISSUER,
      "OIDC_JWKS_URI" => "",
      "SEED_MESSAGES_DIR" => "."
    )
    requested = []

    caller = with_stubbed_method(Stackverse, :config, config) do
      with_stubbed_method(verifier, :fetch_json, lambda { |url|
        requested << url
        if url.end_with?("/.well-known/openid-configuration")
          { "jwks_uri" => "https://identity.example.test/discovered-jwks" }
        else
          { "keys" => [ exported_jwk(key, "discovered-key") ] }
        end
      }) do
        verifier.verify(token)
      end
    end

    assert_equal "discovered-user", caller.username
    assert_equal(
      [
        "#{ISSUER}/.well-known/openid-configuration",
        "https://identity.example.test/discovered-jwks"
      ],
      requested
    )
  end

  test "fetches jwks over https with bounded timeouts and rejects failed responses" do
    verifier = Stackverse::JwtVerifier.new
    success = Net::HTTPOK.new("1.1", "200", "OK")
    success.instance_variable_set(:@read, true)
    success.instance_variable_set(:@body, '{"keys":[]}')
    requests = []
    successful_http = Object.new
    successful_http.define_singleton_method(:get) do |path|
      requests << path
      success
    end

    payload = with_stubbed_method(Net::HTTP, :start, lambda { |host, port, **options, &block|
      requests << [ host, port, options ]
      block.call(successful_http)
    }) do
      verifier.send(:fetch_json, "https://identity.example.test/jwks?tenant=stackverse")
    end

    assert_equal({ "keys" => [] }, payload)
    assert_equal(
      [ "identity.example.test", 443, { use_ssl: true, read_timeout: 5, open_timeout: 5 } ],
      requests.fetch(0)
    )
    assert_equal "/jwks?tenant=stackverse", requests.fetch(1)

    failure = Net::HTTPBadGateway.new("1.1", "502", "Bad Gateway")
    failing_http = Object.new
    failing_http.define_singleton_method(:get) { |_path| failure }
    error = with_stubbed_method(Net::HTTP, :start, ->(*_args, **_options, &block) { block.call(failing_http) }) do
      assert_raises(RuntimeError) { verifier.send(:fetch_json, "https://identity.example.test/jwks") }
    end
    assert_equal "HTTP 502", error.message
  end

  test "logs oidc discovery failures without exposing dependency error text" do
    verifier = Stackverse::JwtVerifier.new
    config = Stackverse::Configuration.new(
      "OIDC_ISSUER_URI" => ISSUER,
      "OIDC_JWKS_URI" => "",
      "SEED_MESSAGES_DIR" => "."
    )
    event = nil

    error = with_stubbed_method(Stackverse, :config, config) do
      with_stubbed_method(verifier, :fetch_json, ->(_url) { raise IOError, "client_secret=must-not-leak" }) do
        with_stubbed_method(Stackverse::EventLog, :error, ->(*args, **fields) { event = [ args, fields ] }) do
          assert_raises(IOError) { verifier.send(:jwks_uri) }
        end
      end
    end

    assert_equal "client_secret=must-not-leak", error.message
    assert_equal "dependency_call_failed", event.first.fetch(0)
    assert_equal "failure", event.first.fetch(1)
    assert_equal "keycloak", event.last.fetch(:dependency)
    assert_equal "io_error", event.last.fetch(:error_code)
    refute_includes event.inspect, "must-not-leak"
  end

  private

  def with_config(&block)
    config = Stackverse::Configuration.new(
      "OIDC_ISSUER_URI" => ISSUER,
      "OIDC_JWKS_URI" => "https://identity.example.test/jwks",
      "SEED_MESSAGES_DIR" => "."
    )
    with_stubbed_method(Stackverse, :config, config, &block)
  end

  def signed_token(key, kid, extra_claims, audience: AUDIENCE)
    now = Time.now.to_i
    payload = {
      "iss" => ISSUER,
      "aud" => audience,
      "iat" => now - 1,
      "exp" => now + 300
    }.merge(extra_claims)
    JWT.encode(payload, key, "RS256", kid: kid)
  end

  def exported_jwk(key, kid)
    JSON.parse(JSON.generate(JWT::JWK.new(key, kid: kid).export))
  end
end
