defmodule StackverseBackend.Validation do
  @moduledoc false

  alias Ecto.Changeset

  alias StackverseBackend.Inputs.{
    BookmarkInput,
    MessageInput,
    ModerationInput,
    ReportInput,
    UserStatusInput
  }

  def validate_bookmark(body), do: body |> BookmarkInput.changeset() |> result()

  def validate_query_tags(tags) do
    tags
    |> BookmarkInput.query_tags_changeset()
    |> result(:tags)
  end

  def validate_message(body), do: body |> MessageInput.changeset() |> result()
  def validate_report(body), do: body |> ReportInput.changeset() |> result()

  def validate_report_resolution(body) do
    body |> ModerationInput.resolution_changeset() |> result()
  end

  def validate_bookmark_status(body) do
    body |> ModerationInput.bookmark_status_changeset() |> result()
  end

  def validate_block_reason(status, reason) do
    status
    |> UserStatusInput.changeset(reason)
    |> case do
      %{valid?: true} -> {:ok, :ok}
      changeset -> {:error, violations(changeset)}
    end
  end

  defp result(changeset, field \\ nil)

  defp result(%{valid?: true} = changeset, nil) do
    value = changeset |> Changeset.apply_changes() |> Map.from_struct()
    {:ok, value}
  end

  defp result(%{valid?: true} = changeset, field) do
    {:ok, Changeset.get_field(changeset, field)}
  end

  defp result(changeset, _field), do: {:error, violations(changeset)}

  defp violations(changeset) do
    changeset.errors
    |> Enum.reverse()
    |> Enum.map(fn {field, {message_key, _metadata}} ->
      %{field: Atom.to_string(field), message_key: message_key}
    end)
  end
end
