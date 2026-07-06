defmodule StackverseBackend.CursorTest do
  use ExUnit.Case, async: true

  alias StackverseBackend.Cursor

  test "cursor round-trips created_at and id" do
    created_at = DateTime.from_naive!(~N[2026-07-05 12:30:00], "Etc/UTC")
    id = "00000000-0000-4000-8000-000000000001"

    raw = Cursor.encode(%{created_at: created_at, id: id})

    assert {:ok, %{created_at: ^created_at, id: ^id}} = Cursor.decode(raw)
    assert :error = Cursor.decode("definitely-not-a-cursor")
  end
end
