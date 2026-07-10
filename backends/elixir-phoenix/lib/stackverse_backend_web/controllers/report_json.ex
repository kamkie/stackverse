defmodule StackverseBackendWeb.ReportJSON do
  @moduledoc false

  alias StackverseBackend.Problem
  alias StackverseBackendWeb.ViewSupport

  def response(row) do
    %{
      id: row["id"],
      bookmarkId: row["bookmark_id"],
      reporter: row["reporter"],
      reason: row["reason"],
      comment: row["comment"],
      status: row["status"],
      createdAt: ViewSupport.iso(row["created_at"]),
      resolvedBy: row["resolved_by"],
      resolvedAt: ViewSupport.iso(row["resolved_at"]),
      resolutionNote: row["resolution_note"]
    }
    |> Problem.omit_nil()
  end
end
