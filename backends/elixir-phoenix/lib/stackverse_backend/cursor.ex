defmodule StackverseBackend.Cursor do
  @moduledoc false

  def encode(%{created_at: created_at, id: id}) do
    created =
      created_at
      |> to_datetime()
      |> DateTime.to_iso8601()

    Base.url_encode64("#{created}|#{id}", padding: false)
  end

  def decode(raw) when is_binary(raw) do
    with {:ok, decoded} <- Base.url_decode64(raw, padding: false),
         [created, id] <- String.split(decoded, "|", parts: 2),
         {:ok, created_at, _offset} <- DateTime.from_iso8601(created),
         true <-
           Regex.match?(~r/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i, id) do
      {:ok, %{created_at: created_at, id: String.downcase(id)}}
    else
      _ -> :error
    end
  end

  def decode(_raw), do: :error

  defp to_datetime(%DateTime{} = value), do: value
  defp to_datetime(%NaiveDateTime{} = value), do: DateTime.from_naive!(value, "Etc/UTC")
end
