defmodule StackverseBackend.Audit do
  @moduledoc false

  alias StackverseBackend.{Query, Repo}

  def list(filters, page, size) do
    {where, binds} = where(filters)
    total = Query.scalar!("select count(*)::int from audit_entries where #{where}", binds)

    items =
      Query.all!(
        """
        select id::text as id, actor, action, target_type, target_id, detail, created_at
        from audit_entries
        where #{where}
        order by created_at desc, id desc
        limit $#{length(binds) + 1} offset $#{length(binds) + 2}
        """,
        binds ++ [size, page * size]
      )

    %{items: items, total: total}
  end

  def record!(actor, action, target_type, target_id, detail) do
    encoded = if is_nil(detail), do: nil, else: Jason.encode!(detail)

    Repo.query!(
      """
      insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at)
      values ($1::text::uuid, $2, $3, $4, $5, $6::jsonb, $7)
      """,
      [Ecto.UUID.generate(), actor, action, target_type, target_id, encoded, Query.now()]
    )
  end

  defp where(filters) do
    [
      {:actor, "actor ="},
      {:action, "action ="},
      {:target_type, "target_type ="},
      {:target_id, "target_id ="},
      {:from, "created_at >="},
      {:to, "created_at <="}
    ]
    |> Enum.reduce({["true"], []}, fn {key, op}, {conditions, binds} ->
      case Map.fetch!(filters, key) do
        nil ->
          {conditions, binds}

        value ->
          index = length(binds) + 1
          cast = if key in [:from, :to], do: "::timestamptz", else: ""
          {conditions ++ ["#{op} $#{index}#{cast}"], binds ++ [value]}
      end
    end)
    |> then(fn {conditions, binds} -> {Enum.join(conditions, " and "), binds} end)
  end
end
