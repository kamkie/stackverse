defmodule StackverseBackendWeb.BookmarkJSON do
  @moduledoc false

  alias StackverseBackend.Problem

  def response(row) do
    %{
      id: row["id"],
      url: row["url"],
      title: row["title"],
      notes: row["notes"],
      tags: Enum.sort(row["tags"] || []),
      visibility: row["visibility"],
      status: row["status"],
      owner: row["owner"],
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
