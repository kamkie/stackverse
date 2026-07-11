defmodule StackverseBackendWeb.ApiAuthBookmarkTest do
  use StackverseBackendWeb.ConnCase, async: false

  @moduletag :database
  @moduletag :authenticated_api
  @moduletag skip: System.get_env("STACKVERSE_DB_TESTS") != "true"

  alias StackverseBackend.{Auth, Repo, TestAuth}
  alias StackverseBackend.Schemas.{Message, UserAccount}

  test "valid bearer claims provision identity while invalid claims and role boundaries fail closed",
       %{
         signing_key: key
       } do
    me = key |> auth_conn("demo", ["offline_access", "moderator"]) |> get("/api/v1/me")

    assert %{
             "username" => "demo",
             "name" => "Demo",
             "email" => "demo@example.test",
             "roles" => ["moderator"]
           } = json_response(me, 200)

    assert %UserAccount{status: "active", first_seen: first_seen, last_seen: last_seen} =
             Repo.get(UserAccount, "demo")

    assert DateTime.compare(last_seen, first_seen) in [:eq, :gt]

    denied = key |> auth_conn("demo") |> get("/api/v1/admin/users")
    assert %{"status" => 403, "title" => "Forbidden"} = json_response(denied, 403)

    malformed =
      build_conn()
      |> put_req_header("authorization", "Bearer not-a-jwt")
      |> get("/api/v1/me")

    assert %{"status" => 401, "title" => "Unauthorized"} = json_response(malformed, 401)
    assert malformed.halted

    assert :error = Auth.verify_bearer(TestAuth.token(key, "demo", [], %{"iss" => "wrong"}))
    assert :error = Auth.verify_bearer(TestAuth.token(key, "demo", [], %{"aud" => "wrong"}))
    assert :error = Auth.verify_bearer(TestAuth.token(key, "demo", [], %{"exp" => 0}))

    assert :error =
             Auth.verify_bearer(
               TestAuth.token(key, "demo", [], %{"nbf" => System.system_time(:second) + 60})
             )

    assert Auth.app_roles(["offline_access", "moderator", "admin"]) == ["admin", "moderator"]
  end

  test "readiness checks the PostgreSQL dependency", %{conn: conn} do
    assert conn |> get("/readyz") |> response(200) == ""
  end

  test "bookmark writes normalize input and return localized validation problems", %{
    signing_key: key
  } do
    insert_message("validation.title.required", "en", "A title is required")

    created =
      key
      |> auth_conn("owner")
      |> json_request(:post, "/api/v1/bookmarks", %{
        url: "https://example.com/bookmark",
        title: "  Phoenix guide  ",
        notes: "Readable",
        tags: [" Elixir ", "elixir", "phoenix"],
        visibility: "public",
        ignored: "contract permits unknown fields"
      })

    assert %{
             "id" => id,
             "owner" => "owner",
             "title" => "Phoenix guide",
             "tags" => ["elixir", "phoenix"],
             "visibility" => "public",
             "status" => "active"
           } = json_response(created, 201)

    assert get_resp_header(created, "location") == ["/api/v1/bookmarks/#{id}"]

    invalid =
      key
      |> auth_conn("owner")
      |> put_req_header("accept-language", "en")
      |> json_request(:post, "/api/v1/bookmarks", %{url: "https://example.com", title: ""})

    assert %{
             "status" => 400,
             "errors" => [
               %{
                 "field" => "title",
                 "messageKey" => "validation.title.required",
                 "message" => "A title is required"
               }
             ]
           } = json_response(invalid, 400)
  end

  test "v1 and v2 listings preserve visibility, filtering, deprecation, and cursor boundaries", %{
    signing_key: key
  } do
    first = create_bookmark(key, "owner", "First % guide", ["elixir", "phoenix"], "public")
    _private = create_bookmark(key, "owner", "Private", ["elixir"], "private")
    second = create_bookmark(key, "other", "Second guide", ["elixir", "phoenix"], "public")

    public =
      get(build_conn(), "/api/v1/bookmarks?visibility=public&tag=elixir&tag=phoenix&q=guide")

    assert %{"totalItems" => 2, "items" => public_items} = json_response(public, 200)
    assert MapSet.new(Enum.map(public_items, & &1["id"])) == MapSet.new([first, second])
    assert get_resp_header(public, "deprecation") == ["@1782864000"]
    assert get_resp_header(public, "sunset") == ["Thu, 01 Jul 2027 00:00:00 GMT"]

    own = key |> auth_conn("owner") |> get("/api/v1/bookmarks?q=%25+guide")
    assert %{"totalItems" => 1, "items" => [%{"id" => ^first}]} = json_response(own, 200)

    page1 = get(build_conn(), "/api/v2/bookmarks?visibility=public&size=1")
    assert %{"items" => [%{"id" => newest}], "nextCursor" => cursor} = json_response(page1, 200)

    page2 =
      get(
        build_conn(),
        "/api/v2/bookmarks?visibility=public&size=1&cursor=#{URI.encode_www_form(cursor)}"
      )

    assert %{"items" => [%{"id" => older}]} = json_response(page2, 200)
    assert MapSet.new([newest, older]) == MapSet.new([first, second])

    malformed = get(build_conn(), "/api/v2/bookmarks?visibility=public&cursor=bad")
    assert %{"status" => 400} = json_response(malformed, 400)

    repeated = get(build_conn(), "/api/v1/bookmarks?visibility=public&size=1&size=2")

    assert %{"status" => 400, "detail" => "size must not be repeated"} =
             json_response(repeated, 400)
  end

  test "ownership masking, tag counts, deletion, and hidden republish conflict hold at HTTP boundary",
       %{
         signing_key: key
       } do
    id = create_bookmark(key, "owner", "Owned", ["elixir", "phoenix"], "private")
    _other = create_bookmark(key, "owner", "Tagged", ["elixir"], "public")

    assert %{"status" => 404} =
             key |> auth_conn("intruder") |> get("/api/v1/bookmarks/#{id}") |> json_response(404)

    assert %{"status" => 404} =
             key
             |> auth_conn("intruder")
             |> json_request(:put, "/api/v1/bookmarks/#{id}", bookmark_body("Stolen", "public"))
             |> json_response(404)

    assert %{"status" => 404} =
             key
             |> auth_conn("intruder")
             |> delete("/api/v1/bookmarks/#{id}")
             |> json_response(404)

    tags = key |> auth_conn("owner") |> get("/api/v1/tags") |> json_response(200)

    assert tags == %{
             "tags" => [%{"tag" => "elixir", "count" => 2}, %{"tag" => "phoenix", "count" => 1}]
           }

    hidden =
      key
      |> auth_conn("moderator", ["moderator"])
      |> json_request(:put, "/api/v1/admin/bookmarks/#{id}/status", %{status: "hidden"})

    assert %{"status" => "hidden"} = json_response(hidden, 200)

    republish =
      key
      |> auth_conn("owner")
      |> json_request(:put, "/api/v1/bookmarks/#{id}", bookmark_body("Owned", "public"))

    assert %{"status" => 409, "title" => "Conflict"} = json_response(republish, 409)

    restored =
      key
      |> auth_conn("moderator", ["moderator"])
      |> json_request(:put, "/api/v1/admin/bookmarks/#{id}/status", %{status: "active"})

    assert %{"status" => "active", "visibility" => "private"} = json_response(restored, 200)

    deleted = key |> auth_conn("owner") |> delete("/api/v1/bookmarks/#{id}")
    assert response(deleted, 204) == ""
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

  defp create_bookmark(key, owner, title, tags, visibility) do
    conn =
      key
      |> auth_conn(owner)
      |> json_request(:post, "/api/v1/bookmarks", %{
        url: "https://example.com/#{Ecto.UUID.generate()}",
        title: title,
        tags: tags,
        visibility: visibility
      })

    json_response(conn, 201)["id"]
  end

  defp bookmark_body(title, visibility) do
    %{url: "https://example.com/updated", title: title, tags: ["elixir"], visibility: visibility}
  end

  defp insert_message(key, language, text) do
    %Message{}
    |> Message.changeset(%{key: key, language: language, text: text})
    |> Repo.insert!()
  end
end
