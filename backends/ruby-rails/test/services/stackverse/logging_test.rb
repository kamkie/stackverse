require "test_helper"

class StackverseLoggingTest < ActiveSupport::TestCase
  test "json formatter emits one structured utc record with stable event fields" do
    line = Stackverse::JsonLogger.json_formatter.call(
      "INFO",
      Time.new(2026, 7, 11, 12, 30, 45.1234, "+02:00"),
      "stackverse.test",
      { message: "Report resolved", event: "report_resolved", outcome: "success", actor: "moderator" }
    )

    assert_equal 1, line.lines.length
    record = JSON.parse(line)
    assert_equal "2026-07-11T10:30:45.123Z", record.fetch("timestamp")
    assert_equal "info", record.fetch("level")
    assert_equal "stackverse.test", record.fetch("logger")
    assert_equal "Report resolved", record.fetch("message")
    assert_equal "report_resolved", record.fetch("event")
    assert_equal "success", record.fetch("outcome")
    assert_equal "moderator", record.fetch("actor")
  end

  test "text formatter stays single line and event log uses the requested severity" do
    line = Stackverse::JsonLogger.text_formatter.call(
      "WARN",
      Time.utc(2026, 7, 11, 10, 30, 45, 123_000),
      "stackverse.test",
      "Dependency unavailable"
    )
    assert_equal "2026-07-11T10:30:45.123Z warn stackverse.test: Dependency unavailable\n", line

    records = []
    logger = Object.new
    logger.define_singleton_method(:add) do |severity, message, progname|
      records << [ severity, message, progname ]
    end

    with_stubbed_method(Rails, :logger, logger) do
      Stackverse::EventLog.warn(
        "dependency_call_failed",
        "failure",
        "PostgreSQL unavailable",
        dependency: "postgres",
        duration_ms: 12,
        omitted: nil
      )
      Stackverse::EventLog.error(
        "dependency_call_failed",
        "failure",
        "OIDC discovery unavailable",
        dependency: "keycloak",
        error_code: "timeout_error"
      )
    end

    severity, message, progname = records.fetch(0)
    assert_equal Logger::WARN, severity
    assert_equal "dependency_call_failed", message.fetch(:event)
    assert_equal "failure", message.fetch(:outcome)
    assert_equal "postgres", message.fetch(:dependency)
    refute message.key?(:omitted)
    assert_equal "stackverse.backend.ruby_rails", progname

    error_severity, error_message, error_progname = records.fetch(1)
    assert_equal Logger::ERROR, error_severity
    assert_equal "dependency_call_failed", error_message.fetch(:event)
    assert_equal "keycloak", error_message.fetch(:dependency)
    assert_equal "timeout_error", error_message.fetch(:error_code)
    assert_equal "stackverse.backend.ruby_rails", error_progname
  end

  test "configuration honors logging and telemetry environment values and rejects invalid ports" do
    config = Stackverse::Configuration.new(
      "PORT" => "9090",
      "DB_PORT" => "55432",
      "LOG_LEVEL" => "WARN",
      "LOG_FORMAT" => "text",
      "OTEL_SDK_DISABLED" => "false",
      "OIDC_JWKS_URI" => "  ",
      "SEED_MESSAGES_DIR" => "."
    )

    assert_equal 9090, config.port
    assert_equal 55_432, config.db_port
    assert_equal "warn", config.log_level
    assert_equal "text", config.log_format
    assert config.otel_enabled
    assert_nil config.oidc_jwks_uri

    error = assert_raises(RuntimeError) do
      Stackverse::Configuration.new("PORT" => "not-a-port", "SEED_MESSAGES_DIR" => ".")
    end
    assert_equal "PORT must be an integer", error.message
  end
end
