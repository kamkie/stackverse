defmodule StackverseBackend.Accounts do
  @moduledoc "The account context owns administrative user-account behavior."

  import Ecto.Query

  alias StackverseBackend.{Audit, Log, Query, Repo}
  alias StackverseBackend.Schemas.{Bookmark, UserAccount}

  def list(q, status, page, size) do
    query = UserAccount |> by_username(q) |> by_status(status)
    total = Repo.aggregate(query, :count, :username)

    items =
      query
      |> join(:left, [account], bookmark in Bookmark, on: bookmark.owner == account.username)
      |> group_by([account], account.username)
      |> order_by([account], desc: account.last_seen, asc: account.username)
      |> limit(^size)
      |> offset(^(page * size))
      |> select([account, bookmark], {account, count(bookmark.id)})
      |> Repo.all()
      |> Enum.map(fn {account, count} -> UserAccount.to_row(account, count) end)

    %{items: items, total: total}
  end

  def get(username) do
    case Repo.get(UserAccount, username) do
      nil -> nil
      account -> UserAccount.to_row(account, bookmark_count(username))
    end
  end

  def set_status(actor, username, status, reason) do
    Repo.transaction(fn ->
      case Repo.one(
             from account in UserAccount,
               where: account.username == ^username,
               lock: "FOR UPDATE"
           ) do
        nil ->
          Repo.rollback(:not_found)

        account ->
          updated = account |> UserAccount.status_changeset(status, reason) |> Repo.update!()

          if status == "blocked" do
            Audit.record!(actor, "user.blocked", "user", username, %{reason: reason})
          else
            Audit.record!(actor, "user.unblocked", "user", username, nil)
          end

          updated
      end
    end)
    |> case do
      {:ok, account} ->
        Log.event(
          :info,
          if(status == "blocked", do: "user_blocked", else: "user_unblocked"),
          "success",
          "User account status changed",
          actor: actor,
          resource_type: "user",
          resource_id: username
        )

        {:ok, UserAccount.to_row(account, bookmark_count(username))}

      result ->
        result
    end
  end

  defp by_username(query, q) do
    if q && String.trim(q) != "" do
      pattern = "%#{Query.escape_like(q)}%"
      where(query, [account], ilike(account.username, ^pattern))
    else
      query
    end
  end

  defp by_status(query, nil), do: query
  defp by_status(query, status), do: where(query, [account], account.status == ^status)

  defp bookmark_count(username) do
    Repo.aggregate(from(bookmark in Bookmark, where: bookmark.owner == ^username), :count, :id)
  end
end
