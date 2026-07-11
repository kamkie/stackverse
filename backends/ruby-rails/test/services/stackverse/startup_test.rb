require "test_helper"

class StackverseStartupTest < ActiveSupport::TestCase
  test "startup migrates seeds and records only effective non-secret configuration" do
    config_type = Data.define(
      :port,
      :db_host,
      :db_port,
      :db_name,
      :oidc_issuer_uri,
      :oidc_jwks_uri,
      :seed_messages_dir,
      :log_level,
      :log_format,
      :otel_enabled
    )
    config = config_type.new(
      8080,
      "postgres.internal",
      5432,
      "stackverse",
      "https://identity.example.test/realms/stackverse",
      nil,
      Pathname("/seed/messages"),
      "info",
      "json",
      false
    )
    calls = []
    event = nil

    with_stubbed_method(Stackverse, :config, config) do
      with_stubbed_method(ActiveRecord::Tasks::DatabaseTasks, :migrate, -> { calls << :migrate }) do
        with_stubbed_method(Stackverse::MessageSeed, :run!, -> { calls << :seed }) do
          with_stubbed_method(Stackverse::EventLog, :info, ->(*args, **fields) { event = [ args, fields ] }) do
            Stackverse::Startup.run!
          end
        end
      end
    end

    assert_equal %i[migrate seed], calls
    assert_equal "application_start", event.first.first
    assert_equal "postgres.internal", event.last.fetch(:db_host)
    assert_equal "(via OIDC discovery)", event.last.fetch(:oidc_jwks_uri)
    refute_includes event.inspect.downcase, "password"
    refute_includes event.inspect.downcase, "secret"
  end
end
