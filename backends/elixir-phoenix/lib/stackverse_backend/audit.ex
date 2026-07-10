defmodule StackverseBackend.Audit do
  @moduledoc false

  import Ecto.Query

  alias StackverseBackend.Repo
  alias StackverseBackend.Schemas.AuditEntry

  def list(filters, page, size) do
    query = filtered_query(filters)
    total = Repo.aggregate(query, :count, :id)

    items =
      query
      |> order_by([entry], desc: entry.created_at, desc: entry.id)
      |> limit(^size)
      |> offset(^(page * size))
      |> Repo.all()
      |> Enum.map(&AuditEntry.to_row/1)

    %{items: items, total: total}
  end

  def record!(actor, action, target_type, target_id, detail) do
    %{
      actor: actor,
      action: action,
      target_type: target_type,
      target_id: target_id,
      detail: detail
    }
    |> AuditEntry.changeset()
    |> Repo.insert!()
  end

  defp filtered_query(filters) do
    AuditEntry
    |> equal(:actor, filters.actor)
    |> equal(:action, filters.action)
    |> equal(:target_type, filters.target_type)
    |> equal(:target_id, filters.target_id)
    |> after_time(filters.from)
    |> before_time(filters.to)
  end

  defp equal(query, _field, nil), do: query

  defp equal(query, field_name, value),
    do: where(query, [entry], field(entry, ^field_name) == ^value)

  defp after_time(query, nil), do: query
  defp after_time(query, value), do: where(query, [entry], entry.created_at >= ^value)

  defp before_time(query, nil), do: query
  defp before_time(query, value), do: where(query, [entry], entry.created_at <= ^value)
end
