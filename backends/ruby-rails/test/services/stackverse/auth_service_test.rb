require "test_helper"

class StackverseAuthServiceTest < StackverseIntegrationTest
  test "authenticated callers are lazily provisioned and last seen advances without replacing first seen" do
    caller = test_caller("alice", roles: [ "offline_access", "moderator" ], name: "Alice", email: "alice@example.com")
    first_seen = Time.utc(2026, 7, 10, 10)
    last_seen = Time.utc(2026, 7, 11, 12)

    with_stubbed_method(Stackverse::AuthService, :verify_bearer, caller) do
      with_stubbed_method(Stackverse::Clock, :now, first_seen) do
        assert_equal caller, Stackverse::AuthService.authenticate("Bearer first-token", Stackverse::QueryParams.new(""), nil)
      end
      with_stubbed_method(Stackverse::Clock, :now, last_seen) do
        assert_equal caller, Stackverse::AuthService.authenticate("Bearer second-token", Stackverse::QueryParams.new(""), nil)
      end
    end

    account = Stackverse::Sql.one("select * from user_accounts where username = 'alice'")
    assert_equal first_seen, account.fetch("first_seen")
    assert_equal last_seen, account.fetch("last_seen")
    assert_equal "active", account.fetch("status")
    assert_equal(
      { username: "alice", roles: [ "moderator" ], name: "Alice", email: "alice@example.com" },
      Stackverse::AuthService.me(caller)
    )
  end

  test "blocked callers are rejected with localized details on their next request" do
    insert_user("blocked", status: "blocked", blocked_reason: "policy")
    insert_message(id: uuid(400), key: "error.account.blocked", language: "en", text: "This account is blocked")
    caller = test_caller("blocked")
    logged = nil

    error = with_stubbed_method(Stackverse::AuthService, :verify_bearer, caller) do
      with_stubbed_method(Stackverse::EventLog, :warn, ->(*args, **fields) { logged = [ args, fields ] }) do
        assert_raises(Stackverse::ProblemError) do
          Stackverse::AuthService.authenticate("Bearer opaque-token", Stackverse::QueryParams.new("lang=en"), nil)
        end
      end
    end

    assert_equal 403, error.problem.status
    assert_equal "This account is blocked", error.problem.detail
    assert_equal "blocked_user_rejected", logged.first.first
    assert_equal "blocked", logged.last.fetch(:actor)
  end

  test "missing and invalid bearer credentials do not leak token material to logs" do
    assert_nil Stackverse::AuthService.authenticate(nil, Stackverse::QueryParams.new(""), nil)
    assert_equal 0, Stackverse::Sql.one("select count(*)::int as count from user_accounts").fetch("count")

    verifier = Object.new
    verifier.define_singleton_method(:verify) { |_token| raise JWT::DecodeError, "invalid signature for secret-token" }
    logged = nil

    error = with_stubbed_method(Stackverse::AuthService, :verifier, verifier) do
      with_stubbed_method(Stackverse::EventLog, :info, ->(*args, **fields) { logged = [ args, fields ] }) do
        assert_raises(Stackverse::ProblemError) do
          Stackverse::AuthService.authenticate("Bearer secret-token", Stackverse::QueryParams.new(""), nil)
        end
      end
    end

    assert_equal 401, error.problem.status
    assert_equal "jwt_validation_failed", logged.first.first
    assert_equal "decode_error", logged.last.fetch(:error_code)
    refute_includes logged.inspect, "secret-token"
    refute_includes logged.inspect, "invalid signature"
  end
end
