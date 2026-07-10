defmodule StackverseBackend.Schemas.Bookmark do
  @moduledoc false

  use Ecto.Schema

  import Ecto.Changeset

  @primary_key {:id, Ecto.UUID, autogenerate: true}
  schema "bookmarks" do
    field :owner, :string
    field :url, :string
    field :title, :string
    field :notes, :string
    field :tags, {:array, :string}, default: []
    field :visibility, :string
    field :status, :string

    timestamps(inserted_at: :created_at, updated_at: :updated_at, type: :utc_datetime_usec)
  end

  def create_changeset(owner, input) do
    %__MODULE__{}
    |> cast(Map.merge(input, %{owner: owner, status: "active"}), [
      :owner,
      :url,
      :title,
      :notes,
      :tags,
      :visibility,
      :status
    ])
    |> validate_required([:owner, :url, :title, :visibility, :status])
  end

  def update_changeset(bookmark, input) do
    cast(bookmark, input, [:url, :title, :notes, :tags, :visibility])
  end

  def status_changeset(bookmark, status), do: change(bookmark, status: status)

  def to_row(nil), do: nil

  def to_row(bookmark) do
    %{
      "id" => bookmark.id,
      "owner" => bookmark.owner,
      "url" => bookmark.url,
      "title" => bookmark.title,
      "notes" => bookmark.notes,
      "tags" => bookmark.tags,
      "visibility" => bookmark.visibility,
      "status" => bookmark.status,
      "created_at" => bookmark.created_at,
      "updated_at" => bookmark.updated_at
    }
  end
end
