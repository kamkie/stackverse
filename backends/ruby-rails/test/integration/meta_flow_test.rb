require "test_helper"

class MetaFlowTest < StackverseIntegrationTest
  test "health and readiness are public and readiness logs only state transitions" do
    get "/healthz"
    assert_response :ok

    MetaController.ready = true
    events = []
    failing_connection = Object.new
    failing_connection.define_singleton_method(:execute) { |_sql| raise PG::ConnectionBad, "database unavailable" }

    with_stubbed_method(Stackverse::Sql, :connection, failing_connection) do
      with_stubbed_method(Stackverse::EventLog, :warn, ->(*args, **fields) { events << [ args, fields ] }) do
        2.times do
          get "/readyz"
          assert_response :service_unavailable
        end
      end
    end

    assert_equal 1, events.length
    assert_equal "dependency_call_failed", events.first.first.first
    assert_equal "postgres", events.first.last.fetch(:dependency)
    refute_includes events.inspect, "database unavailable"

    get "/readyz"
    assert_response :ok
    assert MetaController.ready
  ensure
    MetaController.ready = true
  end
end
