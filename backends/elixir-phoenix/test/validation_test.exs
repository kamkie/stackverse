defmodule StackverseBackend.ValidationTest do
  use ExUnit.Case, async: true

  alias StackverseBackend.Validation

  test "bookmark validation normalizes tags and defaults visibility" do
    assert {:ok, bookmark} =
             Validation.validate_bookmark(%{
               "url" => "https://example.com",
               "title" => " Example ",
               "tags" => [" Elixir ", "elixir", "phoenix"]
             })

    assert bookmark.title == "Example"
    assert bookmark.tags == ["elixir", "phoenix"]
    assert bookmark.visibility == "private"
  end

  test "bookmark validation collects field errors" do
    assert {:error, errors} =
             Validation.validate_bookmark(%{
               "url" => "ftp://example.com",
               "title" => "",
               "notes" => String.duplicate("x", 4001),
               "tags" => ["Bad Tag"]
             })

    keys = Enum.map(errors, & &1.message_key)
    assert "validation.url.invalid" in keys
    assert "validation.title.required" in keys
    assert "validation.notes.too-long" in keys
    assert "validation.tag.invalid" in keys
  end

  test "message validation enforces contract keys" do
    assert {:error, errors} =
             Validation.validate_message(%{
               "key" => "Bad Key",
               "language" => "english",
               "text" => "",
               "description" => String.duplicate("x", 1001)
             })

    keys = Enum.map(errors, & &1.message_key)
    assert "validation.message.key.invalid" in keys
    assert "validation.message.language.invalid" in keys
    assert "validation.message.text.required" in keys
    assert "validation.message.description.too-long" in keys
  end

  test "report validation accepts contract reasons only" do
    assert {:ok, %{reason: "spam"}} = Validation.validate_report(%{"reason" => "spam"})
    assert {:error, errors} = Validation.validate_report(%{"reason" => "unknown"})
    assert [%{message_key: "validation.report.reason.invalid"}] = errors
  end
end
