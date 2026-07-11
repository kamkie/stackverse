require "test_helper"
require "tmpdir"

class StackverseMessageSeedTest < StackverseIntegrationTest
  test "seed import is idempotent and preserves runtime edits" do
    Dir.mktmpdir("stackverse-rails-seed") do |directory|
      seed_path = Pathname(directory).join("en.json")
      File.write(seed_path, JSON.generate("ui.greeting" => "Seed greeting"), mode: "w", encoding: "UTF-8")
      config = Data.define(:seed_messages_dir).new(Pathname(directory))
      events = []

      with_stubbed_method(Stackverse, :config, config) do
        with_stubbed_method(Stackverse::EventLog, :info, ->(*args, **fields) { events << [ args, fields ] }) do
          Stackverse::MessageSeed.run!
          Stackverse::Sql.connection.execute("update messages set text = 'Runtime edit' where key = 'ui.greeting' and language = 'en'")
          File.write(seed_path, JSON.generate("ui.greeting" => "Changed seed"), mode: "w", encoding: "UTF-8")
          Stackverse::MessageSeed.run!
        end
      end

      row = Stackverse::Sql.one("select text from messages where key = 'ui.greeting' and language = 'en'")
      assert_equal "Runtime edit", row.fetch("text")
      assert_equal 2, events.length
      assert_equal "message_seed_imported", events.first.first.first
      assert_equal 1, events.first.last.fetch(:inserted)
      assert_equal 1, events.last.last.fetch(:skipped)
    end
  end

  test "missing seed files fail startup clearly" do
    Dir.mktmpdir("stackverse-rails-empty-seed") do |directory|
      config = Data.define(:seed_messages_dir).new(Pathname(directory))

      error = with_stubbed_method(Stackverse, :config, config) do
        assert_raises(RuntimeError) { Stackverse::MessageSeed.run! }
      end

      assert_includes error.message, "Message seed directory not found"
      assert_includes error.message, directory
    end
  end
end
