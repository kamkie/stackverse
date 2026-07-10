defmodule StackverseBackend.Bookmarks do
  @moduledoc "The bookmark context owns bookmark persistence and visibility rules."

  alias StackverseBackend.{Query, Repo}

  @select "id::text as id, owner, url, title, notes, tags, visibility, status, created_at, updated_at"

  def list(filters, page, size) do
    {where, binds} = where(filters)
    total = Query.scalar!("select count(*)::int from bookmarks where #{where}", binds)

    items =
      Query.all!(
        """
        select #{@select} from bookmarks
        where #{where}
        order by created_at desc, id desc
        limit $#{length(binds) + 1} offset $#{length(binds) + 2}
        """,
        binds ++ [size, page * size]
      )

    %{items: items, total: total}
  end

  def list_cursor(filters, cursor, size) do
    {where, binds} = where(filters)
    {where, binds} = append_cursor(where, binds, cursor)

    Query.all!(
      """
      select #{@select} from bookmarks
      where #{where}
      order by created_at desc, id desc
      limit $#{length(binds) + 1}
      """,
      binds ++ [size + 1]
    )
  end

  def create(owner, input) do
    id = Ecto.UUID.generate()
    now = Query.now()

    bookmark =
      Query.one!(
        """
        insert into bookmarks (id, owner, url, title, notes, tags, visibility, status, created_at, updated_at)
        values ($1::text::uuid, $2, $3, $4, $5, $6::text[], $7, 'active', $8, $8)
        returning #{@select}
        """,
        [
          id,
          owner,
          input.url,
          input.title,
          input.notes,
          input.tags,
          input.visibility,
          now
        ]
      )

    {id, bookmark}
  end

  def get(id), do: Query.one("select #{@select} from bookmarks where id = $1::text::uuid", [id])

  def update(owner, id, input) do
    Repo.transaction(fn ->
      bookmark =
        Query.one!("select #{@select} from bookmarks where id = $1::text::uuid for update", [
          id
        ])

      cond do
        is_nil(bookmark) or bookmark["owner"] != owner ->
          Repo.rollback(:not_found)

        bookmark["status"] == "hidden" and input.visibility == "public" ->
          Repo.rollback(
            {:conflict, "This bookmark was hidden by moderation and cannot be made public."}
          )

        true ->
          Query.one!(
            """
            update bookmarks
            set url = $2, title = $3, notes = $4, tags = $5::text[], visibility = $6, updated_at = $7
            where id = $1::text::uuid
            returning #{@select}
            """,
            [id, input.url, input.title, input.notes, input.tags, input.visibility, Query.now()]
          )
      end
    end)
  end

  def delete(owner, id) do
    case Query.one(
           "delete from bookmarks where id = $1::text::uuid and owner = $2 returning id::text as id",
           [id, owner]
         ) do
      nil -> :not_found
      _row -> :ok
    end
  end

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

  defp where(filters) do
    {conditions, binds} =
      if filters.visibility == "public" do
        {["visibility = 'public' and status = 'active'"], []}
      else
        conditions = ["owner = $1"]
        binds = [filters.caller]

        if filters.visibility do
          {conditions ++ ["visibility = $2"], binds ++ [filters.visibility]}
        else
          {conditions, binds}
        end
      end

    {conditions, binds} =
      if filters.tags != [] do
        index = length(binds) + 1
        {conditions ++ ["tags @> $#{index}::text[]"], binds ++ [filters.tags]}
      else
        {conditions, binds}
      end

    {conditions, binds} =
      if String.trim(filters.q) != "" do
        index = length(binds) + 1
        pattern = "%#{Query.escape_like(filters.q)}%"

        {conditions ++
           ["(title ilike $#{index} escape '\\' or notes ilike $#{index} escape '\\')"],
         binds ++ [pattern]}
      else
        {conditions, binds}
      end

    {Enum.join(conditions, " and "), binds}
  end

  defp append_cursor(where, binds, nil), do: {where, binds}

  defp append_cursor(where, binds, cursor) do
    created_index = length(binds) + 1
    id_index = length(binds) + 2

    {
      "#{where} and (created_at < $#{created_index}::timestamptz or (created_at = $#{created_index}::timestamptz and id < $#{id_index}::text::uuid))",
      binds ++ [cursor.created_at, cursor.id]
    }
  end
end
