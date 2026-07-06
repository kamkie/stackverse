module Stackverse
  module Clock
    module_function

    def now
      Time.now.utc
    end

    def iso_time(value)
      value&.utc&.iso8601(3)
    end

    def iso_date(value)
      value.to_date.iso8601
    end
  end
end
