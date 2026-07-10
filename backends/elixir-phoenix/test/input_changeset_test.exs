defmodule StackverseBackend.InputChangesetTest do
  use StackverseBackend.DataCase, async: true

  alias StackverseBackend.Inputs.{
    BookmarkInput,
    MessageInput,
    ModerationInput,
    ReportInput,
    UserStatusInput
  }

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

  test "bookmark casting rejects malformed arrays and optional scalar types without coercion" do
    for tags <- [42, [42], [%{}]] do
      changeset =
        BookmarkInput.changeset(%{
          "url" => "https://example.com",
          "title" => "Example",
          "tags" => tags
        })

      refute changeset.valid?
      assert {"validation.tag.invalid", metadata} = changeset.errors[:tags]
      assert metadata[:validation] == :cast
    end

    changeset =
      BookmarkInput.changeset(%{
        "url" => "https://example.com",
        "title" => "Example",
        "notes" => 42
      })

    refute changeset.valid?
    assert {"validation.notes.too-long", metadata} = changeset.errors[:notes]
    assert metadata[:validation] == :cast
  end

  test "every optional input string is type checked by Ecto cast" do
    cases = [
      {MessageInput.changeset(%{
         "key" => "example",
         "language" => "en",
         "text" => "x",
         "description" => 42
       }), :description, "validation.message.description.too-long"},
      {ReportInput.changeset(%{"reason" => "spam", "comment" => 42}), :comment,
       "validation.report.comment.too-long"},
      {ModerationInput.resolution_changeset(%{"resolution" => "dismissed", "note" => 42}), :note,
       "validation.resolution.note.too-long"},
      {ModerationInput.bookmark_status_changeset(%{"status" => "active", "note" => 42}), :note,
       "validation.bookmark-status.note.too-long"},
      {UserStatusInput.changeset(%{"status" => "active", "reason" => 42}), :reason,
       "validation.block.reason.too-long"}
    ]

    for {changeset, field, message_key} <- cases do
      refute changeset.valid?
      assert {^message_key, metadata} = changeset.errors[field]
      assert metadata[:validation] == :cast
    end
  end
end
