require "test_helper"

class BookmarksFlowTest < StackverseIntegrationTest
  test "public listings filter correctly and the v2 cursor is stable across inserts" do
    newest_id = insert_bookmark(
      id: uuid(1),
      owner: "alice",
      title: "Rails Contract",
      notes: "A precise comparison",
      tags: %w[ruby rails],
      created_at: Time.utc(2026, 7, 3)
    )
    older_id = insert_bookmark(
      id: uuid(2),
      owner: "bob",
      title: "Ruby Notes",
      tags: %w[ruby],
      created_at: Time.utc(2026, 7, 2)
    )
    insert_bookmark(id: uuid(3), owner: "alice", visibility: "private", created_at: Time.utc(2026, 7, 5))
    insert_bookmark(id: uuid(4), owner: "alice", status: "hidden", created_at: Time.utc(2026, 7, 4))

    get "/api/v1/bookmarks?visibility=public&tag=ruby&tag=rails&q=Rails"

    assert_response :ok
    assert_equal [ newest_id ], json_body.fetch("items").map { |item| item.fetch("id") }
    assert_equal "@1782864000", response.headers.fetch("Deprecation")
    assert_equal "Thu, 01 Jul 2027 00:00:00 GMT", response.headers.fetch("Sunset")
    assert_equal '</api/v2/bookmarks>; rel="successor-version"', response.headers.fetch("Link")

    get "/api/v2/bookmarks?visibility=public&size=1"

    first_page = json_body
    assert_equal [ newest_id ], first_page.fetch("items").map { |item| item.fetch("id") }
    cursor = first_page.fetch("nextCursor")

    insert_bookmark(id: uuid(5), owner: "carol", title: "Inserted later", created_at: Time.utc(2026, 7, 4))
    get "/api/v2/bookmarks?visibility=public&size=1&cursor=#{Rack::Utils.escape(cursor)}"

    second_page = json_body
    assert_equal [ older_id ], second_page.fetch("items").map { |item| item.fetch("id") }
    refute second_page.key?("nextCursor")
  end

  test "bookmark lifecycle enforces ownership while normalizing client input" do
    alice = test_caller("alice")
    bob = test_caller("bob")

    request_as(
      :post,
      "/api/v1/bookmarks",
      caller: alice,
      params: {
        url: " https://example.com/articles/1 ",
        title: " Rails Testing ",
        tags: [ "Ruby", " ruby ", "rails" ],
        ignored: "contract permits unknown fields"
      },
      as: :json
    )

    assert_response :created
    created = json_body
    assert_equal "Rails Testing", created.fetch("title")
    assert_equal %w[ruby rails], created.fetch("tags")
    assert_equal "private", created.fetch("visibility")
    refute created.key?("ignored")
    assert_equal "/api/v1/bookmarks/#{created.fetch("id")}", response.headers.fetch("Location")

    request_as(:get, "/api/v1/bookmarks/#{created.fetch("id")}", caller: bob)
    assert_problem 404, "Not Found"

    request_as(:get, "/api/v1/bookmarks/#{created.fetch("id")}", caller: alice)
    assert_response :ok
    assert_equal "alice", json_body.fetch("owner")

    replacement = {
      url: "https://example.com/revised",
      title: "Revised",
      notes: "owner-only update",
      tags: [ "rails" ],
      visibility: "public"
    }
    request_as(:put, "/api/v1/bookmarks/#{created.fetch("id")}", caller: bob, params: replacement, as: :json)
    assert_problem 404, "Not Found"

    request_as(:put, "/api/v1/bookmarks/#{created.fetch("id")}", caller: alice, params: replacement, as: :json)
    assert_response :ok
    assert_equal "public", json_body.fetch("visibility")

    request_as(:delete, "/api/v1/bookmarks/#{created.fetch("id")}", caller: alice)
    assert_response :no_content

    request_as(:get, "/api/v1/bookmarks/#{created.fetch("id")}", caller: alice)
    assert_problem 404, "Not Found"
  end

  test "authenticated listings remain owner scoped with and without a visibility filter" do
    alice_public = insert_bookmark(id: uuid(30), owner: "alice", visibility: "public")
    alice_private = insert_bookmark(id: uuid(31), owner: "alice", visibility: "private")
    insert_bookmark(id: uuid(32), owner: "bob", visibility: "private")
    alice = test_caller("alice")

    request_as(:get, "/api/v1/bookmarks", caller: alice)
    assert_response :ok
    assert_equal [ alice_public, alice_private ].sort, json_body.fetch("items").map { |item| item.fetch("id") }.sort

    request_as(:get, "/api/v1/bookmarks?visibility=private", caller: alice)
    assert_response :ok
    assert_equal [ alice_private ], json_body.fetch("items").map { |item| item.fetch("id") }
  end

  test "validation errors and hidden publish conflicts use localized problem details" do
    insert_message(id: uuid(10), key: "validation.url.required", language: "pl", text: "Adres jest wymagany")
    insert_message(id: uuid(11), key: "validation.title.required", language: "pl", text: "Tytuł jest wymagany")
    insert_message(id: uuid(12), key: "error.bookmark.hidden-publish", language: "pl", text: "Ukrytej zakładki nie można opublikować")
    alice = test_caller("alice")

    request_as(
      :post,
      "/api/v1/bookmarks",
      caller: alice,
      headers: { "Accept-Language" => "pl-PL, en;q=0.5" },
      params: {},
      as: :json
    )

    problem = assert_problem(400, "Bad Request")
    messages = problem.fetch("errors").to_h { |error| [ error.fetch("messageKey"), error.fetch("message") ] }
    assert_equal "Adres jest wymagany", messages.fetch("validation.url.required")
    assert_equal "Tytuł jest wymagany", messages.fetch("validation.title.required")

    bookmark_id = insert_bookmark(id: uuid(13), owner: "alice", visibility: "private", status: "hidden")
    request_as(
      :put,
      "/api/v1/bookmarks/#{bookmark_id}",
      caller: alice,
      headers: { "Accept-Language" => "pl" },
      params: { url: "https://example.com", title: "Hidden", visibility: "public" },
      as: :json
    )

    conflict = assert_problem(409, "Conflict")
    assert_equal "Ukrytej zakładki nie można opublikować", conflict.fetch("detail")
  end

  test "private listings require authentication and reject unknown visibility first" do
    get "/api/v1/bookmarks"
    assert_problem 401, "Unauthorized"

    get "/api/v1/bookmarks?visibility=shared"
    assert_problem 400, "Bad Request"
  end

  test "tag counts are scoped to the caller and sorted by usage" do
    insert_bookmark(id: uuid(20), owner: "alice", tags: %w[ruby rails])
    insert_bookmark(id: uuid(21), owner: "alice", tags: %w[ruby])
    insert_bookmark(id: uuid(22), owner: "bob", tags: %w[ruby private])

    request_as(:get, "/api/v1/tags", caller: test_caller("alice"))

    assert_response :ok
    assert_equal(
      [ { "tag" => "ruby", "count" => 2 }, { "tag" => "rails", "count" => 1 } ],
      json_body.fetch("tags")
    )
  end
end
