defmodule StackverseBackend.Schemas.UserAccount do
  @moduledoc false

  use Ecto.Schema

  import Ecto.Changeset

  @primary_key {:username, :string, autogenerate: false}
  schema "user_accounts" do
    field :first_seen, :utc_datetime_usec
    field :last_seen, :utc_datetime_usec
    field :status, :string
    field :blocked_reason, :string
  end

  def new(username, now) do
    %__MODULE__{username: username, first_seen: now, last_seen: now, status: "active"}
  end

  def seen_changeset(account, now), do: change(account, last_seen: now)

  def status_changeset(account, "blocked", reason) do
    change(account, status: "blocked", blocked_reason: reason)
  end

  def status_changeset(account, "active", _reason) do
    change(account, status: "active", blocked_reason: nil)
  end

  def to_row(nil, _bookmark_count), do: nil

  def to_row(account, bookmark_count) do
    %{
      "username" => account.username,
      "first_seen" => account.first_seen,
      "last_seen" => account.last_seen,
      "status" => account.status,
      "blocked_reason" => account.blocked_reason,
      "bookmark_count" => bookmark_count
    }
  end
end
