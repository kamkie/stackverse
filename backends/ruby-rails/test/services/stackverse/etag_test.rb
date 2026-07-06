require "test_helper"

class StackverseEtagTest < ActiveSupport::TestCase
  FakeRequest = Struct.new(:if_none_match) do
    def get_header(name)
      name == "HTTP_IF_NONE_MATCH" ? if_none_match : nil
    end
  end

  test "wildcard if-none-match matches any etag" do
    assert Stackverse::Etag.matches?(FakeRequest.new("*"), %("abc"))
  end

  test "comma-separated if-none-match values are trimmed" do
    assert Stackverse::Etag.matches?(FakeRequest.new(%("old", "abc")), %("abc"))
  end
end
