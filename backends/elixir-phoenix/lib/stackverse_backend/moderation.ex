defmodule StackverseBackend.Moderation do
  @moduledoc "The moderation context owns report resolution and bookmark moderation."

  import Ecto.Query

  alias StackverseBackend.{Audit, Log, Persistence, Repo}
  alias StackverseBackend.Schemas.{Bookmark, Report}

  def list_reports(status, page, size) do
    query = from report in Report, where: report.status == ^status
    total = Repo.aggregate(query, :count, :id)

    items =
      query
      |> report_order(status)
      |> limit(^size)
      |> offset(^(page * size))
      |> Repo.all()
      |> Enum.map(&Report.to_row/1)

    %{items: items, total: total}
  end

  def resolve_report(actor, id, input) do
    Repo.transaction(fn ->
      if input.resolution == "actioned" do
        case Repo.one(from report in Report, where: report.id == ^id, select: report.bookmark_id) do
          nil -> Repo.rollback(:not_found)
          bookmark_id -> lock_bookmark(bookmark_id)
        end
      end

      report = Repo.one(from report in Report, where: report.id == ^id, lock: "FOR UPDATE")
      if is_nil(report), do: Repo.rollback(:not_found)

      if input.resolution == "open" do
        reopen_report(report, actor)
      else
        resolved = resolve_one(report, input.resolution, actor, input.note, false)

        if input.resolution == "actioned" do
          hide_bookmark(actor, report.bookmark_id, input.note)

          from(other in Report,
            where:
              other.bookmark_id == ^report.bookmark_id and other.status == "open" and
                other.id != ^report.id,
            order_by: [asc: other.id],
            lock: "FOR UPDATE"
          )
          |> Repo.all()
          |> Enum.each(&resolve_one(&1, "actioned", actor, input.note, true))
        end

        resolved
      end
    end)
  end

  def set_bookmark_status(actor, id, input) do
    Repo.transaction(fn ->
      bookmark =
        Repo.one(from bookmark in Bookmark, where: bookmark.id == ^id, lock: "FOR UPDATE")

      if is_nil(bookmark), do: Repo.rollback(:not_found)

      updated = bookmark |> Bookmark.status_changeset(input.status) |> Repo.update!()

      Audit.record!(actor, "bookmark.status-changed", "bookmark", id, %{
        from: bookmark.status,
        to: input.status,
        note: input.note
      })

      Log.event(:info, "bookmark_status_changed", "success", "Bookmark moderation status changed",
        actor: actor,
        resource_type: "bookmark",
        resource_id: id,
        from: bookmark.status,
        to: input.status
      )

      Bookmark.to_row(updated)
    end)
  end

  defp reopen_report(report, actor) do
    duplicate? =
      Repo.exists?(
        from other in Report,
          where:
            other.bookmark_id == ^report.bookmark_id and other.reporter == ^report.reporter and
              other.status == "open" and other.id != ^report.id
      )

    if duplicate?, do: Repo.rollback(:duplicate_report)

    case report |> Report.resolution_changeset("open", actor, nil) |> Repo.update() do
      {:ok, reopened} ->
        Audit.record!(actor, "report.reopened", "report", report.id, %{
          bookmarkId: report.bookmark_id
        })

        Log.event(:info, "report_reopened", "success", "Report re-opened",
          actor: actor,
          resource_type: "report",
          resource_id: report.id,
          bookmark_id: report.bookmark_id
        )

        Report.to_row(reopened)

      {:error, changeset} ->
        if Persistence.unique_constraint?(changeset),
          do: Repo.rollback(:duplicate_report),
          else: Persistence.raise_invalid!(changeset)
    end
  end

  defp resolve_one(report, resolution, actor, note, auto_resolved) do
    resolved = report |> Report.resolution_changeset(resolution, actor, note) |> Repo.update!()

    Audit.record!(actor, "report.resolved", "report", report.id, %{
      bookmarkId: report.bookmark_id,
      resolution: resolution,
      note: note,
      autoResolved: auto_resolved
    })

    Log.event(:info, "report_resolved", "success", "Report resolved",
      actor: actor,
      resource_type: "report",
      resource_id: report.id,
      bookmark_id: report.bookmark_id,
      resolution: resolution,
      auto_resolved: auto_resolved
    )

    Report.to_row(resolved)
  end

  defp hide_bookmark(actor, bookmark_id, note) do
    case Repo.get(Bookmark, bookmark_id) do
      nil ->
        Repo.rollback(:not_found)

      %{status: "hidden"} ->
        :ok

      bookmark ->
        bookmark |> Bookmark.status_changeset("hidden") |> Repo.update!()

        Audit.record!(actor, "bookmark.status-changed", "bookmark", bookmark_id, %{
          from: "active",
          to: "hidden",
          note: note
        })

        Log.event(
          :info,
          "bookmark_status_changed",
          "success",
          "Bookmark hidden by an actioned report",
          actor: actor,
          resource_type: "bookmark",
          resource_id: bookmark_id,
          from: "active",
          to: "hidden"
        )
    end
  end

  defp lock_bookmark(bookmark_id) do
    case Repo.one(
           from bookmark in Bookmark, where: bookmark.id == ^bookmark_id, lock: "FOR UPDATE"
         ) do
      nil -> Repo.rollback(:not_found)
      bookmark -> bookmark
    end
  end

  defp report_order(query, "open"),
    do: order_by(query, [report], asc: report.created_at, asc: report.id)

  defp report_order(query, _status),
    do: order_by(query, [report], desc: report.created_at, desc: report.id)
end
