defmodule StackverseBackendWeb.MessageJSON do
  @moduledoc false

  alias StackverseBackend.Problem

  def response(row) do
    %{
      id: row["id"],
      key: row["key"],
      language: row["language"],
      text: row["text"],
      description: row["description"],
      createdAt: iso(row["created_at"]),
      updatedAt: iso(row["updated_at"])
    }
    |> Problem.omit_nil()
  end

  defp iso(nil), do: nil
  defp iso(%DateTime{} = value), do: DateTime.to_iso8601(value)

  defp iso(%NaiveDateTime{} = value),
    do: value |> DateTime.from_naive!("Etc/UTC") |> DateTime.to_iso8601()
end
