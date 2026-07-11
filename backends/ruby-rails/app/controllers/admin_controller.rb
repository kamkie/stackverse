class AdminController < ApplicationController
  def reports
    require_role!("moderator")
    page, size = query_params.page_and_size
    status = Stackverse::InputValidation.validate_report_status(query_params.single("status")) || "open"
    rows = Stackverse::Sql.query(<<~SQL.squish)
      select * from reports where status = #{Stackverse::Sql.quote(status)}
      order by created_at asc, id asc
      limit #{size} offset #{page * size}
    SQL
    total = Stackverse::Sql.one("select count(*)::int as count from reports where status = #{Stackverse::Sql.quote(status)}")["count"].to_i
    render_json Stackverse::Sql.page(rows, page, size, total, Stackverse::Serializers.method(:report))
  end

  def resolve_report
    caller = require_role!("moderator")
    report_id = Stackverse::InputValidation.parse_uuid(params[:id])
    target, note = Stackverse::InputValidation.validate_resolution(body_hash)
    row = nil
    ActiveRecord::Base.transaction do
      if target == "actioned"
        bookmark_ref = Stackverse::Sql.one("select bookmark_id from reports where id = #{Stackverse::Sql.quote(report_id)}::uuid")
        Stackverse::Errors.not_found unless bookmark_ref
        Stackverse::Sql.connection.execute("select id from bookmarks where id = #{Stackverse::Sql.quote(bookmark_ref["bookmark_id"])}::uuid for update")
      end

      report = Stackverse::Sql.one("select * from reports where id = #{Stackverse::Sql.quote(report_id)}::uuid for update")
      Stackverse::Errors.not_found unless report

      if target == "open"
        row = reopen_report!(report, caller)
      else
        row = resolve_one!(report, target, caller.username, note, false)
        if target == "actioned"
          hide_bookmark!(report["bookmark_id"].to_s, caller.username, note)
          siblings = Stackverse::Sql.query(<<~SQL.squish)
            select * from reports
            where bookmark_id = #{Stackverse::Sql.quote(report["bookmark_id"])}::uuid
              and status = 'open'
              and id <> #{Stackverse::Sql.quote(report_id)}::uuid
            order by id asc
            for update
          SQL
          siblings.each { |sibling| resolve_one!(sibling, "actioned", caller.username, note, true) }
        end
      end
    end
    render_json Stackverse::Serializers.report(row)
  rescue ActiveRecord::RecordNotUnique
    Stackverse::Errors.conflict("The reporter already has another open report on this bookmark.")
  end

  def set_bookmark_status
    caller = require_role!("moderator")
    bookmark_id = Stackverse::InputValidation.parse_uuid(params[:id])
    status, note = Stackverse::InputValidation.validate_bookmark_status(body_hash)
    row = nil
    existing = nil
    ActiveRecord::Base.transaction do
      existing = Stackverse::Sql.one("select * from bookmarks where id = #{Stackverse::Sql.quote(bookmark_id)}::uuid for update")
      Stackverse::Errors.not_found unless existing
      row = Stackverse::Sql.one(<<~SQL.squish)
        update bookmarks
        set status = #{Stackverse::Sql.quote(status)},
            updated_at = #{Stackverse::Sql.quote(Stackverse::Clock.now)}
        where id = #{Stackverse::Sql.quote(bookmark_id)}::uuid
        returning *
      SQL
      Stackverse::Audit.record(
        caller.username,
        "bookmark.status-changed",
        "bookmark",
        bookmark_id,
        { from: existing["status"], to: status, note: note }
      )
    end
    Stackverse::EventLog.info(
      "bookmark_status_changed",
      "success",
      "Bookmark moderation status changed",
      actor: caller.username,
      resource_type: "bookmark",
      resource_id: bookmark_id,
      from: existing["status"],
      to: status
    )
    render_json Stackverse::Serializers.bookmark(row)
  end

  def users
    require_role!("admin")
    page, size = query_params.page_and_size
    q = query_params.single("q")
    Stackverse::InputValidation.max_length(q, 100, "q")
    status = query_params.single("status")
    Stackverse::Errors.bad_request("unknown status: #{status}") if status && !%w[active blocked].include?(status)

    conditions = [ "true" ]
    conditions << "u.username ilike #{Stackverse::Sql.quote(Stackverse::Sql.like_pattern(q))} escape E'\\\\'" if q.present?
    conditions << "u.status = #{Stackverse::Sql.quote(status)}" if status
    where_sql = conditions.join(" and ")
    rows = Stackverse::Sql.query(<<~SQL.squish)
      #{with_bookmark_count} where #{where_sql}
      order by u.last_seen desc, u.username asc
      limit #{size} offset #{page * size}
    SQL
    total = Stackverse::Sql.one("select count(*)::int as count from user_accounts u where #{where_sql}")["count"].to_i
    render_json Stackverse::Sql.page(rows, page, size, total, Stackverse::Serializers.method(:user_account))
  end

  def user
    require_role!("admin")
    row = find_account(params[:username])
    Stackverse::Errors.not_found unless row
    render_json Stackverse::Serializers.user_account(row)
  end

  def set_user_status
    caller = require_role!("admin")
    username = params[:username]
    status, reason = Stackverse::InputValidation.validate_user_status(body_hash, username, caller.username)
    ActiveRecord::Base.transaction do
      existing = Stackverse::Sql.one("select username from user_accounts where username = #{Stackverse::Sql.quote(username)} for update")
      Stackverse::Errors.not_found unless existing
      if status == "blocked"
        Stackverse::Sql.connection.execute(<<~SQL.squish)
          update user_accounts
          set status = 'blocked', blocked_reason = #{Stackverse::Sql.quote(reason)}
          where username = #{Stackverse::Sql.quote(username)}
        SQL
        Stackverse::Audit.record(caller.username, "user.blocked", "user", username, { reason: reason })
      else
        Stackverse::Sql.connection.execute(<<~SQL.squish)
          update user_accounts
          set status = 'active', blocked_reason = null
          where username = #{Stackverse::Sql.quote(username)}
        SQL
        Stackverse::Audit.record(caller.username, "user.unblocked", "user", username)
      end
    end
    Stackverse::EventLog.info(
      status == "blocked" ? "user_blocked" : "user_unblocked",
      "success",
      status == "blocked" ? "User account blocked" : "User account unblocked",
      actor: caller.username,
      resource_type: "user",
      resource_id: username
    )
    row = find_account(username)
    Stackverse::Errors.not_found unless row
    render_json Stackverse::Serializers.user_account(row)
  end

  def audit_log
    require_role!("admin")
    page, size = query_params.page_and_size
    filters = {
      "actor" => "actor",
      "action" => "action",
      "targetType" => "target_type",
      "targetId" => "target_id"
    }
    conditions = [ "true" ]
    filters.each do |param_name, column|
      value = query_params.single(param_name)
      conditions << "#{column} = #{Stackverse::Sql.quote(value)}" if value
    end
    from = Stackverse::InputValidation.parse_datetime(query_params.single("from"), "from")
    to = Stackverse::InputValidation.parse_datetime(query_params.single("to"), "to")
    conditions << "created_at >= #{Stackverse::Sql.quote(from)}" if from
    conditions << "created_at <= #{Stackverse::Sql.quote(to)}" if to
    where_sql = conditions.join(" and ")
    rows = Stackverse::Sql.query(<<~SQL.squish)
      select * from audit_entries where #{where_sql}
      order by created_at desc, id desc
      limit #{size} offset #{page * size}
    SQL
    total = Stackverse::Sql.one("select count(*)::int as count from audit_entries where #{where_sql}")["count"].to_i
    render_json Stackverse::Sql.page(rows, page, size, total, Stackverse::Serializers.method(:audit))
  end

  def stats
    require_role!("moderator")
    today = Time.now.utc.to_date
    start = today - 29
    created_per_day = count_per_day("bookmarks", "created_at", start)
    active_per_day = count_per_day("user_accounts", "last_seen", start)
    daily = (0...30).map do |offset|
      day = (start + offset).iso8601
      {
        date: day,
        bookmarksCreated: created_per_day.fetch(day, 0),
        activeUsers: active_per_day.fetch(day, 0)
      }
    end
    totals = Stackverse::Sql.one(<<~SQL.squish)
      select
        (select count(*)::int from user_accounts) as users,
        (select count(*)::int from bookmarks) as bookmarks,
        (select count(*)::int from bookmarks where visibility = 'public') as public_bookmarks,
        (select count(*)::int from bookmarks where status = 'hidden') as hidden_bookmarks,
        (select count(*)::int from reports where status = 'open') as open_reports
    SQL
    payload = {
      totals: {
        users: totals["users"].to_i,
        bookmarks: totals["bookmarks"].to_i,
        publicBookmarks: totals["public_bookmarks"].to_i,
        hiddenBookmarks: totals["hidden_bookmarks"].to_i,
        openReports: totals["open_reports"].to_i
      },
      daily: daily,
      topTags: Stackverse::Sql.query(<<~SQL.squish)
        select tag, count(*)::int as count
        from bookmarks, unnest(tags) as tag
        group by tag
        order by count desc, tag asc
        limit 10
      SQL
    }
    render_etag payload
  end

  private

  def with_bookmark_count
    <<~SQL.squish
      select u.*, (select count(*)::int from bookmarks b where b.owner = u.username) as bookmark_count
      from user_accounts u
    SQL
  end

  def find_account(username)
    Stackverse::Sql.one("#{with_bookmark_count} where u.username = #{Stackverse::Sql.quote(username)}")
  end

  def reopen_report!(report, caller)
    conflict = Stackverse::Sql.one(<<~SQL.squish)
      select 1 from reports
      where bookmark_id = #{Stackverse::Sql.quote(report["bookmark_id"])}::uuid
        and reporter = #{Stackverse::Sql.quote(report["reporter"])}
        and status = 'open'
        and id <> #{Stackverse::Sql.quote(report["id"])}::uuid
    SQL
    Stackverse::Errors.conflict("The reporter already has another open report on this bookmark.") if conflict
    row = Stackverse::Sql.one(<<~SQL.squish)
      update reports
      set status = 'open', resolved_by = null, resolved_at = null, resolution_note = null
      where id = #{Stackverse::Sql.quote(report["id"])}::uuid
      returning *
    SQL
    Stackverse::Audit.record(caller.username, "report.reopened", "report", report["id"].to_s, { bookmarkId: report["bookmark_id"].to_s })
    Stackverse::EventLog.info(
      "report_reopened",
      "success",
      "Report re-opened",
      actor: caller.username,
      resource_type: "report",
      resource_id: report["id"].to_s,
      bookmark_id: report["bookmark_id"].to_s
    )
    row
  end

  def resolve_one!(report, resolution, actor, note, auto_resolved)
    row = Stackverse::Sql.one(<<~SQL.squish)
      update reports
      set status = #{Stackverse::Sql.quote(resolution)},
          resolved_by = #{Stackverse::Sql.quote(actor)},
          resolved_at = #{Stackverse::Sql.quote(Stackverse::Clock.now)},
          resolution_note = #{Stackverse::Sql.quote(note)}
      where id = #{Stackverse::Sql.quote(report["id"])}::uuid
      returning *
    SQL
    Stackverse::Audit.record(
      actor,
      "report.resolved",
      "report",
      report["id"].to_s,
      {
        bookmarkId: report["bookmark_id"].to_s,
        resolution: resolution,
        note: note,
        autoResolved: auto_resolved
      }
    )
    Stackverse::EventLog.info(
      "report_resolved",
      "success",
      "Report resolved",
      actor: actor,
      resource_type: "report",
      resource_id: report["id"].to_s,
      bookmark_id: report["bookmark_id"].to_s,
      resolution: resolution,
      auto_resolved: auto_resolved
    )
    row
  end

  def hide_bookmark!(bookmark_id, actor, note)
    bookmark = Stackverse::Sql.one("select * from bookmarks where id = #{Stackverse::Sql.quote(bookmark_id)}::uuid")
    Stackverse::Errors.not_found unless bookmark
    return if bookmark["status"] == "hidden"

    Stackverse::Sql.connection.execute(<<~SQL.squish)
      update bookmarks
      set status = 'hidden', updated_at = #{Stackverse::Sql.quote(Stackverse::Clock.now)}
      where id = #{Stackverse::Sql.quote(bookmark_id)}::uuid
    SQL
    Stackverse::Audit.record(
      actor,
      "bookmark.status-changed",
      "bookmark",
      bookmark_id,
      { from: "active", to: "hidden", note: note }
    )
    Stackverse::EventLog.info(
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

  def count_per_day(table, column, start_date)
    allowed = {
      [ "bookmarks", "created_at" ] => true,
      [ "user_accounts", "last_seen" ] => true
    }
    raise ArgumentError, "unsupported daily series" unless allowed[[ table, column ]]

    start_time = start_date.to_time.utc
    rows = Stackverse::Sql.query(<<~SQL.squish)
      select (#{column} at time zone 'UTC')::date::text as day, count(*)::int as count
      from #{table}
      where #{column} >= #{Stackverse::Sql.quote(start_time)}
      group by day
    SQL
    rows.each_with_object({}) do |row, counts|
      counts[row["day"]] = row["count"].to_i
    end
  end
end
