require "test_helper"

class StackverseQueryParamsTest < ActiveSupport::TestCase
  test "uses pagination defaults and parses repeated multi-values" do
    params = Stackverse::QueryParams.new("tag=ruby&tag=rails")

    assert_equal [ 0, 20 ], params.page_and_size
    assert_equal %w[ruby rails], params.multi("tag")
  end

  test "parses explicit pagination" do
    assert_equal [ 2, 100 ], Stackverse::QueryParams.new("page=2&size=100").page_and_size
  end

  test "rejects repeated single values" do
    error = assert_raises(Stackverse::ProblemError) do
      Stackverse::QueryParams.new("page=1&page=2").page_and_size
    end

    assert_equal 400, error.problem.status
  end

  test "rejects invalid pagination" do
    [ "page=-1", "page=one", "size=0", "size=101" ].each do |query|
      error = assert_raises(Stackverse::ProblemError) do
        Stackverse::QueryParams.new(query).page_and_size
      end

      assert_equal 400, error.problem.status
    end
  end
end
