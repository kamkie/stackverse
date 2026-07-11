require "test_helper"

class StackverseMessageCatalogTest < ActiveSupport::TestCase
  test "accept language parsing is quality ordered and language only" do
    parsed = Stackverse::MessageCatalog.parse_accept_language("en;q=0.5, zz, pl-PL;q=0.8")

    assert_equal %w[zz pl en], parsed
  end

  test "language resolution ignores unsupported preferences and falls back to english" do
    with_stubbed_method(Stackverse::MessageCatalog, :supported_languages, Set.new(%w[en pl])) do
      assert_equal "pl", Stackverse::MessageCatalog.resolve("de", "pl-PL, en;q=0.5")
      assert_equal "en", Stackverse::MessageCatalog.resolve("de", "zz")
    end
  end
end
