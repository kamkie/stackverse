defmodule StackverseBackend.PersistenceSchemaTest do
  use StackverseBackend.DataCase, async: true

  alias StackverseBackend.Schemas.{AuditEntry, Bookmark, Message, Report, UserAccount}

  test "ordinary persistence resources are first-class Ecto schemas" do
    assert Bookmark.__schema__(:source) == "bookmarks"
    assert Bookmark.__schema__(:type, :tags) == {:array, :string}
    assert Message.__schema__(:source) == "messages"
    assert Report.__schema__(:type, :bookmark_id) == Ecto.UUID
    assert UserAccount.__schema__(:primary_key) == [:username]
    assert AuditEntry.__schema__(:type, :detail) == :map
  end

  test "persistence changesets own normal create and unique-constraint metadata" do
    bookmark =
      Bookmark.create_changeset("demo", %{
        url: "https://example.com",
        title: "Example",
        notes: nil,
        tags: ["ecto"],
        visibility: "private"
      })

    assert bookmark.valid?
    assert Changeset.get_field(bookmark, :owner) == "demo"
    assert Changeset.get_field(bookmark, :status) == "active"

    message =
      Message.changeset(%Message{}, %{
        key: "example.title",
        language: "en",
        text: "Example"
      })

    assert message.valid?
    assert Enum.any?(message.constraints, &(&1.constraint == "uq_messages_key_language"))

    report =
      Report.create_changeset("mentor", Ecto.UUID.generate(), %{
        reason: "spam",
        comment: nil
      })

    assert report.valid?
    assert Enum.any?(report.constraints, &(&1.constraint == "uq_reports_one_open_per_reporter"))
  end
end
