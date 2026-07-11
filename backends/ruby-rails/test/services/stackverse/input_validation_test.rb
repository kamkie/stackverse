require "test_helper"

class StackverseInputValidationTest < ActiveSupport::TestCase
  test "bookmark tags are normalized and deduplicated" do
    input = Stackverse::InputValidation.validate_bookmark(
      "url" => "https://example.com",
      "title" => "Example",
      "tags" => [ "Ruby", " ruby ", "rails-api" ]
    )

    assert_equal %w[ruby rails-api], input[:tags]
    assert_equal "private", input[:visibility]
  end

  test "null bookmark visibility uses the private default" do
    input = Stackverse::InputValidation.validate_bookmark(
      "url" => "https://example.com",
      "title" => "Example",
      "visibility" => nil
    )

    assert_equal "private", input[:visibility]
  end

  test "invalid bookmark input raises field violations" do
    error = assert_raises(Stackverse::ValidationError) do
      Stackverse::InputValidation.validate_bookmark({})
    end

    assert_includes error.violations.map(&:field), "url"
    assert_includes error.violations.map(&:field), "title"
  end

  test "validates report moderation message and account inputs" do
    assert_equal(
      { reason: "broken-link", comment: "gone" },
      Stackverse::InputValidation.validate_report("reason" => "broken-link", "comment" => "gone")
    )
    assert_equal "dismissed", Stackverse::InputValidation.validate_report_status("dismissed")
    assert_equal(
      [ "actioned", "confirmed" ],
      Stackverse::InputValidation.validate_resolution("resolution" => "actioned", "note" => "confirmed")
    )
    assert_equal(
      [ "hidden", "manual review" ],
      Stackverse::InputValidation.validate_bookmark_status("status" => "hidden", "note" => "manual review")
    )
    assert_equal(
      { key: "ui.notice", language: "pl", text: "Treść", description: nil },
      Stackverse::InputValidation.validate_message("key" => " ui.notice ", "language" => " pl ", "text" => "Treść")
    )
    assert_equal(
      [ "blocked", "policy" ],
      Stackverse::InputValidation.validate_user_status({ "status" => "blocked", "reason" => " policy " }, "target", "admin")
    )
    assert_equal [ "active", nil ], Stackverse::InputValidation.validate_user_status({ "status" => "active" }, "target", "admin")
    assert_equal Time.iso8601("2026-07-11T12:30:00Z"), Stackverse::InputValidation.parse_datetime("2026-07-11T12:30:00Z", "from")
    assert_equal %w[ruby rails], Stackverse::InputValidation.validate_query_tags([ " Ruby ", "rails" ])
  end

  test "maps malformed contract inputs to field violations or typed problems" do
    violations = [
      -> { Stackverse::InputValidation.validate_report("reason" => "unknown", "comment" => "x" * 1001) },
      -> { Stackverse::InputValidation.validate_resolution("resolution" => "later", "note" => "x" * 1001) },
      -> { Stackverse::InputValidation.validate_bookmark_status("status" => "deleted", "note" => "x" * 1001) },
      -> { Stackverse::InputValidation.validate_message("key" => "Bad Key", "language" => "EN", "text" => "") },
      -> { Stackverse::InputValidation.validate_query_tags([ "not valid" ]) }
    ]

    violations.each do |validation|
      error = assert_raises(Stackverse::ValidationError, &validation)
      refute_empty error.violations
      assert error.violations.all? { |violation| violation.message_key.start_with?("validation.") }
    end

    assert_equal 404, assert_raises(Stackverse::ProblemError) { Stackverse::InputValidation.parse_uuid("not-a-uuid") }.problem.status
    assert_equal 400, assert_raises(Stackverse::ProblemError) { Stackverse::InputValidation.validate_report_status("closed") }.problem.status
    assert_equal 400, assert_raises(Stackverse::ProblemError) { Stackverse::InputValidation.max_length("toolong", 3, "q") }.problem.status
    assert_equal 400, assert_raises(Stackverse::ProblemError) { Stackverse::InputValidation.parse_datetime("yesterday", "from") }.problem.status
    block_error = assert_raises(Stackverse::ValidationError) do
      Stackverse::InputValidation.validate_user_status({ "status" => "blocked", "reason" => "" }, "target", "admin")
    end
    assert_equal [ "validation.block.reason.required" ], block_error.violations.map(&:message_key)
    self_block_error = assert_raises(Stackverse::ProblemError) do
      Stackverse::InputValidation.validate_user_status({ "status" => "blocked", "reason" => "valid" }, "admin", "admin")
    end
    assert_equal 409, self_block_error.problem.status
  end

  test "rejects non-http bookmark urls and normalizes valid uuid casing" do
    error = assert_raises(Stackverse::ValidationError) do
      Stackverse::InputValidation.validate_bookmark("url" => "ftp://example.com/file", "title" => "FTP")
    end
    assert_equal [ "validation.url.invalid" ], error.violations.map(&:message_key)

    malformed = assert_raises(Stackverse::ValidationError) do
      Stackverse::InputValidation.validate_bookmark("url" => "http://[invalid", "title" => "Malformed")
    end
    assert_equal [ "validation.url.invalid" ], malformed.violations.map(&:message_key)

    uppercase = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
    assert_equal uppercase.downcase, Stackverse::InputValidation.parse_uuid(uppercase)
  end
end
