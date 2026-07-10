defmodule StackverseBackendWeb.ReportJSON do
  @moduledoc false

  alias StackverseBackend.Problem

  def response(row) do
    %{
      id: row["id"],
      bookmarkId: row["bookmark_id"],
      reporter: row["reporter"],
      reason: row["reason"],
      comment: row["comment"],
      status: row["status"],
      createdAt: iso(row["created_at"]),
      resolvedBy: row["resolved_by"],
      resolvedAt: iso(row["resolved_at"]),
      resolutionNote: row["resolution_note"]
    }
    |> Problem.omit_nil()
  end

  defp iso(nil), do: nil
  defp iso(%DateTime{} = value), do: DateTime.to_iso8601(value)

  defp iso(%NaiveDateTime{} = value),
    do: value |> DateTime.from_naive!("Etc/UTC") |> DateTime.to_iso8601()
end
