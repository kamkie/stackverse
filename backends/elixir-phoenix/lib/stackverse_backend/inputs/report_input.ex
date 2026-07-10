defmodule StackverseBackend.Inputs.ReportInput do
  @moduledoc false

  use Ecto.Schema

  import Ecto.Changeset

  alias StackverseBackend.Inputs.Support

  @primary_key false
  embedded_schema do
    field :reason, :string
    field :comment, :string
  end

  @reasons ~w[spam offensive broken-link other]
  @cast_messages %{
    reason: "validation.report.reason.invalid",
    comment: "validation.report.comment.too-long"
  }

  def changeset(body) do
    changeset =
      Support.contract_cast(%__MODULE__{}, body, [:reason, :comment], @cast_messages)

    reason = get_field(changeset, :reason)
    comment = get_field(changeset, :comment)

    changeset
    |> Support.require(
      :reason,
      is_binary(reason) and reason in @reasons,
      "validation.report.reason.invalid"
    )
    |> Support.require(
      :comment,
      Support.length_of(comment) <= 1000,
      "validation.report.comment.too-long"
    )
  end
end
