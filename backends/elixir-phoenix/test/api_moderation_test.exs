defmodule StackverseBackendWeb.ApiModerationTest do
  use StackverseBackendWeb.ConnCase, async: false

  @moduletag :database
  @moduletag :authenticated_api
  @moduletag skip: System.get_env("STACKVERSE_DB_TESTS") != "true"

  alias StackverseBackend.{Repo, TestAuth}
  alias StackverseBackend.Schemas.{AuditEntry, Bookmark, Report}

  test "reporters can create, filter, revise, withdraw, and refile only visible open reports", %{
    signing_key: key
  } do
    public_id = create_bookmark(key, "owner", "public")
    private_id = create_bookmark(key, "owner", "private")

    report = create_report(key, "reporter", public_id, "spam", "Initial")

    duplicate =
      key
      |> auth_conn("reporter")
      |> json_request(:post, "/api/v1/bookmarks/#{public_id}/reports", %{
        reason: "offensive"
      })

    assert %{"status" => 409} = json_response(duplicate, 409)

    masked =
      key
      |> auth_conn("other")
      |> json_request(:put, "/api/v1/reports/#{report}", %{reason: "other"})

    assert %{"status" => 404} = json_response(masked, 404)

    updated =
      key
      |> auth_conn("reporter")
      |> json_request(:put, "/api/v1/reports/#{report}", %{
        reason: "broken-link",
        comment: "Rechecked"
      })

    assert %{"reason" => "broken-link", "comment" => "Rechecked", "status" => "open"} =
             json_response(updated, 200)

    mine =
      key
      |> auth_conn("reporter")
      |> get("/api/v1/reports?status=open&size=10")
      |> json_response(200)

    assert %{"totalItems" => 1, "items" => [%{"id" => ^report}]} = mine

    assert key
           |> auth_conn("reporter")
           |> delete("/api/v1/reports/#{report}")
           |> response(204) == ""

    refute Repo.get(Report, report)
    _refiled = create_report(key, "reporter", public_id, "other", nil)

    private_attempt =
      key
      |> auth_conn("reporter")
      |> json_request(:post, "/api/v1/bookmarks/#{private_id}/reports", %{reason: "spam"})

    assert %{"status" => 404} = json_response(private_attempt, 404)

    hide_bookmark(key, public_id)

    hidden_attempt =
      key
      |> auth_conn("another")
      |> json_request(:post, "/api/v1/bookmarks/#{public_id}/reports", %{reason: "spam"})

    assert %{"status" => 404} = json_response(hidden_attempt, 404)
  end

  test "actioning a report hides the bookmark and atomically resolves open siblings with audits",
       %{
         signing_key: key
       } do
    bookmark_id = create_bookmark(key, "owner", "public")
    first = create_report(key, "alice", bookmark_id, "spam", nil)
    second = create_report(key, "bob", bookmark_id, "offensive", "Unsafe")

    resolved =
      key
      |> auth_conn("moderator", ["moderator"])
      |> json_request(:put, "/api/v1/admin/reports/#{first}", %{
        resolution: "actioned",
        note: "Confirmed"
      })

    assert %{
             "status" => "actioned",
             "resolvedBy" => "moderator",
             "resolutionNote" => "Confirmed"
           } = json_response(resolved, 200)

    assert %Report{status: "actioned", resolved_by: "moderator", resolution_note: "Confirmed"} =
             Repo.get(Report, second)

    assert %Bookmark{status: "hidden"} = Repo.get(Bookmark, bookmark_id)

    assert %{"status" => 404} =
             get(build_conn(), "/api/v1/bookmarks/#{bookmark_id}") |> json_response(404)

    assert %{"status" => "hidden"} =
             key
             |> auth_conn("owner")
             |> get("/api/v1/bookmarks/#{bookmark_id}")
             |> json_response(200)

    actions = Repo.all(AuditEntry) |> Enum.map(& &1.action) |> Enum.frequencies()
    assert actions["report.resolved"] == 2
    assert actions["bookmark.status-changed"] == 1

    queue =
      key
      |> auth_conn("moderator", ["moderator"])
      |> get("/api/v1/admin/reports?status=actioned")
      |> json_response(200)

    assert queue["totalItems"] == 2
  end

  test "resolution decisions are revisable, reopening clears fields, and duplicate-open protection remains",
       %{
         signing_key: key
       } do
    bookmark_id = create_bookmark(key, "owner", "public")
    old_report = create_report(key, "reporter", bookmark_id, "spam", nil)

    dismissed = resolve_report(key, old_report, "dismissed", "Not substantiated")
    assert %{"status" => "dismissed", "resolutionNote" => "Not substantiated"} = dismissed

    assert %{"status" => 409} =
             key
             |> auth_conn("reporter")
             |> json_request(:put, "/api/v1/reports/#{old_report}", %{reason: "other"})
             |> json_response(409)

    reopened = resolve_report(key, old_report, "open", "ignored")
    assert %{"status" => "open"} = reopened
    refute Map.has_key?(reopened, "resolvedBy")
    refute Map.has_key?(reopened, "resolvedAt")
    refute Map.has_key?(reopened, "resolutionNote")

    _dismissed_again = resolve_report(key, old_report, "dismissed", nil)
    current = create_report(key, "reporter", bookmark_id, "other", nil)

    duplicate_reopen =
      key
      |> auth_conn("moderator", ["moderator"])
      |> json_request(:put, "/api/v1/admin/reports/#{old_report}", %{resolution: "open"})

    assert %{"status" => 409} = json_response(duplicate_reopen, 409)
    assert %Report{status: "open"} = Repo.get(Report, current)

    actioned = resolve_report(key, old_report, "actioned", "Escalated")
    assert %{"status" => "actioned"} = actioned
    assert %Bookmark{status: "hidden"} = Repo.get(Bookmark, bookmark_id)

    dismissed_after_action = resolve_report(key, old_report, "dismissed", "Revised")
    assert %{"status" => "dismissed"} = dismissed_after_action
    assert %Bookmark{status: "hidden"} = Repo.get(Bookmark, bookmark_id)

    restored =
      key
      |> auth_conn("moderator", ["moderator"])
      |> json_request(:put, "/api/v1/admin/bookmarks/#{bookmark_id}/status", %{
        status: "active",
        note: "Manual restore"
      })

    assert %{"status" => "active", "visibility" => "public"} = json_response(restored, 200)
  end

  defp auth_conn(key, username, roles \\ []) do
    build_conn()
    |> put_req_header("authorization", "Bearer #{TestAuth.token(key, username, roles)}")
  end

  defp json_request(conn, method, path, body) do
    conn = put_req_header(conn, "content-type", "application/json")
    encoded = Jason.encode!(body)

    case method do
      :post -> post(conn, path, encoded)
      :put -> put(conn, path, encoded)
    end
  end

  defp create_bookmark(key, owner, visibility) do
    conn =
      key
      |> auth_conn(owner)
      |> json_request(:post, "/api/v1/bookmarks", %{
        url: "https://example.com/#{Ecto.UUID.generate()}",
        title: "Moderation target",
        tags: ["moderation"],
        visibility: visibility
      })

    json_response(conn, 201)["id"]
  end

  defp create_report(key, reporter, bookmark_id, reason, comment) do
    conn =
      key
      |> auth_conn(reporter)
      |> json_request(:post, "/api/v1/bookmarks/#{bookmark_id}/reports", %{
        reason: reason,
        comment: comment
      })

    json_response(conn, 201)["id"]
  end

  defp resolve_report(key, report_id, resolution, note) do
    key
    |> auth_conn("moderator", ["moderator"])
    |> json_request(:put, "/api/v1/admin/reports/#{report_id}", %{
      resolution: resolution,
      note: note
    })
    |> json_response(200)
  end

  defp hide_bookmark(key, bookmark_id) do
    key
    |> auth_conn("moderator", ["moderator"])
    |> json_request(:put, "/api/v1/admin/bookmarks/#{bookmark_id}/status", %{status: "hidden"})
    |> json_response(200)
  end
end
