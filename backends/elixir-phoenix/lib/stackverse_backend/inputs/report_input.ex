defmodule StackverseBackend.Inputs.ReportInput do
  @moduledoc false

  use Ecto.Schema

  import Ecto.Changeset

  @primary_key false
  embedded_schema do
    field :reason, :string
    field :comment, :string
  end

  @reasons ~w[spam offensive broken-link other]

  def changeset(body) when is_map(body) do
    reason = string_value(body, "reason")
    comment = optional_string(body, "comment")

    change(%__MODULE__{}, %{reason: reason, comment: comment})
    |> require(
      is_binary(reason) and reason in @reasons,
      :reason,
      "validation.report.reason.invalid"
    )
    |> require(length_of(comment) <= 1000, :comment, "validation.report.comment.too-long")
  end

  def changeset(_body), do: changeset(%{})

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
