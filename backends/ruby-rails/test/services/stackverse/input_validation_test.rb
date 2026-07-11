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
end
