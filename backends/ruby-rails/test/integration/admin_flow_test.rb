require "test_helper"

class AdminFlowTest < StackverseIntegrationTest
  test "user administration supports search block unblock and filtered audit lookup" do
    insert_user("admin", last_seen: Time.utc(2026, 7, 3))
    insert_user("target-user", last_seen: Time.utc(2026, 7, 2))
    insert_user("blocked-user", status: "blocked", blocked_reason: "existing", last_seen: Time.utc(2026, 7, 1))
    insert_bookmark(id: uuid(300), owner: "target-user", visibility: "private")
    admin = test_caller("admin", roles: [ "admin", "moderator" ])

    request_as(:get, "/api/v1/admin/users", caller: test_caller("regular"))
    assert_problem 403, "Forbidden"

    request_as(:get, "/api/v1/admin/users?q=target&status=active", caller: admin)
    assert_response :ok
    assert_equal [ "target-user" ], json_body.fetch("items").map { |user| user.fetch("username") }
    assert_equal 1, json_body.fetch("items").first.fetch("bookmarkCount")

    request_as(:get, "/api/v1/admin/users/target-user", caller: admin)
    assert_response :ok
    assert_equal "target-user", json_body.fetch("username")

    request_as(:get, "/api/v1/admin/users/missing", caller: admin)
    assert_problem 404, "Not Found"

    request_as(
      :put,
      "/api/v1/admin/users/target-user/status",
      caller: admin,
      params: { status: "blocked", reason: "Repeated abuse" },
      as: :json
    )
    assert_response :ok
    assert_equal "blocked", json_body.fetch("status")
    assert_equal "Repeated abuse", json_body.fetch("blockedReason")

    request_as(
      :put,
      "/api/v1/admin/users/admin/status",
      caller: admin,
      params: { status: "blocked", reason: "self" },
      as: :json
    )
    assert_problem 409, "Conflict"

    request_as(
      :put,
      "/api/v1/admin/users/target-user/status",
      caller: admin,
      params: { status: "active" },
      as: :json
    )
    assert_response :ok
    assert_equal "active", json_body.fetch("status")
    refute json_body.key?("blockedReason")

    request_as(
      :get,
      "/api/v1/admin/audit-log?actor=admin&action=user.blocked&targetType=user&targetId=target-user&from=2020-01-01T00:00:00Z&to=2030-01-01T00:00:00Z",
      caller: admin
    )
    assert_response :ok
    assert_equal [ "user.blocked" ], json_body.fetch("items").map { |entry| entry.fetch("action") }
    assert_equal "Repeated abuse", json_body.fetch("items").first.fetch("detail").fetch("reason")
  end

  test "moderator stats zero fill thirty days aggregate totals and revalidate with etags" do
    today = Time.now.utc.to_date
    insert_user("today-user", last_seen: today.to_time.utc + 10.hours)
    insert_user("yesterday-user", last_seen: (today - 1).to_time.utc + 12.hours)
    insert_bookmark(
      id: uuid(310),
      owner: "today-user",
      tags: %w[ruby rails],
      visibility: "public",
      created_at: today.to_time.utc + 9.hours
    )
    insert_bookmark(
      id: uuid(311),
      owner: "yesterday-user",
      tags: %w[ruby],
      visibility: "private",
      status: "hidden",
      created_at: (today - 1).to_time.utc + 8.hours
    )
    insert_report(id: uuid(312), bookmark_id: uuid(310), reporter: "reporter")
    moderator = test_caller("moderator", roles: [ "moderator" ])

    request_as(:get, "/api/v1/admin/stats", caller: moderator)

    assert_response :ok
    stats = json_body
    assert_equal(
      {
        "users" => 2,
        "bookmarks" => 2,
        "publicBookmarks" => 1,
        "hiddenBookmarks" => 1,
        "openReports" => 1
      },
      stats.fetch("totals")
    )
    assert_equal 30, stats.fetch("daily").length
    assert_equal (today - 29).iso8601, stats.fetch("daily").first.fetch("date")
    assert_equal today.iso8601, stats.fetch("daily").last.fetch("date")
    assert_equal 1, stats.fetch("daily").last.fetch("bookmarksCreated")
    assert_equal 1, stats.fetch("daily").last.fetch("activeUsers")
    assert_equal [ { "tag" => "ruby", "count" => 2 }, { "tag" => "rails", "count" => 1 } ], stats.fetch("topTags")
    etag = response.headers.fetch("ETag")

    request_as(:get, "/api/v1/admin/stats", caller: moderator, headers: { "If-None-Match" => etag })
    assert_response :not_modified
    assert_empty response.body

    request_as(:get, "/api/v1/admin/stats", caller: test_caller("regular"))
    assert_problem 403, "Forbidden"
  end
end
