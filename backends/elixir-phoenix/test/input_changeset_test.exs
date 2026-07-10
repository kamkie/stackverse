defmodule StackverseBackend.InputChangesetTest do
  use StackverseBackend.DataCase, async: true

  alias StackverseBackend.Inputs.{BookmarkInput, MessageInput}

  test "bookmark input is an embedded schema changeset with contract errors" do
    changeset =
      BookmarkInput.changeset(%{
        "url" => "ftp://example.com",
        "title" => "",
        "tags" => ["Bad Tag"]
      })

    refute changeset.valid?
    assert changeset.data.__struct__ == BookmarkInput
    assert {"validation.url.invalid", _metadata} = changeset.errors[:url]
    assert {"validation.title.required", _metadata} = changeset.errors[:title]
    assert {"validation.tag.invalid", _metadata} = changeset.errors[:tags]
  end

  test "message input changeset preserves normalized values" do
    changeset =
      MessageInput.changeset(%{
        "key" => " example.title ",
        "language" => " en ",
        "text" => "Example"
      })

    assert changeset.valid?
    assert Changeset.get_field(changeset, :key) == "example.title"
    assert Changeset.get_field(changeset, :language) == "en"
  end

  test "query-tag validation also runs through changeset metadata" do
    changeset = BookmarkInput.query_tags_changeset(["Bad Tag"])

    refute changeset.valid?
    assert {"validation.tag.invalid", _metadata} = changeset.errors[:tag]
  end
end
