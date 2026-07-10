defmodule StackverseBackendWeb.AdminJSON do
  @moduledoc false

  alias StackverseBackend.Problem

  def user(row) do
    %{
      username: row["username"],
      firstSeen: iso(row["first_seen"]),
      lastSeen: iso(row["last_seen"]),
      status: row["status"],
      blockedReason: row["blocked_reason"],
      bookmarkCount: row["bookmark_count"]
    }
    |> Problem.omit_nil()
  end

  def audit(row) do
    %{
      id: row["id"],
      actor: row["actor"],
      action: row["action"],
      targetType: row["target_type"],
      targetId: row["target_id"],
      detail: row["detail"],
      createdAt: iso(row["created_at"])
    }
    |> Problem.omit_nil()
  end

  defp iso(nil), do: nil
  defp iso(%DateTime{} = value), do: DateTime.to_iso8601(value)

  defp iso(%NaiveDateTime{} = value),
    do: value |> DateTime.from_naive!("Etc/UTC") |> DateTime.to_iso8601()
end
