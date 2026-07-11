require "test_helper"

class ErrorMappingFlowTest < StackverseIntegrationTest
  test "malformed json becomes a contract bad request problem" do
    request_as(
      :post,
      "/api/v1/bookmarks",
      caller: test_caller("alice"),
      headers: { "Content-Type" => "application/json" },
      params: "{not-json"
    )

    problem = assert_problem(400, "Bad Request")
    assert_equal "Request validation failed.", problem.fetch("detail")
  end

  test "database failures become generic 500 problems and structured dependency events" do
    events = []

    with_stubbed_method(Stackverse::Sql, :query, ->(_sql) { raise PG::ConnectionBad, "password=must-not-leak" }) do
      with_stubbed_method(Stackverse::EventLog, :error, ->(*args, **fields) { events << [ args, fields ] }) do
        get "/api/v1/messages"
      end
    end

    problem = assert_problem(500, "Internal Server Error")
    assert_equal "An unexpected error occurred.", problem.fetch("detail")
    assert_equal 1, events.length
    assert_equal "dependency_call_failed", events.first.first.first
    assert_equal "postgres", events.first.last.fetch(:dependency)
    assert_equal "connection_bad", events.first.last.fetch(:error_code)
    refute_includes events.inspect, "must-not-leak"
  end

  test "unexpected failures retain diagnostics without logging exception secrets" do
    records = []
    logger = Rails.logger
    anonymous_error = Class.new(StandardError)

    with_stubbed_method(Stackverse::Sql, :query, ->(_sql) { raise anonymous_error, "Authorization: Bearer must-not-leak" }) do
      with_stubbed_method(logger, :error, ->(*args, **fields) { records << (fields.empty? ? args.fetch(0) : fields) }) do
        get "/api/v1/messages"
      end
    end

    problem = assert_problem(500, "Internal Server Error")
    assert_equal "An unexpected error occurred.", problem.fetch("detail")
    record = records.fetch(0)
    assert_equal "Unhandled error", record.fetch(:message)
    assert_equal "anonymous_error", record.fetch(:error_code)
    assert_equal "AnonymousError", record.fetch(:exception_class)
    refute_empty record.fetch(:backtrace)
    refute_includes record.inspect, "must-not-leak"
    refute_includes record.inspect, "Authorization"
  end
end
