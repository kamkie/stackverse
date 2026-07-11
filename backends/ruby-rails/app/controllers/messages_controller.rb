class MessagesController < ApplicationController
  def index
    page, size = query_params.page_and_size
    key = query_params.single("key")
    language = query_params.single("language")
    q = query_params.single("q")
    Stackverse::InputValidation.max_length(q, 200, "q")
    conditions = [ "true" ]
    conditions << "key = #{Stackverse::Sql.quote(key)}" if key
    conditions << "language = #{Stackverse::Sql.quote(language)}" if language
    if q.present?
      pattern = Stackverse::Sql.like_pattern(q)
      conditions << "(key ilike #{Stackverse::Sql.quote(pattern)} escape E'\\\\' or text ilike #{Stackverse::Sql.quote(pattern)} escape E'\\\\')"
    end
    where_sql = conditions.join(" and ")
    rows = Stackverse::Sql.query(<<~SQL.squish)
      select * from messages where #{where_sql}
      order by key, language
      limit #{size} offset #{page * size}
    SQL
    total = Stackverse::Sql.one("select count(*)::int as count from messages where #{where_sql}")["count"].to_i
    render_etag Stackverse::Sql.page(rows, page, size, total, Stackverse::Serializers.method(:message))
  end

  def bundle
    language = Stackverse::MessageCatalog.resolve(query_params.first("lang"), request.headers["Accept-Language"])
    render_etag(
      { language: language, messages: Stackverse::MessageCatalog.bundle(language) },
      "Content-Language" => language
    )
  end

  def show
    message_id = Stackverse::InputValidation.parse_uuid(params[:id])
    row = Stackverse::Sql.one("select * from messages where id = #{Stackverse::Sql.quote(message_id)}::uuid")
    Stackverse::Errors.not_found unless row
    render_etag Stackverse::Serializers.message(row)
  end

  def create
    caller = require_role!("admin")
    input = Stackverse::InputValidation.validate_message(body_hash)
    row = nil
    ActiveRecord::Base.transaction do
      ensure_message_unique!(input[:key], input[:language])
      now = Stackverse::Clock.now
      row = Stackverse::Sql.one(<<~SQL.squish)
        insert into messages (id, key, language, text, description, created_at, updated_at)
        values (
          #{Stackverse::Sql.quote(SecureRandom.uuid)},
          #{Stackverse::Sql.quote(input[:key])},
          #{Stackverse::Sql.quote(input[:language])},
          #{Stackverse::Sql.quote(input[:text])},
          #{Stackverse::Sql.quote(input[:description])},
          #{Stackverse::Sql.quote(now)},
          #{Stackverse::Sql.quote(now)}
        )
        returning *
      SQL
      Stackverse::Audit.record(caller.username, "message.created", "message", row["id"].to_s, message_snapshot(row))
    end
    Stackverse::EventLog.info("message_created", "success", "Message created", message_event_fields(caller, row))
    response.headers["Location"] = "/api/v1/messages/#{row["id"]}"
    render_json Stackverse::Serializers.message(row), status: 201
  rescue ActiveRecord::RecordNotUnique
    duplicate_message!(input)
  end

  def update
    caller = require_role!("admin")
    message_id = Stackverse::InputValidation.parse_uuid(params[:id])
    input = Stackverse::InputValidation.validate_message(body_hash)
    row = nil
    ActiveRecord::Base.transaction do
      Stackverse::Errors.not_found unless Stackverse::Sql.one("select 1 from messages where id = #{Stackverse::Sql.quote(message_id)}::uuid")
      ensure_message_unique!(input[:key], input[:language], except_id: message_id)
      row = Stackverse::Sql.one(<<~SQL.squish)
        update messages
        set key = #{Stackverse::Sql.quote(input[:key])},
            language = #{Stackverse::Sql.quote(input[:language])},
            text = #{Stackverse::Sql.quote(input[:text])},
            description = #{Stackverse::Sql.quote(input[:description])},
            updated_at = #{Stackverse::Sql.quote(Stackverse::Clock.now)}
        where id = #{Stackverse::Sql.quote(message_id)}::uuid
        returning *
      SQL
      Stackverse::Audit.record(caller.username, "message.updated", "message", row["id"].to_s, message_snapshot(row))
    end
    Stackverse::EventLog.info("message_updated", "success", "Message updated", message_event_fields(caller, row))
    render_json Stackverse::Serializers.message(row)
  rescue ActiveRecord::RecordNotUnique
    duplicate_message!(input)
  end

  def destroy
    caller = require_role!("admin")
    message_id = Stackverse::InputValidation.parse_uuid(params[:id])
    row = nil
    ActiveRecord::Base.transaction do
      row = Stackverse::Sql.one("delete from messages where id = #{Stackverse::Sql.quote(message_id)}::uuid returning *")
      Stackverse::Errors.not_found unless row
      Stackverse::Audit.record(caller.username, "message.deleted", "message", row["id"].to_s, message_snapshot(row))
    end
    Stackverse::EventLog.info("message_deleted", "success", "Message deleted", message_event_fields(caller, row))
    head :no_content
  end

  private

  def ensure_message_unique!(key, language, except_id: nil)
    clause = "key = #{Stackverse::Sql.quote(key)} and language = #{Stackverse::Sql.quote(language)}"
    clause += " and id <> #{Stackverse::Sql.quote(except_id)}::uuid" if except_id
    duplicate = Stackverse::Sql.one("select 1 from messages where #{clause}")
    duplicate_message!(key: key, language: language) if duplicate
  end

  def duplicate_message!(input)
    Stackverse::Errors.conflict("A message with key '#{input[:key]}' and language '#{input[:language]}' already exists.")
  end

  def message_snapshot(row)
    { key: row["key"], language: row["language"], text: row["text"], description: row["description"] }
  end

  def message_event_fields(caller, row)
    {
      actor: caller.username,
      resource_type: "message",
      resource_id: row["id"].to_s,
      message_key: row["key"],
      language: row["language"]
    }
  end
end
