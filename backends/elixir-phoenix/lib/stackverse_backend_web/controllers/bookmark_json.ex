defmodule StackverseBackendWeb.BookmarkJSON do
  @moduledoc false

  alias StackverseBackend.Problem
  alias StackverseBackendWeb.ViewSupport

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
      createdAt: ViewSupport.iso(row["created_at"]),
      updatedAt: ViewSupport.iso(row["updated_at"])
    }
    |> Problem.omit_nil()
  end
end
