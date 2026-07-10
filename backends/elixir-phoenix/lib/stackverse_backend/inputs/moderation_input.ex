defmodule StackverseBackend.Inputs.ModerationInput do
  @moduledoc false

  use Ecto.Schema

  import Ecto.Changeset

  alias StackverseBackend.Inputs.Support

  @primary_key false
  embedded_schema do
    field :resolution, :string
    field :status, :string
    field :note, :string
  end

  def resolution_changeset(body) do
    messages = %{
      resolution: "validation.resolution.invalid",
      note: "validation.resolution.note.too-long"
    }

    changeset = Support.contract_cast(%__MODULE__{}, body, [:resolution, :note], messages)
    resolution = get_field(changeset, :resolution)
    note = get_field(changeset, :note)

    changeset
    |> Support.require(
      :resolution,
      resolution in ~w[open dismissed actioned],
      "validation.resolution.invalid"
    )
    |> Support.require(:note, length_of(note) <= 1000, "validation.resolution.note.too-long")
  end

  def bookmark_status_changeset(body) do
    messages = %{
      status: "validation.bookmark-status.invalid",
      note: "validation.bookmark-status.note.too-long"
    }

    changeset = Support.contract_cast(%__MODULE__{}, body, [:status, :note], messages)
    status = get_field(changeset, :status)
    note = get_field(changeset, :note)

    changeset
    |> Support.require(:status, status in ~w[active hidden], "validation.bookmark-status.invalid")
    |> Support.require(:note, length_of(note) <= 1000, "validation.bookmark-status.note.too-long")
  end

  defp length_of(nil), do: 0
  defp length_of(value), do: String.length(value)
end
