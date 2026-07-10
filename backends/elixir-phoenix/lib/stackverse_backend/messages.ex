defmodule StackverseBackend.Messages do
  @moduledoc "The message context owns localized message persistence and audit behavior."

  alias StackverseBackend.{Audit, Log, Query, Repo}

  @select "id::text as id, key, language, text, description, created_at, updated_at"

  def list(key, language, q, page, size) do
    {where, binds} = where(key, language, q)
    total = Query.scalar!("select count(*)::int from messages where #{where}", binds)

    items =
      Query.all!(
        """
        select #{@select} from messages
        where #{where}
        order by key, language
        limit $#{length(binds) + 1} offset $#{length(binds) + 2}
        """,
        binds ++ [size, page * size]
      )

    %{items: items, total: total}
  end

  def get(id), do: Query.one("select #{@select} from messages where id = $1::text::uuid", [id])

  def create(actor, input) do
    Repo.transaction(fn ->
      if Query.one!("select 1 as exists from messages where key = $1 and language = $2", [
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
             returning #{@select}
             """,
             [id, input.key, input.language, input.text, input.description, Query.now()]
           ) do
        {:ok, result} ->
          message = result |> Query.rows() |> List.first()
          Audit.record!(actor, "message.created", "message", message["id"], snapshot(message))
          message

        {:error, %Postgrex.Error{postgres: %{code: :unique_violation}}} ->
          Repo.rollback(:duplicate_message)

        {:error, error} ->
          raise error
      end
    end)
    |> log_result("message_created", "Message created", actor)
  end

  def update(actor, id, input) do
    Repo.transaction(fn ->
      if is_nil(Query.one!("select 1 as exists from messages where id = $1::text::uuid", [id])) do
        Repo.rollback(:not_found)
      end

      if Query.one!(
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
             returning #{@select}
             """,
             [id, input.key, input.language, input.text, input.description, Query.now()]
           ) do
        {:ok, result} ->
          message = result |> Query.rows() |> List.first()
          Audit.record!(actor, "message.updated", "message", message["id"], snapshot(message))
          message

        {:error, %Postgrex.Error{postgres: %{code: :unique_violation}}} ->
          Repo.rollback(:duplicate_message)

        {:error, error} ->
          raise error
      end
    end)
    |> log_result("message_updated", "Message updated", actor)
  end

  def delete(actor, id) do
    Repo.transaction(fn ->
      message =
        Query.one!("delete from messages where id = $1::text::uuid returning #{@select}", [id])

      if is_nil(message), do: Repo.rollback(:not_found)

      Audit.record!(actor, "message.deleted", "message", message["id"], snapshot(message))
      message
    end)
    |> log_result("message_deleted", "Message deleted", actor)
  end

  defp log_result({:ok, message} = result, event, text, actor) do
    Log.event(:info, event, "success", text,
      actor: actor,
      resource_type: "message",
      resource_id: message["id"],
      message_key: message["key"],
      language: message["language"]
    )

    result
  end

  defp log_result(result, _event, _text, _actor), do: result

  defp snapshot(message) do
    %{
      key: message["key"],
      language: message["language"],
      text: message["text"],
      description: message["description"]
    }
  end

  defp where(key, language, q) do
    {conditions, binds} = bind_equals(["true"], [], "key", key)
    {conditions, binds} = bind_equals(conditions, binds, "language", language)

    if q && String.trim(q) != "" do
      index = length(binds) + 1
      pattern = "%#{Query.escape_like(q)}%"

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
end
