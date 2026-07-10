defmodule StackverseBackend.Inputs.Support do
  @moduledoc false

  import Ecto.Changeset

  def contract_cast(schema, body, fields, cast_messages) when is_map(body) do
    schema
    |> cast(body, fields)
    |> translate_cast_errors(cast_messages)
  end

  def contract_cast(schema, _body, fields, cast_messages) do
    contract_cast(schema, %{}, fields, cast_messages)
  end

  def cast_valid?(changeset, field) do
    not Enum.any?(changeset.errors, fn
      {^field, {_message, metadata}} -> metadata[:validation] == :cast
      _error -> false
    end)
  end

  def require(changeset, field, condition, message_key) do
    if cast_valid?(changeset, field) and not condition do
      add_error(changeset, field, message_key)
    else
      changeset
    end
  end

  defp translate_cast_errors(changeset, cast_messages) do
    errors =
      Enum.map(changeset.errors, fn
        {field, {_message, metadata}} = error ->
          if metadata[:validation] == :cast do
            {field, {Map.fetch!(cast_messages, field), metadata}}
          else
            error
          end
      end)

    %{changeset | errors: errors}
  end
end
