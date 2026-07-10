defmodule StackverseBackendWeb.MessageJSON do
  @moduledoc false

  alias StackverseBackend.Problem
  alias StackverseBackendWeb.ViewSupport

  def response(row) do
    %{
      id: row["id"],
      key: row["key"],
      language: row["language"],
      text: row["text"],
      description: row["description"],
      createdAt: ViewSupport.iso(row["created_at"]),
      updatedAt: ViewSupport.iso(row["updated_at"])
    }
    |> Problem.omit_nil()
  end
end
