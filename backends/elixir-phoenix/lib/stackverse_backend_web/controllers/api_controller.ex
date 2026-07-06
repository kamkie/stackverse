defmodule StackverseBackendWeb.ApiController do
  use Phoenix.Controller, formats: [:json]

  alias StackverseBackend.{Auth, Cursor, I18n, Log, Problem, Repo, Validation}

  @bookmark_select "id::text as id, owner, url, title, notes, tags, visibility, status, created_at, updated_at"
  @message_select "id::text as id, key, language, text, description, created_at, updated_at"
  @report_select "id::text as id, bookmark_id::text as bookmark_id, reporter, reason, comment, status, resolved_by, resolved_at, resolution_note, created_at"
  @v1_deprecation "@1782864000"
  @v1_sunset "Thu, 01 Jul 2027 00:00:00 GMT"
  @v1_successor "</api/v2/bookmarks>; rel=\"successor-version\""

  def healthz(conn, _params), do: send_resp(conn, 200, "")

  def readyz(conn, _params) do
    case Repo.query("select 1", []) do
      {:ok, _} -> send_resp(conn, 200, "")
      {:error, _} -> send_resp(conn, 503, "")
    end
  end

  def not_found(conn, _params), do: Problem.send(conn, 404, "Not Found")

  def me(conn, _params) do
    with {:ok, caller} <- require_caller(conn) do
      caller
      |> Map.take([:username, :name, :email])
      |> Map.put(:roles, Auth.app_roles(caller.roles))
      |> then(&json(conn, Problem.omit_nil(&1)))
    else
      {:error, conn} -> conn
    end
  end

  def list_bookmarks_v1(conn, _params) do
    conn =
      conn
      |> put_resp_header("deprecation", @v1_deprecation)
      |> put_resp_header("sunset", @v1_sunset)
      |> put_resp_header("link", @v1_successor)

    with {:ok, %{page: page, size: size}} <- paging(conn),
         {:ok, filters} <- bookmark_filters(conn) do
      {where, binds} = bookmark_where(filters)
      total = scalar!("select count(*)::int from bookmarks where #{where}", binds)

      items =
        all!(
          """
          select #{@bookmark_select} from bookmarks
          where #{where}
          order by created_at desc, id desc
          limit $#{length(binds) + 1} offset $#{length(binds) + 2}
          """,
          binds ++ [size, page * size]
        )
        |> Enum.map(&bookmark_response/1)

      json(conn, page_response(items, page, size, total))
    else
      {:error, conn} -> conn
    end
  end

  def list_bookmarks_v2(conn, _params) do
    with {:ok, %{size: size}} <- paging(conn),
         {:ok, filters} <- bookmark_filters(conn),
         {:ok, cursor} <- cursor_param(conn) do
      {where, binds} = bookmark_where(filters)
      {cursor_sql, binds} = append_cursor(where, binds, cursor)

      rows =
        all!(
          """
          select #{@bookmark_select} from bookmarks
          where #{cursor_sql}
          order by created_at desc, id desc
          limit $#{length(binds) + 1}
          """,
          binds ++ [size + 1]
        )

      items = Enum.take(rows, size)

      body =
        %{
          items: Enum.map(items, &bookmark_response/1),
          nextCursor:
            if(length(rows) > size,
              do:
                items
                |> List.last()
                |> then(&Cursor.encode(%{created_at: &1["created_at"], id: &1["id"]})),
              else: nil
            )
        }
        |> Problem.omit_nil()

      json(conn, body)
    else
      {:error, conn} -> conn
    end
  end

  def create_bookmark(conn, _params) do
    with {:ok, caller} <- require_caller(conn),
         {:ok, input} <- validate(conn, Validation.validate_bookmark(conn.body_params)) do
      id = Ecto.UUID.generate()
      now = now()

      bookmark =
        one!(
          """
          insert into bookmarks (id, owner, url, title, notes, tags, visibility, status, created_at, updated_at)
          values ($1::text::uuid, $2, $3, $4, $5, $6::text[], $7, 'active', $8, $8)
          returning #{@bookmark_select}
          """,
          [
            id,
            caller.username,
            input.url,
            input.title,
            input.notes,
            input.tags,
            input.visibility,
            now
          ]
        )

      conn
      |> put_status(201)
      |> put_resp_header("location", "/api/v1/bookmarks/#{id}")
      |> json(bookmark_response(bookmark))
    else
      {:error, conn} -> conn
    end
  end

  def get_bookmark(conn, %{"id" => raw_id}) do
    with {:ok, id} <- path_uuid(conn, raw_id) do
      caller = conn.assigns[:caller]
      bookmark = one("select #{@bookmark_select} from bookmarks where id = $1::text::uuid", [id])

      if bookmark && bookmark_visible_to?(bookmark, caller && caller.username) do
        json(conn, bookmark_response(bookmark))
      else
        Problem.send(conn, 404, "Not Found")
      end
    else
      {:error, conn} -> conn
    end
  end

  def update_bookmark(conn, %{"id" => raw_id}) do
    with {:ok, caller} <- require_caller(conn),
         {:ok, id} <- path_uuid(conn, raw_id),
         {:ok, input} <- validate(conn, Validation.validate_bookmark(conn.body_params)) do
      case Repo.transaction(fn ->
             bookmark =
               one!(
                 "select #{@bookmark_select} from bookmarks where id = $1::text::uuid for update",
                 [
                   id
                 ]
               )

             cond do
               is_nil(bookmark) or bookmark["owner"] != caller.username ->
                 Repo.rollback(:not_found)

               bookmark["status"] == "hidden" and input.visibility == "public" ->
                 Repo.rollback(
                   {:conflict,
                    "This bookmark was hidden by moderation and cannot be made public."}
                 )

               true ->
                 one!(
                   """
                   update bookmarks
                   set url = $2, title = $3, notes = $4, tags = $5::text[], visibility = $6, updated_at = $7
                   where id = $1::text::uuid
                   returning #{@bookmark_select}
                   """,
                   [id, input.url, input.title, input.notes, input.tags, input.visibility, now()]
                 )
             end
           end) do
        {:ok, bookmark} -> json(conn, bookmark_response(bookmark))
        {:error, :not_found} -> Problem.send(conn, 404, "Not Found")
        {:error, {:conflict, detail}} -> Problem.send(conn, 409, "Conflict", detail)
      end
    else
      {:error, conn} -> conn
    end
  end

  def delete_bookmark(conn, %{"id" => raw_id}) do
    with {:ok, caller} <- require_caller(conn),
         {:ok, id} <- path_uuid(conn, raw_id) do
      case one(
             "delete from bookmarks where id = $1::text::uuid and owner = $2 returning id::text as id",
             [id, caller.username]
           ) do
        nil -> Problem.send(conn, 404, "Not Found")
        _ -> send_resp(conn, 204, "")
      end
    else
      {:error, conn} -> conn
    end
  end

  def list_tags(conn, _params) do
    with {:ok, caller} <- require_caller(conn) do
      tags =
        all!(
          """
          select tag, count(*)::int as count
          from bookmarks, unnest(tags) as tag
          where owner = $1
          group by tag
          order by count desc, tag asc
          """,
          [caller.username]
        )

      json(conn, %{tags: Enum.map(tags, &%{tag: &1["tag"], count: &1["count"]})})
    else
      {:error, conn} -> conn
    end
  end

  def report_bookmark(conn, %{"id" => raw_id}) do
    with {:ok, caller} <- require_caller(conn),
         {:ok, bookmark_id} <- path_uuid(conn, raw_id),
         {:ok, input} <- validate(conn, Validation.validate_report(conn.body_params)) do
      case Repo.transaction(fn ->
             bookmark =
               one!(
                 "select visibility, status from bookmarks where id = $1::text::uuid for update",
                 [bookmark_id]
               )

             if is_nil(bookmark) or bookmark["visibility"] != "public" or
                  bookmark["status"] != "active" do
               Repo.rollback(:not_found)
             end

             if one!(
                  "select 1 as exists from reports where bookmark_id = $1::text::uuid and reporter = $2 and status = 'open'",
                  [bookmark_id, caller.username]
                ) do
               Repo.rollback(:duplicate_report)
             end

             id = Ecto.UUID.generate()

             case Repo.query(
                    """
                    insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at)
                    values ($1::text::uuid, $2::text::uuid, $3, $4, $5, 'open', $6)
                    returning #{@report_select}
                    """,
                    [id, bookmark_id, caller.username, input.reason, input.comment, now()]
                  ) do
               {:ok, result} ->
                 result |> rows() |> List.first()

               {:error, %Postgrex.Error{postgres: %{code: :unique_violation}}} ->
                 Repo.rollback(:duplicate_report)

               {:error, error} ->
                 raise error
             end
           end) do
        {:ok, report} ->
          Log.event(:info, "report_created", "success", "Report created on a public bookmark",
            actor: caller.username,
            resource_type: "report",
            resource_id: report["id"],
            bookmark_id: bookmark_id,
            reason: report["reason"]
          )

          conn |> put_status(201) |> json(report_response(report))

        {:error, :not_found} ->
          Problem.send(conn, 404, "Not Found")

        {:error, :duplicate_report} ->
          Problem.send(conn, 409, "Conflict", "You already have an open report on this bookmark.")
      end
    else
      {:error, conn} -> conn
    end
  end

  def list_my_reports(conn, _params) do
    with {:ok, caller} <- require_caller(conn),
         {:ok, %{page: page, size: size}} <- paging(conn),
         {:ok, status} <- optional_enum(conn, "status", ~w[open dismissed actioned]) do
      {status_sql, binds} =
        if status do
          {" and status = $2", [caller.username, status]}
        else
          {"", [caller.username]}
        end

      total = scalar!("select count(*)::int from reports where reporter = $1#{status_sql}", binds)

      items =
        all!(
          """
          select #{@report_select} from reports
          where reporter = $1#{status_sql}
          order by created_at desc, id desc
          limit $#{length(binds) + 1} offset $#{length(binds) + 2}
          """,
          binds ++ [size, page * size]
        )
        |> Enum.map(&report_response/1)

      json(conn, page_response(items, page, size, total))
    else
      {:error, conn} -> conn
    end
  end

  def update_my_report(conn, %{"id" => raw_id}) do
    with {:ok, caller} <- require_caller(conn),
         {:ok, id} <- path_uuid(conn, raw_id) do
      case Repo.transaction(fn ->
             report =
               one!(
                 "select #{@report_select} from reports where id = $1::text::uuid for update",
                 [id]
               )

             if is_nil(report) or report["reporter"] != caller.username do
               Repo.rollback(:not_found)
             end

             with {:ok, input} <- Validation.validate_report(conn.body_params) do
               if report["status"] != "open" do
                 Repo.rollback(:not_open)
               end

               updated =
                 one!(
                   """
                   update reports set reason = $2, comment = $3
                   where id = $1::text::uuid
                   returning #{@report_select}
                   """,
                   [id, input.reason, input.comment]
                 )

               Log.event(:info, "report_updated", "success", "Report updated by its reporter",
                 actor: caller.username,
                 resource_type: "report",
                 resource_id: id,
                 bookmark_id: report["bookmark_id"],
                 reason: input.reason
               )

               updated
             else
               {:error, errors} -> Repo.rollback({:validation, errors})
             end
           end) do
        {:ok, report} ->
          json(conn, report_response(report))

        {:error, :not_found} ->
          Problem.send(conn, 404, "Not Found")

        {:error, :not_open} ->
          Problem.send(conn, 409, "Conflict", "The report has already been resolved.")

        {:error, {:validation, errors}} ->
          Problem.validation(conn, errors)
      end
    else
      {:error, conn} -> conn
    end
  end

  def withdraw_report(conn, %{"id" => raw_id}) do
    with {:ok, caller} <- require_caller(conn),
         {:ok, id} <- path_uuid(conn, raw_id) do
      case Repo.transaction(fn ->
             report =
               one!(
                 "select #{@report_select} from reports where id = $1::text::uuid for update",
                 [id]
               )

             cond do
               is_nil(report) or report["reporter"] != caller.username ->
                 Repo.rollback(:not_found)

               report["status"] != "open" ->
                 Repo.rollback(:not_open)

               true ->
                 Repo.query!("delete from reports where id = $1::text::uuid", [id])

                 Log.event(
                   :info,
                   "report_withdrawn",
                   "success",
                   "Report withdrawn by its reporter",
                   actor: caller.username,
                   resource_type: "report",
                   resource_id: id,
                   bookmark_id: report["bookmark_id"]
                 )
             end
           end) do
        {:ok, _} ->
          send_resp(conn, 204, "")

        {:error, :not_found} ->
          Problem.send(conn, 404, "Not Found")

        {:error, :not_open} ->
          Problem.send(conn, 409, "Conflict", "The report has already been resolved.")
      end
    else
      {:error, conn} -> conn
    end
  end

  def list_report_queue(conn, _params) do
    with {:ok, _caller} <- require_role(conn, "moderator"),
         {:ok, %{page: page, size: size}} <- paging(conn),
         {:ok, status} <- optional_enum(conn, "status", ~w[open dismissed actioned]) do
      status = status || "open"
      total = scalar!("select count(*)::int from reports where status = $1", [status])

      items =
        all!(
          """
          select #{@report_select} from reports
          where status = $1
          order by created_at asc, id asc
          limit $2 offset $3
          """,
          [status, size, page * size]
        )
        |> Enum.map(&report_response/1)

      json(conn, page_response(items, page, size, total))
    else
      {:error, conn} -> conn
    end
  end

  def resolve_report(conn, %{"id" => raw_id}) do
    with {:ok, caller} <- require_role(conn, "moderator"),
         {:ok, id} <- path_uuid(conn, raw_id),
         {:ok, input} <- validate(conn, Validation.validate_report_resolution(conn.body_params)) do
      case Repo.transaction(fn ->
             if input.resolution == "actioned" do
               case one!(
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
               one!(
                 "select #{@report_select} from reports where id = $1::text::uuid for update",
                 [id]
               )

             if is_nil(report), do: Repo.rollback(:not_found)

             if input.resolution == "open" do
               reopen_report(report, caller.username)
             else
               resolved =
                 resolve_one(report, input.resolution, caller.username, input.note, false)

               if input.resolution == "actioned" do
                 hide_bookmark(caller.username, report["bookmark_id"], input.note)

                 all!(
                   """
                   select #{@report_select} from reports
                   where bookmark_id = $1::text::uuid and status = 'open' and id <> $2::text::uuid
                   order by id asc
                   for update
                   """,
                   [report["bookmark_id"], id]
                 )
                 |> Enum.each(&resolve_one(&1, "actioned", caller.username, input.note, true))
               end

               resolved
             end
           end) do
        {:ok, report} ->
          json(conn, report_response(report))

        {:error, :not_found} ->
          Problem.send(conn, 404, "Not Found")

        {:error, :duplicate_report} ->
          Problem.send(
            conn,
            409,
            "Conflict",
            "The reporter already has another open report on this bookmark."
          )
      end
    else
      {:error, conn} -> conn
    end
  end

  def set_bookmark_status(conn, %{"id" => raw_id}) do
    with {:ok, caller} <- require_role(conn, "moderator"),
         {:ok, id} <- path_uuid(conn, raw_id),
         {:ok, input} <- validate(conn, Validation.validate_bookmark_status(conn.body_params)) do
      case Repo.transaction(fn ->
             bookmark =
               one!(
                 "select #{@bookmark_select} from bookmarks where id = $1::text::uuid for update",
                 [
                   id
                 ]
               )

             if is_nil(bookmark), do: Repo.rollback(:not_found)

             updated =
               one!(
                 """
                 update bookmarks set status = $2, updated_at = $3
                 where id = $1::text::uuid
                 returning #{@bookmark_select}
                 """,
                 [id, input.status, now()]
               )

             audit!(caller.username, "bookmark.status-changed", "bookmark", id, %{
               from: bookmark["status"],
               to: input.status,
               note: input.note
             })

             Log.event(
               :info,
               "bookmark_status_changed",
               "success",
               "Bookmark moderation status changed",
               actor: caller.username,
               resource_type: "bookmark",
               resource_id: id,
               from: bookmark["status"],
               to: input.status
             )

             updated
           end) do
        {:ok, bookmark} -> json(conn, bookmark_response(bookmark))
        {:error, :not_found} -> Problem.send(conn, 404, "Not Found")
      end
    else
      {:error, conn} -> conn
    end
  end

  def list_users(conn, _params) do
    with {:ok, _caller} <- require_role(conn, "admin"),
         {:ok, %{page: page, size: size}} <- paging(conn),
         {:ok, q} <- optional_single(conn, "q"),
         :ok <- max_length(conn, q, 100, "q"),
         {:ok, status} <- optional_enum(conn, "status", ~w[active blocked]) do
      {where, binds} = user_where(q, status)
      total = scalar!("select count(*)::int from user_accounts u where #{where}", binds)

      items =
        all!(
          """
          select u.username, u.first_seen, u.last_seen, u.status, u.blocked_reason,
                 (select count(*)::int from bookmarks b where b.owner = u.username) as bookmark_count
          from user_accounts u
          where #{where}
          order by u.last_seen desc, u.username asc
          limit $#{length(binds) + 1} offset $#{length(binds) + 2}
          """,
          binds ++ [size, page * size]
        )
        |> Enum.map(&user_response/1)

      json(conn, page_response(items, page, size, total))
    else
      {:error, conn} -> conn
    end
  end

  def get_user(conn, %{"username" => username}) do
    with {:ok, _caller} <- require_role(conn, "admin") do
      case user_account(username) do
        nil -> Problem.send(conn, 404, "Not Found")
        account -> json(conn, user_response(account))
      end
    else
      {:error, conn} -> conn
    end
  end

  def set_user_status(conn, %{"username" => username}) do
    with {:ok, caller} <- require_role(conn, "admin"),
         {:ok, status} <- required_status(conn, conn.body_params),
         reason <-
           optional_body_string(conn.body_params, "reason")
           |> then(&if(is_binary(&1), do: String.trim(&1), else: nil)),
         {:ok, :ok} <- validate(conn, Validation.validate_block_reason(status, reason)) do
      cond do
        status == "blocked" and username == caller.username ->
          Problem.send(conn, 409, "Conflict", "Admins cannot block themselves.")

        true ->
          case Repo.transaction(fn ->
                 if is_nil(
                      one!("select username from user_accounts where username = $1 for update", [
                        username
                      ])
                    ) do
                   Repo.rollback(:not_found)
                 end

                 if status == "blocked" do
                   Repo.query!(
                     "update user_accounts set status = 'blocked', blocked_reason = $2 where username = $1",
                     [
                       username,
                       reason
                     ]
                   )

                   audit!(caller.username, "user.blocked", "user", username, %{reason: reason})
                 else
                   Repo.query!(
                     "update user_accounts set status = 'active', blocked_reason = null where username = $1",
                     [username]
                   )

                   audit!(caller.username, "user.unblocked", "user", username, nil)
                 end
               end) do
            {:ok, _} ->
              Log.event(
                :info,
                if(status == "blocked", do: "user_blocked", else: "user_unblocked"),
                "success",
                "User account status changed",
                actor: caller.username,
                resource_type: "user",
                resource_id: username
              )

              json(conn, user_response(user_account(username)))

            {:error, :not_found} ->
              Problem.send(conn, 404, "Not Found")
          end
      end
    else
      {:error, conn} -> conn
    end
  end

  def list_audit_log(conn, _params) do
    with {:ok, _caller} <- require_role(conn, "admin"),
         {:ok, %{page: page, size: size}} <- paging(conn),
         {:ok, filters} <- audit_filters(conn) do
      {where, binds} = audit_where(filters)
      total = scalar!("select count(*)::int from audit_entries where #{where}", binds)

      items =
        all!(
          """
          select id::text as id, actor, action, target_type, target_id, detail, created_at
          from audit_entries
          where #{where}
          order by created_at desc, id desc
          limit $#{length(binds) + 1} offset $#{length(binds) + 2}
          """,
          binds ++ [size, page * size]
        )
        |> Enum.map(&audit_response/1)

      json(conn, page_response(items, page, size, total))
    else
      {:error, conn} -> conn
    end
  end

  def get_stats(conn, _params) do
    with {:ok, _caller} <- require_role(conn, "moderator") do
      today = Date.utc_today()
      from_date = Date.add(today, -29)
      from = DateTime.new!(from_date, ~T[00:00:00], "Etc/UTC")

      totals = %{
        users: scalar!("select count(*)::int from user_accounts", []),
        bookmarks: scalar!("select count(*)::int from bookmarks", []),
        publicBookmarks:
          scalar!("select count(*)::int from bookmarks where visibility = 'public'", []),
        hiddenBookmarks:
          scalar!("select count(*)::int from bookmarks where status = 'hidden'", []),
        openReports: scalar!("select count(*)::int from reports where status = 'open'", [])
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
        all!(
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

      etag_json(conn, 200, %{totals: totals, daily: daily, topTags: top_tags})
    else
      {:error, conn} -> conn
    end
  end

  def list_messages(conn, _params) do
    with {:ok, %{page: page, size: size}} <- paging(conn),
         {:ok, key} <- optional_single(conn, "key"),
         {:ok, language} <- optional_single(conn, "language"),
         {:ok, q} <- optional_single(conn, "q"),
         :ok <- max_length(conn, q, 200, "q") do
      {where, binds} = message_where(key, language, q)
      total = scalar!("select count(*)::int from messages where #{where}", binds)

      items =
        all!(
          """
          select #{@message_select} from messages
          where #{where}
          order by key, language
          limit $#{length(binds) + 1} offset $#{length(binds) + 2}
          """,
          binds ++ [size, page * size]
        )
        |> Enum.map(&message_response/1)

      etag_json(conn, 200, page_response(items, page, size, total))
    else
      {:error, conn} -> conn
    end
  end

  def message_bundle(conn, _params) do
    language =
      conn.query_string
      |> Problem.query_values("lang")
      |> List.first()
      |> I18n.resolve_language(conn |> get_req_header("accept-language") |> List.first())

    conn
    |> put_resp_header("content-language", language)
    |> etag_json(200, %{language: language, messages: I18n.bundle(language)})
  end

  def get_message(conn, %{"id" => raw_id}) do
    with {:ok, id} <- path_uuid(conn, raw_id) do
      case one("select #{@message_select} from messages where id = $1::text::uuid", [id]) do
        nil -> Problem.send(conn, 404, "Not Found")
        message -> etag_json(conn, 200, message_response(message))
      end
    else
      {:error, conn} -> conn
    end
  end

  def create_message(conn, _params) do
    with {:ok, caller} <- require_role(conn, "admin"),
         {:ok, input} <- validate(conn, Validation.validate_message(conn.body_params)) do
      case Repo.transaction(fn ->
             if one!("select 1 as exists from messages where key = $1 and language = $2", [
                  input.key,
                  input.language
                ]) do
               Repo.rollback(:duplicate_message)
             end

             id = Ecto.UUID.generate()

             case Repo.query(
                    """
                    insert into messages (id, key, language, text, description, created_at, updated_at)
                    values ($1::text::uuid, $2, $3, $4, $5, $6, $6)
                    returning #{@message_select}
                    """,
                    [id, input.key, input.language, input.text, input.description, now()]
                  ) do
               {:ok, result} ->
                 message = result |> rows() |> List.first()

                 audit!(
                   caller.username,
                   "message.created",
                   "message",
                   message["id"],
                   message_snapshot(message)
                 )

                 message

               {:error, %Postgrex.Error{postgres: %{code: :unique_violation}}} ->
                 Repo.rollback(:duplicate_message)

               {:error, error} ->
                 raise error
             end
           end) do
        {:ok, message} ->
          Log.event(:info, "message_created", "success", "Message created",
            actor: caller.username,
            resource_type: "message",
            resource_id: message["id"],
            message_key: message["key"],
            language: message["language"]
          )

          conn
          |> put_status(201)
          |> put_resp_header("location", "/api/v1/messages/#{message["id"]}")
          |> json(message_response(message))

        {:error, :duplicate_message} ->
          Problem.send(
            conn,
            409,
            "Conflict",
            "A message with this key and language already exists."
          )
      end
    else
      {:error, conn} -> conn
    end
  end

  def update_message(conn, %{"id" => raw_id}) do
    with {:ok, caller} <- require_role(conn, "admin"),
         {:ok, id} <- path_uuid(conn, raw_id),
         {:ok, input} <- validate(conn, Validation.validate_message(conn.body_params)) do
      case Repo.transaction(fn ->
             if is_nil(one!("select 1 as exists from messages where id = $1::text::uuid", [id])) do
               Repo.rollback(:not_found)
             end

             if one!(
                  "select 1 as exists from messages where key = $1 and language = $2 and id <> $3::text::uuid",
                  [input.key, input.language, id]
                ) do
               Repo.rollback(:duplicate_message)
             end

             case Repo.query(
                    """
                    update messages
                    set key = $2, language = $3, text = $4, description = $5, updated_at = $6
                    where id = $1::text::uuid
                    returning #{@message_select}
                    """,
                    [id, input.key, input.language, input.text, input.description, now()]
                  ) do
               {:ok, result} ->
                 message = result |> rows() |> List.first()

                 audit!(
                   caller.username,
                   "message.updated",
                   "message",
                   message["id"],
                   message_snapshot(message)
                 )

                 message

               {:error, %Postgrex.Error{postgres: %{code: :unique_violation}}} ->
                 Repo.rollback(:duplicate_message)

               {:error, error} ->
                 raise error
             end
           end) do
        {:ok, message} ->
          Log.event(:info, "message_updated", "success", "Message updated",
            actor: caller.username,
            resource_type: "message",
            resource_id: message["id"],
            message_key: message["key"],
            language: message["language"]
          )

          json(conn, message_response(message))

        {:error, :not_found} ->
          Problem.send(conn, 404, "Not Found")

        {:error, :duplicate_message} ->
          Problem.send(
            conn,
            409,
            "Conflict",
            "A message with this key and language already exists."
          )
      end
    else
      {:error, conn} -> conn
    end
  end

  def delete_message(conn, %{"id" => raw_id}) do
    with {:ok, caller} <- require_role(conn, "admin"),
         {:ok, id} <- path_uuid(conn, raw_id) do
      case Repo.transaction(fn ->
             message =
               one!(
                 "delete from messages where id = $1::text::uuid returning #{@message_select}",
                 [id]
               )

             if is_nil(message), do: Repo.rollback(:not_found)

             audit!(
               caller.username,
               "message.deleted",
               "message",
               message["id"],
               message_snapshot(message)
             )

             message
           end) do
        {:ok, message} ->
          Log.event(:info, "message_deleted", "success", "Message deleted",
            actor: caller.username,
            resource_type: "message",
            resource_id: message["id"],
            message_key: message["key"],
            language: message["language"]
          )

          send_resp(conn, 204, "")

        {:error, :not_found} ->
          Problem.send(conn, 404, "Not Found")
      end
    else
      {:error, conn} -> conn
    end
  end

  defp bookmark_filters(conn) do
    with {:ok, q} <- optional_single(conn, "q"),
         :ok <- max_length(conn, q, 200, "q"),
         {:ok, visibility} <- optional_enum(conn, "visibility", ~w[private public]),
         {:ok, tags} <-
           validate(
             conn,
             Validation.validate_query_tags(Problem.query_values(conn.query_string, "tag"))
           ) do
      cond do
        visibility == "public" ->
          {:ok, %{caller: nil, visibility: visibility, tags: tags, q: q || ""}}

        conn.assigns[:caller] ->
          {:ok,
           %{caller: conn.assigns.caller.username, visibility: visibility, tags: tags, q: q || ""}}

        true ->
          {:error, Problem.send(conn, 401, "Unauthorized", "Authentication is required.")}
      end
    end
  end

  defp bookmark_where(filters) do
    {conditions, binds} =
      if filters.visibility == "public" do
        {["visibility = 'public' and status = 'active'"], []}
      else
        conditions = ["owner = $1"]
        binds = [filters.caller]

        if filters.visibility do
          {conditions ++ ["visibility = $2"], binds ++ [filters.visibility]}
        else
          {conditions, binds}
        end
      end

    {conditions, binds} =
      if filters.tags != [] do
        index = length(binds) + 1
        {conditions ++ ["tags @> $#{index}::text[]"], binds ++ [filters.tags]}
      else
        {conditions, binds}
      end

    {conditions, binds} =
      if String.trim(filters.q) != "" do
        index = length(binds) + 1
        pattern = "%#{escape_like(filters.q)}%"

        {conditions ++
           ["(title ilike $#{index} escape '\\' or notes ilike $#{index} escape '\\')"],
         binds ++ [pattern]}
      else
        {conditions, binds}
      end

    {Enum.join(conditions, " and "), binds}
  end

  defp append_cursor(where, binds, nil), do: {where, binds}

  defp append_cursor(where, binds, cursor) do
    created_index = length(binds) + 1
    id_index = length(binds) + 2

    {
      "#{where} and (created_at < $#{created_index}::timestamptz or (created_at = $#{created_index}::timestamptz and id < $#{id_index}::text::uuid))",
      binds ++ [cursor.created_at, cursor.id]
    }
  end

  defp cursor_param(conn) do
    case optional_single(conn, "cursor") do
      {:ok, nil} ->
        {:ok, nil}

      {:ok, raw} ->
        case Cursor.decode(raw) do
          {:ok, cursor} ->
            {:ok, cursor}

          :error ->
            {:error,
             Problem.send(conn, 400, "Bad Request", "The cursor is malformed or unresolvable.")}
        end

      {:error, conn} ->
        {:error, conn}
    end
  end

  defp reopen_report(report, actor) do
    if one!(
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
        reopened = result |> rows() |> List.first()

        audit!(actor, "report.reopened", "report", report["id"], %{
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
      one!(
        """
        update reports
        set status = $2, resolved_by = $3, resolved_at = $4, resolution_note = $5
        where id = $1::text::uuid
        returning #{@report_select}
        """,
        [report["id"], resolution, actor, now(), note]
      )

    audit!(actor, "report.resolved", "report", report["id"], %{
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
      one!("select #{@bookmark_select} from bookmarks where id = $1::text::uuid", [bookmark_id])

    if is_nil(bookmark), do: Repo.rollback(:not_found)

    if bookmark["status"] != "hidden" do
      Repo.query!(
        "update bookmarks set status = 'hidden', updated_at = $2 where id = $1::text::uuid",
        [
          bookmark_id,
          now()
        ]
      )

      audit!(actor, "bookmark.status-changed", "bookmark", bookmark_id, %{
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

  defp user_where(q, status) do
    conditions = ["true"]
    binds = []

    {conditions, binds} =
      if q && String.trim(q) != "" do
        {conditions ++ ["u.username ilike $1 escape '\\'"], ["%#{escape_like(q)}%"]}
      else
        {conditions, binds}
      end

    {conditions, binds} =
      if status do
        index = length(binds) + 1
        {conditions ++ ["u.status = $#{index}"], binds ++ [status]}
      else
        {conditions, binds}
      end

    {Enum.join(conditions, " and "), binds}
  end

  defp user_account(username) do
    one(
      """
      select u.username, u.first_seen, u.last_seen, u.status, u.blocked_reason,
             (select count(*)::int from bookmarks b where b.owner = u.username) as bookmark_count
      from user_accounts u
      where u.username = $1
      """,
      [username]
    )
  end

  defp audit_filters(conn) do
    with {:ok, actor} <- optional_single(conn, "actor"),
         {:ok, action} <- optional_single(conn, "action"),
         {:ok, target_type} <- optional_single(conn, "targetType"),
         {:ok, target_id} <- optional_single(conn, "targetId"),
         {:ok, from} <- datetime_param(conn, "from"),
         {:ok, to} <- datetime_param(conn, "to") do
      {:ok,
       %{
         actor: actor,
         action: action,
         target_type: target_type,
         target_id: target_id,
         from: from,
         to: to
       }}
    end
  end

  defp audit_where(filters) do
    [
      {:actor, "actor ="},
      {:action, "action ="},
      {:target_type, "target_type ="},
      {:target_id, "target_id ="},
      {:from, "created_at >="},
      {:to, "created_at <="}
    ]
    |> Enum.reduce({["true"], []}, fn {key, op}, {conditions, binds} ->
      case Map.fetch!(filters, key) do
        nil ->
          {conditions, binds}

        value ->
          index = length(binds) + 1
          cast = if key in [:from, :to], do: "::timestamptz", else: ""
          {conditions ++ ["#{op} $#{index}#{cast}"], binds ++ [value]}
      end
    end)
    |> then(fn {conditions, binds} -> {Enum.join(conditions, " and "), binds} end)
  end

  defp message_where(key, language, q) do
    conditions = ["true"]
    binds = []

    {conditions, binds} = bind_equals(conditions, binds, "key", key)
    {conditions, binds} = bind_equals(conditions, binds, "language", language)

    if q && String.trim(q) != "" do
      index = length(binds) + 1
      pattern = "%#{escape_like(q)}%"

      {Enum.join(
         conditions ++ ["(key ilike $#{index} escape '\\' or text ilike $#{index} escape '\\')"],
         " and "
       ), binds ++ [pattern]}
    else
      {Enum.join(conditions, " and "), binds}
    end
  end

  defp bind_equals(conditions, binds, _column, nil), do: {conditions, binds}

  defp bind_equals(conditions, binds, column, value) do
    index = length(binds) + 1
    {conditions ++ ["#{column} = $#{index}"], binds ++ [value]}
  end

  defp count_per_day(table, column, from) do
    all!(
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

  defp required_status(conn, body) when is_map(body) do
    case Map.get(body, "status") do
      value when value in ["active", "blocked"] -> {:ok, value}
      _ -> {:error, :bad_status}
    end
    |> case do
      {:error, :bad_status} ->
        {:error, Problem.send(conn, 400, "Bad Request", "status is required")}

      other ->
        other
    end
  end

  defp required_status(conn, _body),
    do: {:error, Problem.send(conn, 400, "Bad Request", "status is required")}

  defp validate(_conn, {:ok, value}), do: {:ok, value}
  defp validate(conn, {:error, errors}), do: {:error, Problem.validation(conn, errors)}

  defp require_caller(conn) do
    case conn.assigns[:caller] do
      nil -> {:error, Problem.send(conn, 401, "Unauthorized", "Authentication is required.")}
      caller -> {:ok, caller}
    end
  end

  defp require_role(conn, role) do
    with {:ok, caller} <- require_caller(conn) do
      if Auth.has_role?(caller, role) do
        {:ok, caller}
      else
        Log.event(:info, "authz_denied", "denied", "Denied a request lacking the required role",
          actor: caller.username
        )

        {:error,
         Problem.send(
           conn,
           403,
           "Forbidden",
           "You do not have the role required for this operation."
         )}
      end
    end
  end

  defp paging(conn) do
    with {:ok, page_raw} <- optional_single(conn, "page"),
         {:ok, size_raw} <- optional_single(conn, "size"),
         {:ok, page} <- parse_int(conn, page_raw, 0, "page"),
         {:ok, size} <- parse_int(conn, size_raw, 20, "size") do
      cond do
        page < 0 ->
          {:error, Problem.send(conn, 400, "Bad Request", "page must not be negative")}

        size < 1 or size > 100 ->
          {:error, Problem.send(conn, 400, "Bad Request", "size must be between 1 and 100")}

        true ->
          {:ok, %{page: page, size: size}}
      end
    end
  end

  defp optional_enum(conn, name, values) do
    case optional_single(conn, name) do
      {:ok, nil} ->
        {:ok, nil}

      {:ok, value} ->
        if value in values do
          {:ok, value}
        else
          {:error, Problem.send(conn, 400, "Bad Request", "unknown #{name}: #{value}")}
        end

      {:error, conn} ->
        {:error, conn}
    end
  end

  defp optional_single(conn, name) do
    case Problem.query_values(conn.query_string, name) do
      [] -> {:ok, nil}
      [value] -> {:ok, value}
      _ -> {:error, Problem.send(conn, 400, "Bad Request", "#{name} must not be repeated")}
    end
  end

  defp max_length(_conn, nil, _max, _name), do: :ok

  defp max_length(conn, value, max, name) do
    if String.length(value) <= max do
      :ok
    else
      {:error,
       Problem.send(conn, 400, "Bad Request", "#{name} must be at most #{max} characters")}
    end
  end

  defp parse_int(_conn, nil, fallback, _name), do: {:ok, fallback}
  defp parse_int(_conn, "", fallback, _name), do: {:ok, fallback}

  defp parse_int(conn, value, _fallback, name) do
    case Integer.parse(value) do
      {int, ""} -> {:ok, int}
      _ -> {:error, Problem.send(conn, 400, "Bad Request", "#{name} must be an integer")}
    end
  end

  defp datetime_param(conn, name) do
    case optional_single(conn, name) do
      {:ok, nil} ->
        {:ok, nil}

      {:ok, raw} ->
        case DateTime.from_iso8601(raw) do
          {:ok, value, _offset} ->
            {:ok, value}

          _ ->
            {:error,
             Problem.send(conn, 400, "Bad Request", "#{name} must be an RFC 3339 date-time")}
        end

      {:error, conn} ->
        {:error, conn}
    end
  end

  defp path_uuid(conn, value) do
    if Regex.match?(~r/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i, value) do
      {:ok, String.downcase(value)}
    else
      {:error, Problem.send(conn, 404, "Not Found")}
    end
  end

  defp bookmark_visible_to?(bookmark, caller) do
    bookmark["owner"] == caller or
      (bookmark["visibility"] == "public" and bookmark["status"] == "active")
  end

  defp bookmark_response(row) do
    %{
      id: row["id"],
      url: row["url"],
      title: row["title"],
      notes: row["notes"],
      tags: Enum.sort(row["tags"] || []),
      visibility: row["visibility"],
      status: row["status"],
      owner: row["owner"],
      createdAt: iso(row["created_at"]),
      updatedAt: iso(row["updated_at"])
    }
    |> Problem.omit_nil()
  end

  defp report_response(row) do
    %{
      id: row["id"],
      bookmarkId: row["bookmark_id"],
      reporter: row["reporter"],
      reason: row["reason"],
      comment: row["comment"],
      status: row["status"],
      createdAt: iso(row["created_at"]),
      resolvedBy: row["resolved_by"],
      resolvedAt: iso(row["resolved_at"]),
      resolutionNote: row["resolution_note"]
    }
    |> Problem.omit_nil()
  end

  defp message_response(row) do
    %{
      id: row["id"],
      key: row["key"],
      language: row["language"],
      text: row["text"],
      description: row["description"],
      createdAt: iso(row["created_at"]),
      updatedAt: iso(row["updated_at"])
    }
    |> Problem.omit_nil()
  end

  defp user_response(row) do
    %{
      username: row["username"],
      firstSeen: iso(row["first_seen"]),
      lastSeen: iso(row["last_seen"]),
      status: row["status"],
      blockedReason: row["blocked_reason"],
      bookmarkCount: row["bookmark_count"]
    }
    |> Problem.omit_nil()
  end

  defp audit_response(row) do
    %{
      id: row["id"],
      actor: row["actor"],
      action: row["action"],
      targetType: row["target_type"],
      targetId: row["target_id"],
      detail: row["detail"],
      createdAt: iso(row["created_at"])
    }
    |> Problem.omit_nil()
  end

  defp page_response(items, page, size, total_items) do
    %{
      items: items,
      page: page,
      size: size,
      totalItems: total_items,
      totalPages: ceil(total_items / size)
    }
  end

  defp message_snapshot(message) do
    %{
      key: message["key"],
      language: message["language"],
      text: message["text"],
      description: message["description"]
    }
  end

  defp audit!(actor, action, target_type, target_id, detail) do
    encoded = if is_nil(detail), do: nil, else: Jason.encode!(detail)

    Repo.query!(
      """
      insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at)
      values ($1::text::uuid, $2, $3, $4, $5, $6::jsonb, $7)
      """,
      [Ecto.UUID.generate(), actor, action, target_type, target_id, encoded, now()]
    )
  end

  defp etag_json(conn, status, payload) do
    body = Jason.encode!(payload)
    etag = "\"" <> Base.url_encode64(:crypto.hash(:sha256, body), padding: false) <> "\""

    conn =
      conn
      |> put_resp_header("etag", etag)
      |> put_resp_header("cache-control", "no-cache")

    if etag_matches?(conn, etag) do
      send_resp(conn, 304, "")
    else
      conn
      |> put_status(status)
      |> put_resp_content_type("application/json")
      |> send_resp(status, body)
    end
  end

  defp etag_matches?(conn, etag) do
    conn
    |> get_req_header("if-none-match")
    |> Enum.flat_map(&String.split(&1, ","))
    |> Enum.map(&String.trim/1)
    |> Enum.any?(&(&1 == etag))
  end

  defp all!(sql, params), do: sql |> Repo.query!(params) |> rows()
  defp one!(sql, params), do: sql |> all!(params) |> List.first()

  defp one(sql, params) do
    case Repo.query(sql, params) do
      {:ok, result} -> result |> rows() |> List.first()
      {:error, _} -> nil
    end
  end

  defp scalar!(sql, params) do
    %{rows: [[value]]} = Repo.query!(sql, params)
    value
  end

  defp rows(%Postgrex.Result{columns: columns, rows: rows}) do
    Enum.map(rows, fn row ->
      columns
      |> Enum.zip(row)
      |> Map.new()
    end)
  end

  defp escape_like(value) do
    value
    |> String.replace("\\", "\\\\")
    |> String.replace("%", "\\%")
    |> String.replace("_", "\\_")
  end

  defp optional_body_string(body, key) when is_map(body) do
    case Map.get(body, key) do
      value when is_binary(value) -> value
      _ -> nil
    end
  end

  defp optional_body_string(_body, _key), do: nil

  defp now, do: DateTime.utc_now() |> DateTime.truncate(:microsecond)
  defp iso(nil), do: nil
  defp iso(%DateTime{} = value), do: DateTime.to_iso8601(value)

  defp iso(%NaiveDateTime{} = value),
    do: value |> DateTime.from_naive!("Etc/UTC") |> DateTime.to_iso8601()
end
