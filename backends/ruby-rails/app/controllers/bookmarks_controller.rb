class BookmarksController < ApplicationController
  V1_DEPRECATION_HEADERS = {
    "Deprecation" => "@1782864000",
    "Sunset" => "Thu, 01 Jul 2027 00:00:00 GMT",
    "Link" => '</api/v2/bookmarks>; rel="successor-version"'
  }.freeze

  def index_v1
    page, size = query_params.page_and_size
    where_sql, params_sql = listing_where
    rows = Stackverse::Sql.query(<<~SQL.squish)
      select * from bookmarks where #{where_sql}
      order by created_at desc, id desc
      limit #{size} offset #{page * size}
    SQL
    total = Stackverse::Sql.one("select count(*)::int as count from bookmarks where #{where_sql}")["count"].to_i
    render_json(
      Stackverse::Sql.page(rows, page, size, total, Stackverse::Serializers.method(:bookmark)),
      headers: V1_DEPRECATION_HEADERS
    )
  end

  def index_v2
    _page, size = query_params.page_and_size
    raw_cursor = query_params.single("cursor")
    cursor = raw_cursor ? Stackverse::Cursor.decode(raw_cursor) : nil
    where_sql, = listing_where
    cursor_sql = ""
    if cursor
      cursor_sql = <<~SQL.squish
        and (created_at < #{Stackverse::Sql.quote(cursor.created_at)}
          or (created_at = #{Stackverse::Sql.quote(cursor.created_at)}
          and id < #{Stackverse::Sql.quote(cursor.id)}::uuid))
      SQL
    end
    rows = Stackverse::Sql.query(<<~SQL.squish)
      select * from bookmarks where #{where_sql} #{cursor_sql}
      order by created_at desc, id desc
      limit #{size + 1}
    SQL
    items = rows.first(size)
    payload = { items: items.map { |row| Stackverse::Serializers.bookmark(row) } }
    if rows.length > size && items.any?
      last = items.last
      payload[:nextCursor] = Stackverse::Cursor.encode(Stackverse::BookmarkCursor.new(last["created_at"], last["id"].to_s))
    end
    render_json payload
  end

  def create
    caller = require_caller!
    input = Stackverse::InputValidation.validate_bookmark(body_hash)
    now = Stackverse::Clock.now
    row = Stackverse::Sql.one(<<~SQL.squish)
      insert into bookmarks (id, owner, url, title, notes, tags, visibility, status, created_at, updated_at)
      values (
        #{Stackverse::Sql.quote(SecureRandom.uuid)},
        #{Stackverse::Sql.quote(caller.username)},
        #{Stackverse::Sql.quote(input[:url])},
        #{Stackverse::Sql.quote(input[:title])},
        #{Stackverse::Sql.quote(input[:notes])},
        #{Stackverse::Sql.array(input[:tags])},
        #{Stackverse::Sql.quote(input[:visibility])},
        'active',
        #{Stackverse::Sql.quote(now)},
        #{Stackverse::Sql.quote(now)}
      )
      returning *
    SQL
    response.headers["Location"] = "/api/v1/bookmarks/#{row["id"]}"
    render_json Stackverse::Serializers.bookmark(row), status: 201
  end

  def show
    row = find_bookmark(Stackverse::InputValidation.parse_uuid(params[:id]))
    username = current_caller&.username
    Stackverse::Errors.not_found unless row && visible_to?(row, username)
    render_json Stackverse::Serializers.bookmark(row)
  end

  def update
    caller = require_caller!
    bookmark_id = Stackverse::InputValidation.parse_uuid(params[:id])
    input = Stackverse::InputValidation.validate_bookmark(body_hash)
    row = nil
    ActiveRecord::Base.transaction do
      existing = Stackverse::Sql.one("select * from bookmarks where id = #{Stackverse::Sql.quote(bookmark_id)}::uuid for update")
      Stackverse::Errors.not_found unless existing && existing["owner"] == caller.username
      if existing["status"] == "hidden" && input[:visibility] == "public"
        Stackverse::Errors.conflict(
          "This bookmark was hidden by moderation and cannot be made public.",
          detail_key: "error.bookmark.hidden-publish"
        )
      end
      row = Stackverse::Sql.one(<<~SQL.squish)
        update bookmarks
        set url = #{Stackverse::Sql.quote(input[:url])},
            title = #{Stackverse::Sql.quote(input[:title])},
            notes = #{Stackverse::Sql.quote(input[:notes])},
            tags = #{Stackverse::Sql.array(input[:tags])},
            visibility = #{Stackverse::Sql.quote(input[:visibility])},
            updated_at = #{Stackverse::Sql.quote(Stackverse::Clock.now)}
        where id = #{Stackverse::Sql.quote(bookmark_id)}::uuid
        returning *
      SQL
    end
    render_json Stackverse::Serializers.bookmark(row)
  end

  def destroy
    caller = require_caller!
    bookmark_id = Stackverse::InputValidation.parse_uuid(params[:id])
    existing = find_bookmark(bookmark_id)
    Stackverse::Errors.not_found unless existing && existing["owner"] == caller.username
    Stackverse::Sql.connection.execute("delete from bookmarks where id = #{Stackverse::Sql.quote(bookmark_id)}::uuid")
    head :no_content
  end

  def tags
    caller = require_caller!
    rows = Stackverse::Sql.query(<<~SQL.squish)
      select tag, count(*)::int as count
      from bookmarks, unnest(tags) as tag
      where owner = #{Stackverse::Sql.quote(caller.username)}
      group by tag
      order by count desc, tag asc
    SQL
    render_json({ tags: rows })
  end

  private

  def listing_where
    q = query_params.single("q")
    Stackverse::InputValidation.max_length(q, 200, "q")
    visibility = query_params.single("visibility")
    Stackverse::Errors.bad_request("unknown visibility: #{visibility}") if visibility && !Stackverse::InputValidation::VISIBILITIES.include?(visibility)
    tags = Stackverse::InputValidation.validate_query_tags(query_params.multi("tag"))

    conditions = []
    if visibility == "public"
      conditions << "visibility = 'public' and status = 'active'"
    else
      caller = require_caller!
      conditions << "owner = #{Stackverse::Sql.quote(caller.username)}"
      conditions << "visibility = #{Stackverse::Sql.quote(visibility)}" if visibility
    end
    conditions << "tags @> #{Stackverse::Sql.array(tags)}" if tags.any?
    if q.present?
      pattern = Stackverse::Sql.like_pattern(q)
      conditions << "(title ilike #{Stackverse::Sql.quote(pattern)} escape E'\\\\' or notes ilike #{Stackverse::Sql.quote(pattern)} escape E'\\\\')"
    end
    [ conditions.join(" and "), [] ]
  end

  def find_bookmark(bookmark_id)
    Stackverse::Sql.one("select * from bookmarks where id = #{Stackverse::Sql.quote(bookmark_id)}::uuid")
  end

  def visible_to?(bookmark, username)
    bookmark["owner"] == username || (bookmark["visibility"] == "public" && bookmark["status"] == "active")
  end
end
