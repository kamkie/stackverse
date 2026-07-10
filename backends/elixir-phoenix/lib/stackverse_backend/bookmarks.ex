defmodule StackverseBackend.Bookmarks do
  @moduledoc "The bookmark context owns bookmark persistence and visibility rules."

  import Ecto.Query

  alias StackverseBackend.{Query, Repo}
  alias StackverseBackend.Schemas.Bookmark

  def list(filters, page, size) do
    query = filtered_query(filters)
    total = Repo.aggregate(query, :count, :id)

    items =
      query
      |> order_by([bookmark], desc: bookmark.created_at, desc: bookmark.id)
      |> limit(^size)
      |> offset(^(page * size))
      |> Repo.all()
      |> Enum.map(&Bookmark.to_row/1)

    %{items: items, total: total}
  end

  def list_cursor(filters, cursor, size) do
    filters
    |> filtered_query()
    |> after_cursor(cursor)
    |> order_by([bookmark], desc: bookmark.created_at, desc: bookmark.id)
    |> limit(^(size + 1))
    |> Repo.all()
    |> Enum.map(&Bookmark.to_row/1)
  end

  def create(owner, input) do
    bookmark = owner |> Bookmark.create_changeset(input) |> Repo.insert!()
    {bookmark.id, Bookmark.to_row(bookmark)}
  end

  def get(id), do: id |> Repo.get(Bookmark) |> Bookmark.to_row()

  def update(owner, id, input) do
    Repo.transaction(fn ->
      bookmark =
        Repo.one(from bookmark in Bookmark, where: bookmark.id == ^id, lock: "FOR UPDATE")

      cond do
        is_nil(bookmark) or bookmark.owner != owner ->
          Repo.rollback(:not_found)

        bookmark.status == "hidden" and input.visibility == "public" ->
          Repo.rollback(
            {:conflict, "This bookmark was hidden by moderation and cannot be made public."}
          )

        true ->
          bookmark
          |> Bookmark.update_changeset(input)
          |> Repo.update!()
          |> Bookmark.to_row()
      end
    end)
  end

  def delete(owner, id) do
    case Repo.delete_all(
           from bookmark in Bookmark, where: bookmark.id == ^id and bookmark.owner == ^owner
         ) do
      {0, _rows} -> :not_found
      {_count, _rows} -> :ok
    end
  end

  # PostgreSQL's array expansion is intentionally visible at this aggregate boundary.
  def list_tags(owner) do
    Query.all!(
      """
      select tag, count(*)::int as count
      from bookmarks, unnest(tags) as tag
      where owner = $1
      group by tag
      order by count desc, tag asc
      """,
      [owner]
    )
  end

  def visible_to?(bookmark, caller) do
    bookmark["owner"] == caller or
      (bookmark["visibility"] == "public" and bookmark["status"] == "active")
  end

  defp filtered_query(filters) do
    Bookmark
    |> by_visibility(filters)
    |> by_tags(filters.tags)
    |> by_text(filters.q)
  end

  defp by_visibility(query, %{visibility: "public"}) do
    where(query, [bookmark], bookmark.visibility == "public" and bookmark.status == "active")
  end

  defp by_visibility(query, filters) do
    query = where(query, [bookmark], bookmark.owner == ^filters.caller)

    if filters.visibility do
      where(query, [bookmark], bookmark.visibility == ^filters.visibility)
    else
      query
    end
  end

  defp by_tags(query, []), do: query

  # The text[] containment operator is the reviewed PostgreSQL-specific filter.
  defp by_tags(query, tags) do
    where(query, [bookmark], fragment("? @> ?", bookmark.tags, type(^tags, {:array, :string})))
  end

  defp by_text(query, q) do
    if String.trim(q) == "" do
      query
    else
      pattern = "%#{Query.escape_like(q)}%"
      where(query, [bookmark], ilike(bookmark.title, ^pattern) or ilike(bookmark.notes, ^pattern))
    end
  end

  defp after_cursor(query, nil), do: query

  defp after_cursor(query, cursor) do
    where(
      query,
      [bookmark],
      bookmark.created_at < ^cursor.created_at or
        (bookmark.created_at == ^cursor.created_at and bookmark.id < ^cursor.id)
    )
  end
end
