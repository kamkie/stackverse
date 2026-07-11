require "test_helper"

class IdentityAndModelsFlowTest < StackverseIntegrationTest
  test "me exposes only application roles and optional identity claims" do
    caller = test_caller(
      "admin-user",
      roles: [ "offline_access", "moderator", "admin" ],
      name: "Admin User",
      email: "admin@example.com"
    )

    request_as(:get, "/api/v1/me", caller: caller)

    assert_response :ok
    assert_equal(
      {
        "username" => "admin-user",
        "roles" => %w[admin moderator],
        "name" => "Admin User",
        "email" => "admin@example.com"
      },
      json_body
    )

    get "/api/v1/me"
    assert_problem 401, "Unauthorized"
  end

  test "active record models persist against the contract schema and assign uuid primary keys" do
    account = UserAccount.create!(
      username: "owner",
      first_seen: Time.utc(2026, 7, 11, 10),
      last_seen: Time.utc(2026, 7, 11, 10),
      status: "active"
    )
    bookmark = Bookmark.create!(
      owner: account.username,
      url: "https://example.com",
      title: "ActiveRecord boundary",
      notes: nil,
      tags: %w[ruby rails],
      visibility: "public",
      status: "active"
    )
    report = Report.create!(bookmark_id: bookmark.id, reporter: "reporter", reason: "spam", status: "open")
    message = LocalizedMessage.create!(key: "ui.model", language: "en", text: "Model", description: nil)
    audit = AuditEntry.create!(
      actor: "admin",
      action: "message.created",
      target_type: "message",
      target_id: message.id,
      detail: { key: message.key }
    )

    [ bookmark, report, message, audit ].each do |record|
      assert_match Stackverse::InputValidation::UUID_PATTERN, record.id
      assert record.persisted?
    end
    assert_equal %w[ruby rails], bookmark.reload.tags
    assert_equal({ "key" => "ui.model" }, audit.reload.detail)

    explicit_id = uuid(450)
    explicit = Bookmark.create!(
      id: explicit_id,
      owner: account.username,
      url: "https://example.com/explicit",
      title: "Explicit UUID",
      tags: [],
      visibility: "private",
      status: "active"
    )
    assert_equal explicit_id, explicit.id
  end
end
