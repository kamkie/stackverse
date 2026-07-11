defmodule StackverseBackend.LoggingProblemTest do
  use ExUnit.Case, async: true

  import ExUnit.CaptureLog

  alias StackverseBackend.{JsonLogFormatter, Log}

  test "JSON logs carry stable structured fields and omit logger internals" do
    formatted =
      JsonLogFormatter.format(
        :info,
        "Moderation complete",
        {{2026, 7, 11}, {12, 30, 15, 123}},
        event: "report_resolved",
        outcome: "success",
        actor: "moderator",
        nested: %{resource_id: "report-1"},
        optional: nil,
        pid: self(),
        time: 123
      )

    payload = formatted |> IO.iodata_to_binary() |> Jason.decode!()

    assert payload["timestamp"] == "2026-07-11T12:30:15.123000Z"
    assert payload["level"] == "INFO"
    assert payload["message"] == "Moderation complete"
    assert payload["event"] == "report_resolved"
    assert payload["outcome"] == "success"
    assert payload["nested"] == %{"resource_id" => "report-1"}
    refute Map.has_key?(payload, "optional")
    refute Map.has_key?(payload, "time")
  end

  test "connection metadata is allowlisted so headers, tokens, cookies, and bodies never leak" do
    conn =
      Plug.Test.conn(:post, "/api/v1/bookmarks?secret=query", "sensitive body")
      |> Plug.Conn.put_req_header("authorization", "Bearer secret-token")
      |> Plug.Conn.put_req_header("cookie", "stackverse_session=secret")
      |> Map.put(:status, 403)

    payload =
      :warning
      |> JsonLogFormatter.format("Denied", {{2026, 7, 11}, {1, 2, 3, 4}}, conn)
      |> IO.iodata_to_binary()
      |> Jason.decode!()

    assert payload["conn"] == %{
             "method" => "POST",
             "path" => "/api/v1/bookmarks",
             "remote_ip" => "127.0.0.1",
             "status" => 403
           }

    serialized = Jason.encode!(payload)
    refute serialized =~ "secret-token"
    refute serialized =~ "stackverse_session"
    refute serialized =~ "sensitive body"
    refute serialized =~ "secret=query"
  end

  test "formatter handles non-keyword metadata, non-iodata messages, and malformed timestamps safely" do
    payload =
      JsonLogFormatter.format(:debug, {:tuple, 1}, {{2026, 7, 11}, {1, 2, 3, 4}}, [1, :two])
      |> IO.iodata_to_binary()
      |> Jason.decode!()

    assert payload["message"] == "{:tuple, 1}"
    assert payload["metadata"] == [1, "two"]

    fallback =
      JsonLogFormatter.format(:error, "broken", :not_a_timestamp, [])
      |> IO.iodata_to_binary()
      |> Jason.decode!()

    assert fallback["level"] == "ERROR"
    assert fallback["message"] == "log formatting failed"
    assert is_binary(fallback["formatter_error"])
  end

  test "event logger emits the human diagnostic without payload fields" do
    output =
      capture_log(fn ->
        Log.event(:warning, "blocked_user_rejected", "denied", "Blocked account refused",
          actor: "blocked-user",
          resource_type: "user"
        )
      end)

    assert output =~ "Blocked account refused"
    refute output =~ "authorization"
    refute output =~ "cookie"
  end
end
