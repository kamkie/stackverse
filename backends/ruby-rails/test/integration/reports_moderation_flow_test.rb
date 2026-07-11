require "test_helper"

class ReportsModerationFlowTest < StackverseIntegrationTest
  test "reporters can create revise list and withdraw only their own open reports" do
    bookmark_id = insert_bookmark(id: uuid(100), owner: "owner")
    reporter = test_caller("reporter")

    request_as(
      :post,
      "/api/v1/bookmarks/#{bookmark_id}/reports",
      caller: reporter,
      params: { reason: "spam", comment: "Repeated promotions" },
      as: :json
    )

    assert_response :created
    report_id = json_body.fetch("id")

    request_as(
      :post,
      "/api/v1/bookmarks/#{bookmark_id}/reports",
      caller: reporter,
      params: { reason: "other" },
      as: :json
    )
    assert_problem 409, "Conflict"

    request_as(:get, "/api/v1/reports?status=open", caller: reporter)
    assert_response :ok
    assert_equal [ report_id ], json_body.fetch("items").map { |item| item.fetch("id") }

    request_as(
      :put,
      "/api/v1/reports/#{report_id}",
      caller: reporter,
      params: { reason: "broken-link", comment: "Now unavailable" },
      as: :json
    )
    assert_response :ok
    assert_equal "broken-link", json_body.fetch("reason")

    request_as(
      :put,
      "/api/v1/reports/#{report_id}",
      caller: test_caller("someone-else"),
      params: { reason: "other" },
      as: :json
    )
    assert_problem 404, "Not Found"

    request_as(:delete, "/api/v1/reports/#{report_id}", caller: reporter)
    assert_response :no_content
    assert_nil Stackverse::Sql.one("select 1 from reports where id = #{Stackverse::Sql.quote(report_id)}::uuid")

    request_as(
      :post,
      "/api/v1/bookmarks/#{bookmark_id}/reports",
      caller: reporter,
      params: { reason: "other" },
      as: :json
    )
    assert_response :created

    replacement_id = json_body.fetch("id")
    Stackverse::Sql.connection.execute(<<~SQL.squish)
      update reports set status = 'dismissed' where id = #{Stackverse::Sql.quote(replacement_id)}::uuid
    SQL
    request_as(:delete, "/api/v1/reports/#{replacement_id}", caller: reporter)
    assert_problem 409, "Conflict"
  end

  test "private and hidden bookmarks are masked from report creation" do
    private_id = insert_bookmark(id: uuid(110), owner: "owner", visibility: "private")
    hidden_id = insert_bookmark(id: uuid(111), owner: "owner", status: "hidden")
    reporter = test_caller("reporter")

    [ private_id, hidden_id ].each do |bookmark_id|
      request_as(
        :post,
        "/api/v1/bookmarks/#{bookmark_id}/reports",
        caller: reporter,
        params: { reason: "spam" },
        as: :json
      )
      assert_problem 404, "Not Found"
    end
  end

  test "actioned moderation hides the bookmark resolves siblings and can be revised to open" do
    bookmark_id = insert_bookmark(id: uuid(120), owner: "owner", visibility: "public")
    first_id = insert_report(id: uuid(121), bookmark_id: bookmark_id, reporter: "alice", created_at: Time.utc(2026, 7, 1))
    second_id = insert_report(id: uuid(122), bookmark_id: bookmark_id, reporter: "bob", created_at: Time.utc(2026, 7, 2))
    moderator = test_caller("mod", roles: [ "moderator" ])

    request_as(
      :put,
      "/api/v1/admin/reports/#{first_id}",
      caller: moderator,
      params: { resolution: "actioned", note: "Policy violation" },
      as: :json
    )

    assert_response :ok
    assert_equal "actioned", json_body.fetch("status")
    reports = Stackverse::Sql.query("select * from reports order by id")
    assert_equal %w[actioned actioned], reports.map { |report| report.fetch("status") }
    assert reports.all? { |report| report.fetch("resolved_by") == "mod" }
    assert_equal "hidden", Stackverse::Sql.one("select status from bookmarks where id = #{Stackverse::Sql.quote(bookmark_id)}::uuid").fetch("status")

    audit_actions = Stackverse::Sql.query("select action from audit_entries order by created_at, id").map { |row| row.fetch("action") }
    assert_equal 2, audit_actions.count("report.resolved")
    assert_equal 1, audit_actions.count("bookmark.status-changed")

    request_as(
      :put,
      "/api/v1/admin/reports/#{first_id}",
      caller: moderator,
      params: { resolution: "open", note: "ignored while reopening" },
      as: :json
    )

    reopened = json_body
    assert_equal "open", reopened.fetch("status")
    refute reopened.key?("resolvedBy")
    refute reopened.key?("resolvedAt")
    refute reopened.key?("resolutionNote")
    assert_equal "actioned", Stackverse::Sql.one("select status from reports where id = #{Stackverse::Sql.quote(second_id)}::uuid").fetch("status")
    assert_equal "hidden", Stackverse::Sql.one("select status from bookmarks where id = #{Stackverse::Sql.quote(bookmark_id)}::uuid").fetch("status")
    assert_equal 1, Stackverse::Sql.one("select count(*)::int as count from audit_entries where action = 'report.reopened'").fetch("count")
  end

  test "moderator endpoints enforce roles and explicit bookmark status changes are audited" do
    bookmark_id = insert_bookmark(id: uuid(130), owner: "owner", visibility: "public")
    report_id = insert_report(id: uuid(131), bookmark_id: bookmark_id, reporter: "reporter")

    request_as(:get, "/api/v1/admin/reports", caller: test_caller("regular"))
    assert_problem 403, "Forbidden"

    moderator = test_caller("mod", roles: [ "moderator" ])
    request_as(:get, "/api/v1/admin/reports", caller: moderator)
    assert_response :ok
    assert_equal [ report_id ], json_body.fetch("items").map { |item| item.fetch("id") }

    request_as(
      :put,
      "/api/v1/admin/bookmarks/#{bookmark_id}/status",
      caller: moderator,
      params: { status: "hidden", note: "manual review" },
      as: :json
    )
    assert_response :ok
    assert_equal "hidden", json_body.fetch("status")
    assert_equal "public", json_body.fetch("visibility")

    request_as(
      :put,
      "/api/v1/admin/bookmarks/#{bookmark_id}/status",
      caller: moderator,
      params: { status: "active" },
      as: :json
    )
    assert_response :ok
    assert_equal "active", json_body.fetch("status")
    assert_equal "public", json_body.fetch("visibility")
    assert_equal 2, Stackverse::Sql.one("select count(*)::int as count from audit_entries where action = 'bookmark.status-changed'").fetch("count")
  end
end
