defmodule StackverseBackendWeb.ControllerHTTPTest do
  use StackverseBackendWeb.ConnCase, async: true

  test "health is served through the Phoenix endpoint", %{conn: conn} do
    conn = get(conn, "/healthz")

    assert response(conn, 200) == ""
  end

  test "focused bookmark controller rejects anonymous writes", %{conn: conn} do
    conn =
      conn
      |> put_req_header("content-type", "application/json")
      |> post("/api/v1/bookmarks", Jason.encode!(%{url: "https://example.com", title: "Example"}))

    assert %{"status" => 401, "title" => "Unauthorized"} = json_response(conn, 401)
  end

  test "focused message controller rejects anonymous admin writes", %{conn: conn} do
    conn =
      conn
      |> put_req_header("content-type", "application/json")
      |> post(
        "/api/v1/messages",
        Jason.encode!(%{key: "example.title", language: "en", text: "Example"})
      )

    assert %{"status" => 401, "title" => "Unauthorized"} = json_response(conn, 401)
  end

  test "router fallback uses the problem contract", %{conn: conn} do
    conn = get(conn, "/not-a-stackverse-route")

    assert %{"status" => 404, "title" => "Not Found"} = json_response(conn, 404)
  end

  test "Phoenix JSON parsing rejects malformed request documents before controller dispatch", %{
    conn: conn
  } do
    assert_raise Plug.Parsers.ParseError, fn ->
      conn
      |> put_req_header("content-type", "application/json")
      |> post("/api/v1/bookmarks", "{not-json")
    end
  end
end
