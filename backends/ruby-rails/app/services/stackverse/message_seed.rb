module Stackverse
  module MessageSeed
    module_function

    def run!
      files = Dir[Stackverse.config.seed_messages_dir.join("*.json")].sort
      raise "Message seed directory not found: #{Stackverse.config.seed_messages_dir}" if files.empty?

      files.each do |path|
        language = File.basename(path, ".json")
        entries = JSON.parse(File.read(path, encoding: "UTF-8"))
        inserted = 0
        ActiveRecord::Base.transaction do
          entries.each do |key, text|
            now = Clock.now
            result = Sql.connection.execute(<<~SQL.squish)
              insert into messages (id, key, language, text, created_at, updated_at)
              values (#{Sql.quote(SecureRandom.uuid)}, #{Sql.quote(key)}, #{Sql.quote(language)}, #{Sql.quote(text)}, #{Sql.quote(now)}, #{Sql.quote(now)})
              on conflict (key, language) do nothing
            SQL
            inserted += result.cmd_tuples
          end
        end
        EventLog.info(
          "message_seed_imported",
          "success",
          "Message seed '#{language}': #{inserted} inserted, #{entries.length - inserted} already present",
          language: language,
          inserted: inserted,
          skipped: entries.length - inserted
        )
      end
    end
  end
end
