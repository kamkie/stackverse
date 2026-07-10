defmodule StackverseBackend.Inputs.UserStatusInput do
  @moduledoc false

  use Ecto.Schema

  import Ecto.Changeset

  alias StackverseBackend.Inputs.Support

  @primary_key false
  embedded_schema do
    field :status, :string
    field :reason, :string
  end

  @cast_messages %{
    status: "validation.user-status.invalid",
    reason: "validation.block.reason.too-long"
  }

  def changeset(body) do
    changeset =
      %__MODULE__{}
      |> Support.contract_cast(body, [:status, :reason], @cast_messages)
      |> trim_reason()

    status = get_field(changeset, :status)
    reason = get_field(changeset, :reason)

    changeset
    |> Support.require(:status, status in ~w[active blocked], "validation.user-status.invalid")
    |> Support.require(
      :reason,
      status != "blocked" or (is_binary(reason) and reason != ""),
      "validation.block.reason.required"
    )
    |> Support.require(:reason, length_of(reason) <= 1000, "validation.block.reason.too-long")
  end

  defp trim_reason(changeset) do
    if Support.cast_valid?(changeset, :reason),
      do: update_change(changeset, :reason, &String.trim/1),
      else: changeset
  end

  defp length_of(nil), do: 0
  defp length_of(value), do: String.length(value)
end
