defmodule StackverseBackend.JsonLogFormatter do
  @moduledoc false

  def format(level, message, timestamp, metadata) do
    fields = metadata_fields(metadata)

    payload =
      %{
        "timestamp" => format_timestamp(timestamp),
        "level" => level |> Atom.to_string() |> String.upcase(),
        "message" => stringify(message)
      }
      |> Map.merge(fields)

    [Jason.encode!(payload), "\n"]
  rescue
    error ->
      [
        Jason.encode!(%{
          "level" => "ERROR",
          "message" => "log formatting failed",
          "formatter_error" => Exception.message(error)
        }),
        "\n"
      ]
  end

  defp normalize(value) when is_atom(value), do: Atom.to_string(value)
  defp normalize(value) when is_binary(value), do: value
  defp normalize(value) when is_number(value) or is_boolean(value), do: value
  defp normalize(value) when is_list(value), do: Enum.map(value, &normalize/1)

  defp normalize(%Plug.Conn{} = conn) do
    %{
      "method" => conn.method,
      "path" => conn.request_path,
      "status" => conn.status,
      "remote_ip" => conn.remote_ip |> Tuple.to_list() |> Enum.join(".")
    }
  end

  defp normalize(value) when is_map(value),
    do: Map.new(value, fn {key, val} -> {key_name(key), normalize(val)} end)

  defp normalize(value), do: inspect(value)

  defp key_name(value) when is_atom(value), do: Atom.to_string(value)
  defp key_name(value) when is_binary(value), do: value
  defp key_name(value), do: inspect(value)

  defp metadata_fields(metadata) when is_list(metadata) do
    if Keyword.keyword?(metadata) do
      metadata
      |> Enum.reject(fn {key, value} ->
        key in [:erl_level, :domain, :gl, :pid, :time] or is_nil(value)
      end)
      |> Map.new(fn {key, value} -> {key_name(key), normalize(value)} end)
    else
      %{"metadata" => normalize(metadata)}
    end
  end

  defp metadata_fields(%Plug.Conn{} = conn), do: %{"conn" => normalize(conn)}
  defp metadata_fields(metadata), do: %{"metadata" => normalize(metadata)}

  defp stringify(value) do
    IO.iodata_to_binary(value)
  rescue
    _ -> inspect(value)
  end

  defp format_timestamp({date, time}) do
    {{year, month, day}, {hour, minute, second, millisecond}} = {date, time}

    NaiveDateTime.new!(year, month, day, hour, minute, second, {millisecond * 1000, 6})
    |> DateTime.from_naive!("Etc/UTC")
    |> DateTime.to_iso8601()
  end
end
