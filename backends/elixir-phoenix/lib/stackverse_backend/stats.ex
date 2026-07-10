defmodule StackverseBackend.Stats do
  @moduledoc "The stats context owns aggregate operational statistics queries."

  import Ecto.Query

  alias StackverseBackend.{Query, Repo}
  alias StackverseBackend.Schemas.{Bookmark, Report, UserAccount}

  def get do
    today = Date.utc_today()
    from_date = Date.add(today, -29)
    from = DateTime.new!(from_date, ~T[00:00:00], "Etc/UTC")

    totals = %{
      users: Repo.aggregate(UserAccount, :count, :username),
      bookmarks: Repo.aggregate(Bookmark, :count, :id),
      publicBookmarks:
        Repo.aggregate(
          from(bookmark in Bookmark, where: bookmark.visibility == "public"),
          :count,
          :id
        ),
      hiddenBookmarks:
        Repo.aggregate(
          from(bookmark in Bookmark, where: bookmark.status == "hidden"),
          :count,
          :id
        ),
      openReports:
        Repo.aggregate(from(report in Report, where: report.status == "open"), :count, :id)
    }

    bookmarks_by_day = count_per_day("bookmarks", "created_at", from)
    active_by_day = count_per_day("user_accounts", "last_seen", from)

    daily =
      Enum.map(0..29, fn offset ->
        date = from_date |> Date.add(offset) |> Date.to_iso8601()

        %{
          date: date,
          bookmarksCreated: Map.get(bookmarks_by_day, date, 0),
          activeUsers: Map.get(active_by_day, date, 0)
        }
      end)

    top_tags =
      Query.all!(
        """
        select tag, count(*)::int as count
        from bookmarks, unnest(tags) as tag
        group by tag
        order by count desc, tag asc
        limit 10
        """,
        []
      )
      |> Enum.map(&%{tag: &1["tag"], count: &1["count"]})

    %{totals: totals, daily: daily, topTags: top_tags}
  end

  defp count_per_day(table, column, from) do
    Query.all!(
      """
      select (#{column} at time zone 'UTC')::date::text as day, count(*)::int as count
      from #{table}
      where #{column} >= $1::timestamptz
      group by day
      """,
      [from]
    )
    |> Map.new(&{&1["day"], &1["count"]})
  end
end
