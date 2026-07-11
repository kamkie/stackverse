defmodule StackverseBackendWeb.ApiMessageAdminTest do
  use StackverseBackendWeb.ConnCase, async: false

  @moduletag :database
  @moduletag :authenticated_api
  @moduletag skip: System.get_env("STACKVERSE_DB_TESTS") != "true"

  import StackverseBackend.TestHTTP

  alias StackverseBackend.Repo
  alias StackverseBackend.Schemas.AuditEntry

  test "public message reads provide filters, language fallback, and stateless ETags", %{
    signing_key: key
  } do
    title_en = create_message(key, "ui.title", "en", "Title")
    _title_pl = create_message(key, "ui.title", "pl", "Tytuł")
    only_en = create_message(key, "ui.only-en", "en", "English fallback")

    list = get(build_conn(), "/api/v1/messages?language=en&q=title")
    assert %{"totalItems" => 1, "items" => [%{"id" => ^title_en}]} = json_response(list, 200)
    [etag] = get_resp_header(list, "etag")
    assert get_resp_header(list, "cache-control") == ["no-cache"]

    unchanged =
      build_conn()
      |> put_req_header("if-none-match", "\"other\", #{etag}")
      |> get("/api/v1/messages?language=en&q=title")

    assert response(unchanged, 304) == ""

    bundle =
      build_conn()
      |> put_req_header("accept-language", "de;q=0.9, pl-PL;q=0.8, en;q=0.7")
      |> get("/api/v1/messages/bundle")

    assert %{
             "language" => "pl",
             "messages" => %{
               "ui.title" => "Tytuł",
               "ui.only-en" => "English fallback"
             }
           } = json_response(bundle, 200)

    assert get_resp_header(bundle, "content-language") == ["pl"]

    item = get(build_conn(), "/api/v1/messages/#{only_en}")
    assert %{"text" => "English fallback"} = json_response(item, 200)
    [item_etag] = get_resp_header(item, "etag")

    assert build_conn()
           |> put_req_header("if-none-match", item_etag)
           |> get("/api/v1/messages/#{only_en}")
           |> response(304) == ""

    assert %{"status" => 404} =
             get(build_conn(), "/api/v1/messages/#{Ecto.UUID.generate()}")
             |> json_response(404)
  end

  test "admin message mutations enforce roles, conflicts, validation, and immutable audits", %{
    signing_key: key
  } do
    denied =
      key
      |> auth_conn("regular")
      |> json_request(:post, "/api/v1/messages", message_body("ui.denied", "en", "No"))

    assert %{"status" => 403} = json_response(denied, 403)

    id = create_message(key, "ui.first", "en", "First", "Translator context")

    duplicate =
      key
      |> auth_conn("admin", ["admin", "moderator"])
      |> json_request(:post, "/api/v1/messages", message_body("ui.first", "en", "Again"))

    assert %{"status" => 409, "title" => "Conflict"} = json_response(duplicate, 409)

    invalid =
      key
      |> auth_conn("admin", ["admin", "moderator"])
      |> json_request(:put, "/api/v1/messages/#{id}", message_body("Bad Key", "english", ""))

    assert %{"status" => 400, "errors" => errors} = json_response(invalid, 400)

    assert MapSet.new(Enum.map(errors, & &1["messageKey"])) ==
             MapSet.new([
               "validation.message.key.invalid",
               "validation.message.language.invalid",
               "validation.message.text.required"
             ])

    updated =
      key
      |> auth_conn("admin", ["admin", "moderator"])
      |> json_request(:put, "/api/v1/messages/#{id}", message_body("ui.first", "en", "Updated"))

    assert %{"id" => ^id, "text" => "Updated"} = json_response(updated, 200)

    deleted =
      key
      |> auth_conn("admin", ["admin", "moderator"])
      |> delete("/api/v1/messages/#{id}")

    assert response(deleted, 204) == ""

    actions = AuditEntry |> Repo.all() |> Enum.map(& &1.action) |> Enum.frequencies()
    assert actions == %{"message.created" => 1, "message.updated" => 1, "message.deleted" => 1}

    missing =
      key
      |> auth_conn("admin", ["admin", "moderator"])
      |> delete("/api/v1/messages/#{Ecto.UUID.generate()}")

    assert %{"status" => 404} = json_response(missing, 404)
  end

  test "account blocking applies on the next authenticated request and admin views are filterable",
       %{
         signing_key: key
       } do
    assert %{"username" => "admin"} =
             key
             |> auth_conn("admin", ["admin", "moderator"])
             |> get("/api/v1/me")
             |> json_response(200)

    assert %{"username" => "victim"} =
             key |> auth_conn("victim") |> get("/api/v1/me") |> json_response(200)

    _bookmark = create_bookmark(key, "victim", ["elixir", "admin"])

    users =
      key
      |> auth_conn("admin", ["admin", "moderator"])
      |> get("/api/v1/admin/users?q=ICT&status=active")
      |> json_response(200)

    assert %{
             "totalItems" => 1,
             "items" => [%{"username" => "victim", "bookmarkCount" => 1}]
           } = users

    assert %{"username" => "victim", "status" => "active"} =
             key
             |> auth_conn("admin", ["admin", "moderator"])
             |> get("/api/v1/admin/users/victim")
             |> json_response(200)

    assert %{"status" => 404} =
             key
             |> auth_conn("admin", ["admin", "moderator"])
             |> get("/api/v1/admin/users/missing")
             |> json_response(404)

    self_block =
      key
      |> auth_conn("admin", ["admin", "moderator"])
      |> json_request(:put, "/api/v1/admin/users/admin/status", %{
        status: "blocked",
        reason: "self"
      })

    assert %{"status" => 409} = json_response(self_block, 409)

    no_reason =
      key
      |> auth_conn("admin", ["admin", "moderator"])
      |> json_request(:put, "/api/v1/admin/users/victim/status", %{status: "blocked"})

    assert %{"status" => 400} = json_response(no_reason, 400)

    blocked =
      key
      |> auth_conn("admin", ["admin", "moderator"])
      |> json_request(:put, "/api/v1/admin/users/victim/status", %{
        status: "blocked",
        reason: "Policy violation"
      })

    assert %{"status" => "blocked", "blockedReason" => "Policy violation"} =
             json_response(blocked, 200)

    rejected = key |> auth_conn("victim") |> get("/api/v1/me")
    assert %{"status" => 403, "detail" => "error.account.blocked"} = json_response(rejected, 403)
    assert rejected.halted

    unblocked =
      key
      |> auth_conn("admin", ["admin", "moderator"])
      |> json_request(:put, "/api/v1/admin/users/victim/status", %{status: "active"})

    assert %{"status" => "active"} = json_response(unblocked, 200)

    assert %{"username" => "victim"} =
             key |> auth_conn("victim") |> get("/api/v1/me") |> json_response(200)
  end

  test "audit filtering and moderator stats expose typed pages, zero-filled days, and ETags", %{
    signing_key: key
  } do
    _id = create_message(key, "audit.example", "en", "Audited")
    _bookmark = create_bookmark(key, "admin", ["elixir", "stats"])

    audit =
      key
      |> auth_conn("admin", ["admin", "moderator"])
      |> get("/api/v1/admin/audit-log?actor=admin&action=message.created&targetType=message")
      |> json_response(200)

    assert %{
             "totalItems" => 1,
             "items" => [
               %{
                 "actor" => "admin",
                 "action" => "message.created",
                 "targetType" => "message"
               }
             ]
           } = audit

    invalid_time =
      key
      |> auth_conn("admin", ["admin", "moderator"])
      |> get("/api/v1/admin/audit-log?from=yesterday")

    assert %{"status" => 400} = json_response(invalid_time, 400)

    stats = key |> auth_conn("moderator", ["moderator"]) |> get("/api/v1/admin/stats")
    body = json_response(stats, 200)

    assert body["totals"]["users"] == 2
    assert body["totals"]["bookmarks"] == 1
    assert length(body["daily"]) == 30
    assert List.last(body["daily"])["bookmarksCreated"] == 1

    assert [%{"tag" => "elixir", "count" => 1}, %{"tag" => "stats", "count" => 1}] =
             body["topTags"]

    [etag] = get_resp_header(stats, "etag")

    assert key
           |> auth_conn("moderator", ["moderator"])
           |> put_req_header("if-none-match", etag)
           |> get("/api/v1/admin/stats")
           |> response(304) == ""
  end

  defp create_message(key, message_key, language, text, description \\ nil) do
    body = message_body(message_key, language, text) |> Map.put(:description, description)

    conn =
      key
      |> auth_conn("admin", ["admin", "moderator"])
      |> json_request(:post, "/api/v1/messages", body)

    json_response(conn, 201)["id"]
  end

  defp message_body(key, language, text), do: %{key: key, language: language, text: text}

  defp create_bookmark(key, owner, tags) do
    conn =
      key
      |> auth_conn(owner)
      |> json_request(:post, "/api/v1/bookmarks", %{
        url: "https://example.com/#{Ecto.UUID.generate()}",
        title: "Stats bookmark",
        tags: tags,
        visibility: "public"
      })

    json_response(conn, 201)["id"]
  end
end
