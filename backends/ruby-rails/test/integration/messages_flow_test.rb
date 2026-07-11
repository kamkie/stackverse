require "test_helper"

class MessagesFlowTest < StackverseIntegrationTest
  test "public message reads resolve language fallback and support etag revalidation" do
    greeting_id = insert_message(id: uuid(200), key: "ui.greeting", language: "en", text: "Hello")
    insert_message(id: uuid(201), key: "ui.greeting", language: "pl", text: "Cześć")
    insert_message(id: uuid(202), key: "ui.only-en", language: "en", text: "English fallback")

    get "/api/v1/messages/bundle", headers: { "Accept-Language" => "de, pl-PL;q=0.9, en;q=0.5" }

    assert_response :ok
    bundle = json_body
    assert_equal "pl", bundle.fetch("language")
    assert_equal "Cześć", bundle.fetch("messages").fetch("ui.greeting")
    assert_equal "English fallback", bundle.fetch("messages").fetch("ui.only-en")
    assert_equal "pl", response.headers.fetch("Content-Language")
    assert_equal "no-cache", response.headers.fetch("Cache-Control")
    etag = response.headers.fetch("ETag")

    get "/api/v1/messages/bundle", headers: { "Accept-Language" => "pl", "If-None-Match" => etag }
    assert_response :not_modified
    assert_empty response.body

    get "/api/v1/messages?language=pl&q=greeting"
    assert_response :ok
    assert_equal [ "pl" ], json_body.fetch("items").map { |message| message.fetch("language") }

    get "/api/v1/messages/#{greeting_id}"
    assert_response :ok
    assert_equal "Hello", json_body.fetch("text")

    get "/api/v1/messages/#{uuid(299)}"
    assert_problem 404, "Not Found"
  end

  test "admin message lifecycle enforces role uniqueness and immutable audit records" do
    input = { key: "ui.notice", language: "en", text: "Initial", description: "Banner" }

    request_as(:post, "/api/v1/messages", caller: test_caller("regular"), params: input, as: :json)
    assert_problem 403, "Forbidden"

    admin = test_caller("admin", roles: [ "admin", "moderator" ])
    request_as(:post, "/api/v1/messages", caller: admin, params: input, as: :json)

    assert_response :created
    message_id = json_body.fetch("id")
    assert_equal "/api/v1/messages/#{message_id}", response.headers.fetch("Location")

    request_as(:post, "/api/v1/messages", caller: admin, params: input, as: :json)
    duplicate = assert_problem(409, "Conflict")
    assert_includes duplicate.fetch("detail"), "ui.notice"

    request_as(
      :put,
      "/api/v1/messages/#{message_id}",
      caller: admin,
      params: { key: "ui.notice.updated", language: "pl", text: "Zmieniono" },
      as: :json
    )
    assert_response :ok
    assert_equal "ui.notice.updated", json_body.fetch("key")
    assert_equal "pl", json_body.fetch("language")

    request_as(:delete, "/api/v1/messages/#{message_id}", caller: admin)
    assert_response :no_content
    assert_nil Stackverse::Sql.one("select 1 from messages where id = #{Stackverse::Sql.quote(message_id)}::uuid")

    audit = Stackverse::Sql.query("select action, detail from audit_entries order by created_at, id")
    assert_equal %w[message.created message.updated message.deleted], audit.map { |entry| entry.fetch("action") }
    assert_equal "ui.notice", JSON.parse(audit.first.fetch("detail")).fetch("key")
    assert_equal "ui.notice.updated", JSON.parse(audit.last.fetch("detail")).fetch("key")
  end
end
