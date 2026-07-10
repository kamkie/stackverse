defmodule StackverseBackend.Inputs.UserStatusInput do
  @moduledoc false

  use Ecto.Schema

  import Ecto.Changeset

  @primary_key false
  embedded_schema do
    field :status, :string
    field :reason, :string
  end

  def changeset(status, reason) do
    change(%__MODULE__{}, %{status: status, reason: reason})
    |> require(
      status != "blocked" or (is_binary(reason) and String.trim(reason) != ""),
      :reason,
      "validation.block.reason.required"
    )
    |> require(length_of(reason) <= 1000, :reason, "validation.block.reason.too-long")
  end

  defp length_of(nil), do: 0
  defp length_of(value), do: String.length(value)

  defp require(changeset, true, _field, _message_key), do: changeset
  defp require(changeset, false, field, message_key), do: add_error(changeset, field, message_key)
end
