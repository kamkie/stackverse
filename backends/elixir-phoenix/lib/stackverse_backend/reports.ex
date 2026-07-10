defmodule StackverseBackend.Reports do
  @moduledoc "The report context owns reporter-facing report behavior."

  alias StackverseBackend.{Log, Query, Repo, Validation}

  @select "id::text as id, bookmark_id::text as bookmark_id, reporter, reason, comment, status, resolved_by, resolved_at, resolution_note, created_at"

  def create(reporter, bookmark_id, input) do
    Repo.transaction(fn ->
      bookmark =
        Query.one!(
          "select visibility, status from bookmarks where id = $1::text::uuid for update",
          [bookmark_id]
        )

      if is_nil(bookmark) or bookmark["visibility"] != "public" or
           bookmark["status"] != "active" do
        Repo.rollback(:not_found)
      end

      if Query.one!(
           "select 1 as exists from reports where bookmark_id = $1::text::uuid and reporter = $2 and status = 'open'",
           [bookmark_id, reporter]
         ) do
        Repo.rollback(:duplicate_report)
      end

      id = Ecto.UUID.generate()

      case Repo.query(
             """
             insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at)
             values ($1::text::uuid, $2::text::uuid, $3, $4, $5, 'open', $6)
             returning #{@select}
             """,
             [id, bookmark_id, reporter, input.reason, input.comment, Query.now()]
           ) do
        {:ok, result} ->
          result |> Query.rows() |> List.first()

        {:error, %Postgrex.Error{postgres: %{code: :unique_violation}}} ->
          Repo.rollback(:duplicate_report)

        {:error, error} ->
          raise error
      end
    end)
    |> case do
      {:ok, report} = result ->
        Log.event(:info, "report_created", "success", "Report created on a public bookmark",
          actor: reporter,
          resource_type: "report",
          resource_id: report["id"],
          bookmark_id: bookmark_id,
          reason: report["reason"]
        )

        result

      result ->
        result
    end
  end

  def list(reporter, status, page, size) do
    {status_sql, binds} =
      if status do
        {" and status = $2", [reporter, status]}
      else
        {"", [reporter]}
      end

    total =
      Query.scalar!("select count(*)::int from reports where reporter = $1#{status_sql}", binds)

    items =
      Query.all!(
        """
        select #{@select} from reports
        where reporter = $1#{status_sql}
        order by created_at desc, id desc
        limit $#{length(binds) + 1} offset $#{length(binds) + 2}
        """,
        binds ++ [size, page * size]
      )

    %{items: items, total: total}
  end

  def update(reporter, id, body) do
    Repo.transaction(fn ->
      report =
        Query.one!("select #{@select} from reports where id = $1::text::uuid for update", [id])

      if is_nil(report) or report["reporter"] != reporter, do: Repo.rollback(:not_found)

      with {:ok, input} <- Validation.validate_report(body) do
        if report["status"] != "open", do: Repo.rollback(:not_open)

        updated =
          Query.one!(
            """
            update reports set reason = $2, comment = $3
            where id = $1::text::uuid
            returning #{@select}
            """,
            [id, input.reason, input.comment]
          )

        Log.event(:info, "report_updated", "success", "Report updated by its reporter",
          actor: reporter,
          resource_type: "report",
          resource_id: id,
          bookmark_id: report["bookmark_id"],
          reason: input.reason
        )

        updated
      else
        {:error, errors} -> Repo.rollback({:validation, errors})
      end
    end)
  end

  def withdraw(reporter, id) do
    Repo.transaction(fn ->
      report =
        Query.one!("select #{@select} from reports where id = $1::text::uuid for update", [id])

      cond do
        is_nil(report) or report["reporter"] != reporter ->
          Repo.rollback(:not_found)

        report["status"] != "open" ->
          Repo.rollback(:not_open)

        true ->
          Repo.query!("delete from reports where id = $1::text::uuid", [id])

          Log.event(:info, "report_withdrawn", "success", "Report withdrawn by its reporter",
            actor: reporter,
            resource_type: "report",
            resource_id: id,
            bookmark_id: report["bookmark_id"]
          )
      end
    end)
  end
end
