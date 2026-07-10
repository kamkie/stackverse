defmodule StackverseBackend.Accounts do
  @moduledoc "The account context owns administrative user-account behavior."

  alias StackverseBackend.{Audit, Log, Query, Repo}

  def list(q, status, page, size) do
    {where, binds} = where(q, status)
    total = Query.scalar!("select count(*)::int from user_accounts u where #{where}", binds)

    items =
      Query.all!(
        """
        select u.username, u.first_seen, u.last_seen, u.status, u.blocked_reason,
               (select count(*)::int from bookmarks b where b.owner = u.username) as bookmark_count
        from user_accounts u
        where #{where}
        order by u.last_seen desc, u.username asc
        limit $#{length(binds) + 1} offset $#{length(binds) + 2}
        """,
        binds ++ [size, page * size]
      )

    %{items: items, total: total}
  end

  def get(username) do
    Query.one(
      """
      select u.username, u.first_seen, u.last_seen, u.status, u.blocked_reason,
             (select count(*)::int from bookmarks b where b.owner = u.username) as bookmark_count
      from user_accounts u
      where u.username = $1
      """,
      [username]
    )
  end

  def set_status(actor, username, status, reason) do
    Repo.transaction(fn ->
      if is_nil(
           Query.one!("select username from user_accounts where username = $1 for update", [
             username
           ])
         ) do
        Repo.rollback(:not_found)
      end

      if status == "blocked" do
        Repo.query!(
          "update user_accounts set status = 'blocked', blocked_reason = $2 where username = $1",
          [username, reason]
        )

        Audit.record!(actor, "user.blocked", "user", username, %{reason: reason})
      else
        Repo.query!(
          "update user_accounts set status = 'active', blocked_reason = null where username = $1",
          [username]
        )

        Audit.record!(actor, "user.unblocked", "user", username, nil)
      end
    end)
    |> case do
      {:ok, _result} ->
        Log.event(
          :info,
          if(status == "blocked", do: "user_blocked", else: "user_unblocked"),
          "success",
          "User account status changed",
          actor: actor,
          resource_type: "user",
          resource_id: username
        )

        {:ok, get(username)}

      result ->
        result
    end
  end

  defp where(q, status) do
    conditions = ["true"]
    binds = []

    {conditions, binds} =
      if q && String.trim(q) != "" do
        {conditions ++ ["u.username ilike $1 escape '\\'"], ["%#{Query.escape_like(q)}%"]}
      else
        {conditions, binds}
      end

    {conditions, binds} =
      if status do
        index = length(binds) + 1
        {conditions ++ ["u.status = $#{index}"], binds ++ [status]}
      else
        {conditions, binds}
      end

    {Enum.join(conditions, " and "), binds}
  end
end
