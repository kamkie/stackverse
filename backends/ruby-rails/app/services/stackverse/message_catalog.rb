module Stackverse
  require "set"

  module MessageCatalog
    DEFAULT_LANGUAGE = "en"

    module_function

    def supported_languages
      Sql.query("select distinct language from messages").map { |row| row["language"] }.to_set
    end

    def resolve(lang, accept_language)
      supported = supported_languages
      return lang if lang.present? && supported.include?(lang)

      parse_accept_language(accept_language).each do |code|
        return code if supported.include?(code)
      end
      DEFAULT_LANGUAGE
    end

    def localize(key, language)
      languages = [ language, DEFAULT_LANGUAGE ].uniq
      row = Sql.one(<<~SQL.squish)
        select text from messages
        where key = #{Sql.quote(key)} and language = any(#{Sql.array(languages)})
        order by case when language = #{Sql.quote(language)} then 0 else 1 end
        limit 1
      SQL
      row ? row["text"] : key
    end

    def bundle(language)
      languages = [ language, DEFAULT_LANGUAGE ].uniq
      rows = Sql.query(<<~SQL.squish)
        select key, language, text from messages
        where language = any(#{Sql.array(languages)})
        order by key, case when language = #{Sql.quote(language)} then 0 else 1 end
      SQL
      rows.each_with_object({}) do |row, payload|
        payload[row["key"]] ||= row["text"]
      end
    end

    def parse_accept_language(header)
      return [] if header.blank?

      header.split(",").filter_map do |part|
        pieces = part.strip.split(";")
        tag = pieces.first.to_s.strip.downcase.split("-").first
        next unless tag.match?(/\A[a-z]{1,8}\z/)

        quality = pieces.drop(1).filter_map do |parameter|
          match = parameter.match(/\A\s*q=([0-9.]+)\s*\z/)
          match ? match[1].to_f : nil
        end.first || 1.0
        [ tag, quality.nan? ? 0.0 : quality ]
      end.sort_by { |_tag, quality| -quality }.map(&:first)
    end
  end
end
