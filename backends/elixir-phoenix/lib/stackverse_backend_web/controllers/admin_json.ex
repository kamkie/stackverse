defmodule StackverseBackendWeb.AdminJSON do
  @moduledoc false

  alias StackverseBackend.Problem
  alias StackverseBackendWeb.ViewSupport

  def user(row) do
    %{
      username: row["username"],
      firstSeen: ViewSupport.iso(row["first_seen"]),
      lastSeen: ViewSupport.iso(row["last_seen"]),
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
      createdAt: ViewSupport.iso(row["created_at"])
    }
    |> Problem.omit_nil()
  end
end
