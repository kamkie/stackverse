defmodule StackverseBackend.Messages do
  @moduledoc "The message context owns localized message persistence and audit behavior."

  import Ecto.Query

  alias StackverseBackend.{Audit, Log, Persistence, Query, Repo}
  alias StackverseBackend.Schemas.Message

  def list(key, language, q, page, size) do
    query = Message |> by_value(:key, key) |> by_value(:language, language) |> by_text(q)
    total = Repo.aggregate(query, :count, :id)

    items =
      query
      |> order_by([message], asc: message.key, asc: message.language)
      |> limit(^size)
      |> offset(^(page * size))
      |> Repo.all()
      |> Enum.map(&Message.to_row/1)

    %{items: items, total: total}
  end

  def get(id), do: Message |> Repo.get(id) |> Message.to_row()

  def create(actor, input) do
    Repo.transaction(fn ->
      case Repo.insert(Message.changeset(%Message{}, input)) do
        {:ok, message} ->
          row = Message.to_row(message)
          Audit.record!(actor, "message.created", "message", row["id"], snapshot(row))
          row

        {:error, changeset} ->
          if Persistence.unique_constraint?(changeset),
            do: Repo.rollback(:duplicate_message),
            else: Persistence.raise_invalid!(changeset)
      end
    end)
    |> log_result("message_created", "Message created", actor)
  end

  def update(actor, id, input) do
    Repo.transaction(fn ->
      case Repo.get(Message, id) do
        nil ->
          Repo.rollback(:not_found)

        message ->
          case message |> Message.changeset(input) |> Repo.update() do
            {:ok, updated} ->
              row = Message.to_row(updated)
              Audit.record!(actor, "message.updated", "message", row["id"], snapshot(row))
              row

            {:error, changeset} ->
              if Persistence.unique_constraint?(changeset),
                do: Repo.rollback(:duplicate_message),
                else: Persistence.raise_invalid!(changeset)
          end
      end
    end)
    |> log_result("message_updated", "Message updated", actor)
  end

  def delete(actor, id) do
    Repo.transaction(fn ->
      case Repo.get(Message, id) do
        nil ->
          Repo.rollback(:not_found)

        message ->
          Repo.delete!(message)
          row = Message.to_row(message)
          Audit.record!(actor, "message.deleted", "message", row["id"], snapshot(row))
          row
      end
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

  defp by_value(query, _field, nil), do: query
  defp by_value(query, :key, value), do: where(query, [message], message.key == ^value)
  defp by_value(query, :language, value), do: where(query, [message], message.language == ^value)

  defp by_text(query, q) do
    if q && String.trim(q) != "" do
      pattern = "%#{Query.escape_like(q)}%"
      where(query, [message], ilike(message.key, ^pattern) or ilike(message.text, ^pattern))
    else
      query
    end
  end
end
