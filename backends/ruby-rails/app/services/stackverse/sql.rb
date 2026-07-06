module Stackverse
  module Sql
    module_function

    def connection
      ActiveRecord::Base.connection
    end

    def query(sql)
      connection.exec_query(sql).to_a
    end

    def one(sql)
      query(sql).first
    end

    def quote(value)
      connection.quote(value)
    end

    def array(values)
      "ARRAY[#{values.map { |value| quote(value) }.join(",")}]::text[]"
    end

    def like_pattern(value)
      "%#{value.gsub("\\", "\\\\\\\\").gsub("%", "\\%").gsub("_", "\\_")}%"
    end

    def page(rows, page, size, total, mapper)
      {
        items: rows.map { |row| mapper.call(row) },
        page: page,
        size: size,
        totalItems: total,
        totalPages: (total.to_f / size).ceil
      }
    end
  end
end
