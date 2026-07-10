defmodule StackverseBackend.PersistenceQueryTest do
  use StackverseBackend.DataCase, async: false

  @moduletag :database
  @moduletag skip: System.get_env("STACKVERSE_DB_TESTS") != "true"

  alias StackverseBackend.{Bookmarks, Messages, Repo}
  alias StackverseBackend.Schemas.{Bookmark, Message}

  test "single-resource contexts call Repo.get with schema-first arguments" do
    bookmark = insert_bookmark(["ecto"])

    message =
      %Message{}
      |> Message.changeset(%{key: "test.repo-get", language: "en", text: "Repo get"})
      |> Repo.insert!()

    bookmark_id = bookmark.id
    message_id = message.id

    assert %{"id" => ^bookmark_id, "title" => "Ecto persistence"} = Bookmarks.get(bookmark_id)
    assert %{"id" => ^message_id, "key" => "test.repo-get"} = Messages.get(message_id)
  end

  test "text-array filters work for count, offset, and cursor queries" do
    first = insert_bookmark(["ecto", "phoenix"])
    _other = insert_bookmark(["other"])

    filters = %{caller: nil, visibility: "public", tags: ["ecto"], q: ""}

    assert %{total: 1, items: [%{"id" => id}]} = Bookmarks.list(filters, 0, 20)
    assert id == first.id

    assert [%{"id" => ^id}] = Bookmarks.list_cursor(filters, nil, 20)

    cursor = %{created_at: first.created_at, id: first.id}
    assert [] = Bookmarks.list_cursor(filters, cursor, 20)
  end

  defp insert_bookmark(tags) do
    "demo"
    |> Bookmark.create_changeset(%{
      url: "https://example.com/#{Ecto.UUID.generate()}",
      title: "Ecto persistence",
      notes: nil,
      tags: tags,
      visibility: "public"
    })
    |> Repo.insert!()
  end
end
