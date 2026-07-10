defmodule StackverseBackend.Query do
  @moduledoc false

  alias StackverseBackend.Repo

  def all!(sql, params), do: sql |> Repo.query!(params) |> rows()

  def rows(%Postgrex.Result{columns: columns, rows: rows}) do
    Enum.map(rows, fn row ->
      columns
      |> Enum.zip(row)
      |> Map.new()
    end)
  end

  def now, do: DateTime.utc_now() |> DateTime.truncate(:microsecond)

  def escape_like(value) do
    value
    |> String.replace("\\", "\\\\")
    |> String.replace("%", "\\%")
    |> String.replace("_", "\\_")
  end
end
