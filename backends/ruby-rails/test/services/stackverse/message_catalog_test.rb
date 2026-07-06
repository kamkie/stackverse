require "test_helper"

class StackverseMessageCatalogTest < ActiveSupport::TestCase
  test "accept language parsing is quality ordered and language only" do
    parsed = Stackverse::MessageCatalog.parse_accept_language("en;q=0.5, zz, pl-PL;q=0.8")

    assert_equal %w[zz pl en], parsed
  end
end
