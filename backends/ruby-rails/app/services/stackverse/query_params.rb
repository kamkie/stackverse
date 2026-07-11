module Stackverse
  class QueryParams
    def initialize(query_string)
      @values = Rack::Utils.parse_query(query_string.to_s)
    end

    def first(name)
      values(name).first
    end

    def single(name)
      raw = values(name)
      return nil if raw.empty?

      Errors.bad_request("#{name} must not be repeated") if raw.length > 1
      raw.first
    end

    def multi(name)
      values(name)
    end

    def page_and_size
      page = integer(single("page"), 0, "page")
      size = integer(single("size"), 20, "size")
      Errors.bad_request("page must not be negative") if page.negative?
      Errors.bad_request("size must be between 1 and 100") if size < 1 || size > 100
      [ page, size ]
    end

    private

    def values(name)
      value = @values[name]
      Array(value).map(&:to_s)
    end

    def integer(value, fallback, name)
      return fallback if value.nil? || value == ""

      Errors.bad_request("#{name} must be an integer") unless value.match?(/\A-?\d+\z/)
      value.to_i
    end
  end
end
