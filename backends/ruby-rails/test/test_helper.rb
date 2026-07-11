ENV["RAILS_ENV"] ||= "test"
ENV["STACKVERSE_SKIP_STARTUP_TASKS"] = "true"

require "simplecov"
require "simplecov-cobertura"
SimpleCov.start "rails" do
  root File.expand_path("../../..", __dir__)
  coverage_dir File.expand_path("../coverage", __dir__)
  formatter SimpleCov::Formatter::CoberturaFormatter
  add_filter "/test/"
end

require_relative "../config/environment"
require "active_support/test_case"
require "active_support/testing/autorun"
require "action_dispatch/testing/integration"

module StackverseMethodStubs
  def with_stubbed_method(target, name, replacement)
    singleton = target.singleton_class
    visibility = if singleton.private_method_defined?(name)
      :private
    elsif singleton.protected_method_defined?(name)
      :protected
    else
      :public
    end
    own_method = singleton.public_instance_methods(false).include?(name) ||
      singleton.protected_instance_methods(false).include?(name) ||
      singleton.private_instance_methods(false).include?(name)
    original = target.method(name)

    singleton.send(:define_method, name) do |*args, **keywords, &method_block|
      replacement.respond_to?(:call) ? replacement.call(*args, **keywords, &method_block) : replacement
    end
    singleton.send(visibility, name)
    yield
  ensure
    if own_method
      singleton.send(:define_method, name, original)
      singleton.send(visibility, name)
    elsif singleton.public_instance_methods(false).include?(name) ||
        singleton.protected_instance_methods(false).include?(name) ||
        singleton.private_instance_methods(false).include?(name)
      singleton.send(:remove_method, name)
    end
  end
end

class ActiveSupport::TestCase
  include StackverseMethodStubs
  parallelize(workers: 1)
end

class StackverseIntegrationTest < ActionDispatch::IntegrationTest
  setup do
    Stackverse::Sql.connection.execute(<<~SQL.squish)
      truncate table audit_entries, reports, bookmarks, messages, user_accounts cascade
    SQL
  end

  private

  def json_body
    JSON.parse(response.body)
  end

  def test_caller(username, roles: [], name: nil, email: nil)
    Stackverse::Caller.new(username, roles, name, email)
  end

  def request_as(method, path, caller:, headers: {}, **options)
    request_headers = { "Authorization" => "Bearer integration-test" }.merge(headers)
    with_stubbed_method(Stackverse::AuthService, :authenticate, caller) do
      public_send(method, path, headers: request_headers, **options)
    end
  end

  def assert_problem(status, title = nil)
    assert_response status
    assert_equal "application/problem+json", response.media_type
    payload = json_body
    assert_equal status, payload.fetch("status")
    assert_equal title, payload.fetch("title") if title
    payload
  end

  def insert_user(username, status: "active", blocked_reason: nil, first_seen: Time.utc(2026, 7, 1), last_seen: Time.utc(2026, 7, 1))
    Stackverse::Sql.connection.execute(<<~SQL.squish)
      insert into user_accounts (username, first_seen, last_seen, status, blocked_reason)
      values (
        #{Stackverse::Sql.quote(username)},
        #{Stackverse::Sql.quote(first_seen)},
        #{Stackverse::Sql.quote(last_seen)},
        #{Stackverse::Sql.quote(status)},
        #{Stackverse::Sql.quote(blocked_reason)}
      )
    SQL
    username
  end

  def insert_bookmark(id:, owner:, visibility: "public", status: "active", title: "Bookmark", url: "https://example.com", notes: nil, tags: [], created_at: Time.utc(2026, 7, 1), updated_at: created_at)
    Stackverse::Sql.connection.execute(<<~SQL.squish)
      insert into bookmarks (id, owner, url, title, notes, tags, visibility, status, created_at, updated_at)
      values (
        #{Stackverse::Sql.quote(id)}::uuid,
        #{Stackverse::Sql.quote(owner)},
        #{Stackverse::Sql.quote(url)},
        #{Stackverse::Sql.quote(title)},
        #{Stackverse::Sql.quote(notes)},
        #{Stackverse::Sql.array(tags)},
        #{Stackverse::Sql.quote(visibility)},
        #{Stackverse::Sql.quote(status)},
        #{Stackverse::Sql.quote(created_at)},
        #{Stackverse::Sql.quote(updated_at)}
      )
    SQL
    id
  end

  def insert_report(id:, bookmark_id:, reporter:, status: "open", reason: "spam", comment: nil, resolved_by: nil, resolved_at: nil, resolution_note: nil, created_at: Time.utc(2026, 7, 1))
    Stackverse::Sql.connection.execute(<<~SQL.squish)
      insert into reports (id, bookmark_id, reporter, reason, comment, status, resolved_by, resolved_at, resolution_note, created_at)
      values (
        #{Stackverse::Sql.quote(id)}::uuid,
        #{Stackverse::Sql.quote(bookmark_id)}::uuid,
        #{Stackverse::Sql.quote(reporter)},
        #{Stackverse::Sql.quote(reason)},
        #{Stackverse::Sql.quote(comment)},
        #{Stackverse::Sql.quote(status)},
        #{Stackverse::Sql.quote(resolved_by)},
        #{Stackverse::Sql.quote(resolved_at)},
        #{Stackverse::Sql.quote(resolution_note)},
        #{Stackverse::Sql.quote(created_at)}
      )
    SQL
    id
  end

  def insert_message(id:, key:, language:, text:, description: nil, created_at: Time.utc(2026, 7, 1), updated_at: created_at)
    Stackverse::Sql.connection.execute(<<~SQL.squish)
      insert into messages (id, key, language, text, description, created_at, updated_at)
      values (
        #{Stackverse::Sql.quote(id)}::uuid,
        #{Stackverse::Sql.quote(key)},
        #{Stackverse::Sql.quote(language)},
        #{Stackverse::Sql.quote(text)},
        #{Stackverse::Sql.quote(description)},
        #{Stackverse::Sql.quote(created_at)},
        #{Stackverse::Sql.quote(updated_at)}
      )
    SQL
    id
  end

  def uuid(number)
    format("00000000-0000-0000-0000-%012d", number)
  end
end
