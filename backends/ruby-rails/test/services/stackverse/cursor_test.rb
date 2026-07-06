require "test_helper"

class StackverseCursorTest < ActiveSupport::TestCase
  test "round trips opaque bookmark cursors" do
    cursor = Stackverse::BookmarkCursor.new(Time.utc(2026, 7, 5, 12, 30, 15, 123456), "00000000-0000-0000-0000-000000000001")

    decoded = Stackverse::Cursor.decode(Stackverse::Cursor.encode(cursor))

    assert_equal cursor.id, decoded.id
    assert_equal cursor.created_at.iso8601(6), decoded.created_at.utc.iso8601(6)
  end

  test "rejects malformed cursors as bad requests" do
    error = assert_raises(Stackverse::ProblemError) { Stackverse::Cursor.decode("not a cursor") }

    assert_equal 400, error.problem.status
  end
end
