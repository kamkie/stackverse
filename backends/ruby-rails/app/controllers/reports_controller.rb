class ReportsController < ApplicationController
  def create
    caller = require_caller!
    bookmark_id = Stackverse::InputValidation.parse_uuid(params[:bookmark_id])
    input = Stackverse::InputValidation.validate_report(body_hash)
    row = nil
    ActiveRecord::Base.transaction do
      bookmark = Stackverse::Sql.one("select visibility, status from bookmarks where id = #{Stackverse::Sql.quote(bookmark_id)}::uuid for update")
      Stackverse::Errors.not_found unless bookmark && bookmark["visibility"] == "public" && bookmark["status"] == "active"
      duplicate_open_report!(bookmark_id, caller.username) if open_report?(bookmark_id, caller.username)
      row = Stackverse::Sql.one(<<~SQL.squish)
        insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at)
        values (
          #{Stackverse::Sql.quote(SecureRandom.uuid)},
          #{Stackverse::Sql.quote(bookmark_id)}::uuid,
          #{Stackverse::Sql.quote(caller.username)},
          #{Stackverse::Sql.quote(input[:reason])},
          #{Stackverse::Sql.quote(input[:comment])},
          'open',
          #{Stackverse::Sql.quote(Stackverse::Clock.now)}
        )
        returning *
      SQL
    end
    Stackverse::EventLog.info(
      "report_created",
      "success",
      "Report created on a public bookmark",
      actor: caller.username,
      resource_type: "report",
      resource_id: row["id"].to_s,
      bookmark_id: bookmark_id,
      reason: row["reason"]
    )
    render_json Stackverse::Serializers.report(row), status: 201
  rescue ActiveRecord::RecordNotUnique
    duplicate_open_report!(bookmark_id, caller.username)
  end

  def index
    caller = require_caller!
    page, size = query_params.page_and_size
    status = Stackverse::InputValidation.validate_report_status(query_params.single("status"))
    conditions = [ "reporter = #{Stackverse::Sql.quote(caller.username)}" ]
    conditions << "status = #{Stackverse::Sql.quote(status)}" if status
    where_sql = conditions.join(" and ")
    rows = Stackverse::Sql.query(<<~SQL.squish)
      select * from reports where #{where_sql}
      order by created_at desc, id desc
      limit #{size} offset #{page * size}
    SQL
    total = Stackverse::Sql.one("select count(*)::int as count from reports where #{where_sql}")["count"].to_i
    render_json Stackverse::Sql.page(rows, page, size, total, Stackverse::Serializers.method(:report))
  end

  def update
    caller = require_caller!
    report_id = Stackverse::InputValidation.parse_uuid(params[:id])
    input = Stackverse::InputValidation.validate_report(body_hash)
    row = nil
    report = nil
    ActiveRecord::Base.transaction do
      report = own_report!(report_id, caller.username)
      require_open!(report)
      row = Stackverse::Sql.one(<<~SQL.squish)
        update reports
        set reason = #{Stackverse::Sql.quote(input[:reason])},
            comment = #{Stackverse::Sql.quote(input[:comment])}
        where id = #{Stackverse::Sql.quote(report_id)}::uuid
        returning *
      SQL
    end
    Stackverse::EventLog.info(
      "report_updated",
      "success",
      "Report updated by its reporter",
      actor: caller.username,
      resource_type: "report",
      resource_id: report_id,
      bookmark_id: report["bookmark_id"].to_s,
      reason: input[:reason]
    )
    render_json Stackverse::Serializers.report(row)
  end

  def destroy
    caller = require_caller!
    report_id = Stackverse::InputValidation.parse_uuid(params[:id])
    report = nil
    ActiveRecord::Base.transaction do
      report = own_report!(report_id, caller.username)
      require_open!(report)
      Stackverse::Sql.connection.execute("delete from reports where id = #{Stackverse::Sql.quote(report_id)}::uuid")
    end
    Stackverse::EventLog.info(
      "report_withdrawn",
      "success",
      "Report withdrawn by its reporter",
      actor: caller.username,
      resource_type: "report",
      resource_id: report_id,
      bookmark_id: report["bookmark_id"].to_s
    )
    head :no_content
  end

  private

  def own_report!(report_id, reporter)
    report = Stackverse::Sql.one("select * from reports where id = #{Stackverse::Sql.quote(report_id)}::uuid for update")
    Stackverse::Errors.not_found unless report && report["reporter"] == reporter
    report
  end

  def require_open!(report)
    Stackverse::Errors.conflict("The report has already been resolved.") unless report["status"] == "open"
  end

  def open_report?(bookmark_id, reporter)
    Stackverse::Sql.one(<<~SQL.squish)
      select 1 from reports
      where bookmark_id = #{Stackverse::Sql.quote(bookmark_id)}::uuid
        and reporter = #{Stackverse::Sql.quote(reporter)}
        and status = 'open'
    SQL
  end

  def duplicate_open_report!(_bookmark_id, _reporter)
    Stackverse::Errors.conflict("You already have an open report on this bookmark.")
  end
end
