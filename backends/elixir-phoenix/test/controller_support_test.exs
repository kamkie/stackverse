defmodule StackverseBackendWeb.ControllerSupportTest do
  use StackverseBackendWeb.ConnCase, async: false

  alias StackverseBackend.{Cursor, Problem}
  alias StackverseBackendWeb.ControllerSupport, as: Support

  test "authorization helpers distinguish missing callers, roles, and successful access", %{
    conn: conn
  } do
    assert {:error, missing} = Support.require_caller(conn)
    assert %{"status" => 401} = json_response(missing, 401)

    caller = %{username: "demo", roles: ["moderator"]}
    assigned = assign(conn, :caller, caller)
    assert {:ok, ^caller} = Support.require_caller(assigned)
    assert {:ok, ^caller} = Support.require_role(assigned, "moderator")

    assert {:error, denied} = Support.require_role(assigned, "admin")
    assert %{"status" => 403, "title" => "Forbidden"} = json_response(denied, 403)
  end

  test "paging and single-value helpers validate repetitions, integers, ranges, and lengths", %{
    conn: conn
  } do
    assert {:ok, %{page: 0, size: 20}} = Support.paging(conn)

    assert {:ok, %{page: 2, size: 100}} =
             Support.paging(%{conn | query_string: "page=2&size=100"})

    for query <- ["page=-1", "page=wat", "size=0", "size=101", "size=1&size=2"] do
      assert {:error, failed} = Support.paging(%{conn | query_string: query})
      assert failed.status == 400
    end

    assert {:ok, nil} = Support.optional_enum(conn, "status", ~w[active blocked])

    assert {:ok, "active"} =
             Support.optional_enum(
               %{conn | query_string: "status=active"},
               "status",
               ~w[active blocked]
             )

    assert {:error, repeated} =
             Support.optional_enum(
               %{conn | query_string: "status=active&status=blocked"},
               "status",
               ~w[active blocked]
             )

    assert repeated.status == 400
    assert :ok = Support.max_length(conn, nil, 3, "q")
    assert :ok = Support.max_length(conn, "abc", 3, "q")
    assert {:error, too_long} = Support.max_length(conn, "abcd", 3, "q")
    assert too_long.status == 400
  end

  test "date, UUID, cursor, and request-body helpers preserve contract error precedence", %{
    conn: conn
  } do
    assert {:ok, nil} = Support.datetime_param(conn, "from")

    assert {:ok, %DateTime{}} =
             Support.datetime_param(
               %{conn | query_string: "from=2026-07-11T12%3A30%3A00Z"},
               "from"
             )

    assert {:error, bad_date} =
             Support.datetime_param(%{conn | query_string: "from=tomorrow"}, "from")

    assert bad_date.status == 400

    uppercase = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"
    assert {:ok, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"} = Support.path_uuid(conn, uppercase)
    assert {:error, missing} = Support.path_uuid(conn, "not-a-uuid")
    assert missing.status == 404

    created_at = DateTime.from_naive!(~N[2026-07-11 12:30:00], "Etc/UTC")
    cursor = Cursor.encode(%{created_at: created_at, id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"})

    assert {:ok, %{created_at: ^created_at}} =
             Support.cursor_param(%{conn | query_string: "cursor=#{URI.encode_www_form(cursor)}"})

    assert {:error, bad_cursor} = Support.cursor_param(%{conn | query_string: "cursor=bad"})
    assert bad_cursor.status == 400

    assert Support.optional_body_string(%{"note" => "safe"}, "note") == "safe"
    assert is_nil(Support.optional_body_string(%{"note" => 1}, "note"))
    assert is_nil(Support.optional_body_string(:not_a_map, "note"))
    assert {:ok, "blocked"} = Support.required_status(conn, %{"status" => "blocked"})
    assert {:error, invalid_status} = Support.required_status(conn, %{"status" => "other"})
    assert invalid_status.status == 400
  end

  test "ETag responses revalidate exactly and query decoding tolerates client-controlled input",
       %{
         conn: conn
       } do
    response = Support.etag_json(conn, 200, %{items: [1, 2]})
    assert %{"items" => [1, 2]} = json_response(response, 200)
    [etag] = get_resp_header(response, "etag")
    assert get_resp_header(response, "cache-control") == ["no-cache"]

    not_modified =
      conn
      |> put_req_header("if-none-match", "\"stale\", #{etag}")
      |> Support.etag_json(200, %{items: [1, 2]})

    assert response(not_modified, 304) == ""

    assert Problem.query_values("tag=elixir&tag=phoenix&q=hello+world", "tag") ==
             ["elixir", "phoenix"]

    assert Problem.query_values("q=hello+world", "q") == ["hello world"]
    assert Problem.query_values("q=%ZZ", "q") == ["%ZZ"]
    assert Problem.omit_nil(%{present: 1, absent: nil}) == %{present: 1}
  end

  test "v1 compatibility headers are stable", %{conn: conn} do
    response = Support.v1_headers(conn)
    assert get_resp_header(response, "deprecation") == ["@1782864000"]
    assert get_resp_header(response, "sunset") == ["Thu, 01 Jul 2027 00:00:00 GMT"]
    assert get_resp_header(response, "link") == ["</api/v2/bookmarks>; rel=\"successor-version\""]
  end
end
