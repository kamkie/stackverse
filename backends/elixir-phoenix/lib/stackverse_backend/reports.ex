defmodule StackverseBackend.Reports do
  @moduledoc "The report context owns reporter-facing report behavior."

  import Ecto.Query

  alias StackverseBackend.{Log, Repo, Validation}
  alias StackverseBackend.Schemas.{Bookmark, Report}

  def create(reporter, bookmark_id, input) do
    Repo.transaction(fn ->
      bookmark =
        Repo.one(
          from bookmark in Bookmark,
            where: bookmark.id == ^bookmark_id,
            lock: "FOR UPDATE"
        )

      if is_nil(bookmark) or bookmark.visibility != "public" or bookmark.status != "active" do
        Repo.rollback(:not_found)
      end

      case Repo.insert(Report.create_changeset(reporter, bookmark_id, input)) do
        {:ok, report} ->
          Report.to_row(report)

        {:error, changeset} ->
          if duplicate?(changeset),
            do: Repo.rollback(:duplicate_report),
            else: raise_invalid(changeset)
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
    query = Report |> where([report], report.reporter == ^reporter) |> by_status(status)
    total = Repo.aggregate(query, :count, :id)

    items =
      query
      |> order_by([report], desc: report.created_at, desc: report.id)
      |> limit(^size)
      |> offset(^(page * size))
      |> Repo.all()
      |> Enum.map(&Report.to_row/1)

    %{items: items, total: total}
  end

  def update(reporter, id, body) do
    Repo.transaction(fn ->
      report = Repo.one(from report in Report, where: report.id == ^id, lock: "FOR UPDATE")

      if is_nil(report) or report.reporter != reporter, do: Repo.rollback(:not_found)

      with {:ok, input} <- Validation.validate_report(body) do
        if report.status != "open", do: Repo.rollback(:not_open)

        updated = report |> Report.update_changeset(input) |> Repo.update!()

        Log.event(:info, "report_updated", "success", "Report updated by its reporter",
          actor: reporter,
          resource_type: "report",
          resource_id: id,
          bookmark_id: report.bookmark_id,
          reason: input.reason
        )

        Report.to_row(updated)
      else
        {:error, errors} -> Repo.rollback({:validation, errors})
      end
    end)
  end

  def withdraw(reporter, id) do
    Repo.transaction(fn ->
      report = Repo.one(from report in Report, where: report.id == ^id, lock: "FOR UPDATE")

      cond do
        is_nil(report) or report.reporter != reporter ->
          Repo.rollback(:not_found)

        report.status != "open" ->
          Repo.rollback(:not_open)

        true ->
          Repo.delete!(report)

          Log.event(:info, "report_withdrawn", "success", "Report withdrawn by its reporter",
            actor: reporter,
            resource_type: "report",
            resource_id: id,
            bookmark_id: report.bookmark_id
          )
      end
    end)
  end

  defp by_status(query, nil), do: query
  defp by_status(query, status), do: where(query, [report], report.status == ^status)

  defp duplicate?(changeset) do
    Enum.any?(changeset.errors, fn {_field, {_message, metadata}} ->
      metadata[:constraint] == :unique
    end)
  end

  defp raise_invalid(changeset),
    do: raise(Ecto.InvalidChangesetError, action: changeset.action, changeset: changeset)
end
