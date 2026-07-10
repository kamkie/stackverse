defmodule StackverseBackend.Inputs.ModerationInput do
  @moduledoc false

  use Ecto.Schema

  import Ecto.Changeset

  @primary_key false
  embedded_schema do
    field :resolution, :string
    field :status, :string
    field :note, :string
  end

  def resolution_changeset(body) when is_map(body) do
    resolution = string_value(body, "resolution")
    note = optional_string(body, "note")

    change(%__MODULE__{}, %{resolution: resolution, note: note})
    |> require(
      resolution in ~w[open dismissed actioned],
      :resolution,
      "validation.resolution.invalid"
    )
    |> require(length_of(note) <= 1000, :note, "validation.resolution.note.too-long")
  end

  def resolution_changeset(_body), do: resolution_changeset(%{})

  def bookmark_status_changeset(body) when is_map(body) do
    status = string_value(body, "status")
    note = optional_string(body, "note")

    change(%__MODULE__{}, %{status: status, note: note})
    |> require(
      status in ~w[active hidden],
      :status,
      "validation.bookmark-status.invalid"
    )
    |> require(length_of(note) <= 1000, :note, "validation.bookmark-status.note.too-long")
  end

  def bookmark_status_changeset(_body), do: bookmark_status_changeset(%{})

  defp string_value(body, key) do
    case Map.get(body, key) do
      value when is_binary(value) -> value
      _value -> nil
    end
  end

  defp optional_string(body, key) do
    case Map.get(body, key) do
      value when is_binary(value) -> value
      _ -> nil
    end
  end

  defp length_of(nil), do: 0
  defp length_of(value), do: String.length(value)

  defp require(changeset, true, _field, _message_key), do: changeset
  defp require(changeset, false, field, message_key), do: add_error(changeset, field, message_key)
end
