defmodule StackverseBackend.Moderation do
  @moduledoc "The moderation context owns report resolution and bookmark moderation."

  alias StackverseBackend.{Audit, Log, Query, Repo}

  @report_select "id::text as id, bookmark_id::text as bookmark_id, reporter, reason, comment, status, resolved_by, resolved_at, resolution_note, created_at"
  @bookmark_select "id::text as id, owner, url, title, notes, tags, visibility, status, created_at, updated_at"

  def list_reports(status, page, size) do
    total = Query.scalar!("select count(*)::int from reports where status = $1", [status])

    items =
      Query.all!(
        """
        select #{@report_select} from reports
        where status = $1
        order by created_at asc, id asc
        limit $2 offset $3
        """,
        [status, size, page * size]
      )

    %{items: items, total: total}
  end

  def resolve_report(actor, id, input) do
    Repo.transaction(fn ->
      if input.resolution == "actioned" do
        case Query.one!(
               "select bookmark_id::text as bookmark_id from reports where id = $1::text::uuid",
               [id]
             ) do
          nil ->
            Repo.rollback(:not_found)

          row ->
            Repo.query!("select id from bookmarks where id = $1::text::uuid for update", [
              row["bookmark_id"]
            ])
        end
      end

      report =
        Query.one!("select #{@report_select} from reports where id = $1::text::uuid for update", [
          id
        ])

      if is_nil(report), do: Repo.rollback(:not_found)

      if input.resolution == "open" do
        reopen_report(report, actor)
      else
        resolved = resolve_one(report, input.resolution, actor, input.note, false)

        if input.resolution == "actioned" do
          hide_bookmark(actor, report["bookmark_id"], input.note)

          Query.all!(
            """
            select #{@report_select} from reports
            where bookmark_id = $1::text::uuid and status = 'open' and id <> $2::text::uuid
            order by id asc
            for update
            """,
            [report["bookmark_id"], id]
          )
          |> Enum.each(&resolve_one(&1, "actioned", actor, input.note, true))
        end

        resolved
      end
    end)
  end

  def set_bookmark_status(actor, id, input) do
    Repo.transaction(fn ->
      bookmark =
        Query.one!(
          "select #{@bookmark_select} from bookmarks where id = $1::text::uuid for update",
          [id]
        )

      if is_nil(bookmark), do: Repo.rollback(:not_found)

      updated =
        Query.one!(
          """
          update bookmarks set status = $2, updated_at = $3
          where id = $1::text::uuid
          returning #{@bookmark_select}
          """,
          [id, input.status, Query.now()]
        )

      Audit.record!(actor, "bookmark.status-changed", "bookmark", id, %{
        from: bookmark["status"],
        to: input.status,
        note: input.note
      })

      Log.event(:info, "bookmark_status_changed", "success", "Bookmark moderation status changed",
        actor: actor,
        resource_type: "bookmark",
        resource_id: id,
        from: bookmark["status"],
        to: input.status
      )

      updated
    end)
  end

  defp reopen_report(report, actor) do
    if Query.one!(
         "select 1 as exists from reports where bookmark_id = $1::text::uuid and reporter = $2 and status = 'open' and id <> $3::text::uuid",
         [report["bookmark_id"], report["reporter"], report["id"]]
       ) do
      Repo.rollback(:duplicate_report)
    end

    case Repo.query(
           """
           update reports
           set status = 'open', resolved_by = null, resolved_at = null, resolution_note = null
           where id = $1::text::uuid
           returning #{@report_select}
           """,
           [report["id"]]
         ) do
      {:ok, result} ->
        reopened = result |> Query.rows() |> List.first()

        Audit.record!(actor, "report.reopened", "report", report["id"], %{
          bookmarkId: report["bookmark_id"]
        })

        Log.event(:info, "report_reopened", "success", "Report re-opened",
          actor: actor,
          resource_type: "report",
          resource_id: report["id"],
          bookmark_id: report["bookmark_id"]
        )

        reopened

      {:error, %Postgrex.Error{postgres: %{code: :unique_violation}}} ->
        Repo.rollback(:duplicate_report)

      {:error, error} ->
        raise error
    end
  end

  defp resolve_one(report, resolution, actor, note, auto_resolved) do
    resolved =
      Query.one!(
        """
        update reports
        set status = $2, resolved_by = $3, resolved_at = $4, resolution_note = $5
        where id = $1::text::uuid
        returning #{@report_select}
        """,
        [report["id"], resolution, actor, Query.now(), note]
      )

    Audit.record!(actor, "report.resolved", "report", report["id"], %{
      bookmarkId: report["bookmark_id"],
      resolution: resolution,
      note: note,
      autoResolved: auto_resolved
    })

    Log.event(:info, "report_resolved", "success", "Report resolved",
      actor: actor,
      resource_type: "report",
      resource_id: report["id"],
      bookmark_id: report["bookmark_id"],
      resolution: resolution,
      auto_resolved: auto_resolved
    )

    resolved
  end

  defp hide_bookmark(actor, bookmark_id, note) do
    bookmark =
      Query.one!("select #{@bookmark_select} from bookmarks where id = $1::text::uuid", [
        bookmark_id
      ])

    if is_nil(bookmark), do: Repo.rollback(:not_found)

    if bookmark["status"] != "hidden" do
      Repo.query!(
        "update bookmarks set status = 'hidden', updated_at = $2 where id = $1::text::uuid",
        [
          bookmark_id,
          Query.now()
        ]
      )

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
end
