module Stackverse
  class AppProblem
    attr_reader :status, :title, :detail, :detail_key

    def initialize(status:, title:, detail: nil, detail_key: nil)
      @status = status
      @title = title
      @detail = detail
      @detail_key = detail_key
    end
  end
end
