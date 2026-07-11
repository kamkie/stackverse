require "test_helper"

class StackverseSerializersTest < ActiveSupport::TestCase
  test "serializes bookmark rows and omits nil values" do
    row = {
      "id" => "00000000-0000-0000-0000-000000000001",
      "url" => "https://example.com",
      "title" => "Example",
      "notes" => nil,
      "tags" => "{ruby,rails}",
      "visibility" => "private",
      "status" => "active",
      "owner" => "demo",
      "created_at" => Time.utc(2026, 7, 11, 10),
      "updated_at" => Time.utc(2026, 7, 11, 11)
    }

    serialized = Stackverse::Serializers.bookmark(row)

    assert_equal %w[ruby rails], serialized[:tags]
    assert_equal "2026-07-11T10:00:00.000Z", serialized[:createdAt]
    refute serialized.key?(:notes)
  end

  test "normalizes postgres arrays" do
    assert_equal [], Stackverse::Serializers.pg_text_array(nil)
    assert_equal [], Stackverse::Serializers.pg_text_array("{}")
    assert_equal %w[ruby rails], Stackverse::Serializers.pg_text_array(%w[ruby rails])
  end

  test "parses raw postgres json audit details into contract objects" do
    serialized = Stackverse::Serializers.audit(
      "id" => "00000000-0000-0000-0000-000000000001",
      "actor" => "admin",
      "action" => "user.blocked",
      "target_type" => "user",
      "target_id" => "target",
      "detail" => '{"reason":"policy"}',
      "created_at" => Time.utc(2026, 7, 11, 10)
    )

    assert_equal({ "reason" => "policy" }, serialized.fetch(:detail))
  end
end
