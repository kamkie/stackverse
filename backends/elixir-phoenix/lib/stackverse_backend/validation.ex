defmodule StackverseBackend.Validation do
  @moduledoc false

  @tag_pattern ~r/^[a-z0-9-]{1,30}$/
  @message_key_pattern ~r/^[a-z0-9-]+(\.[a-z0-9-]+)*$/
  @language_pattern ~r/^[a-z]{2}$/
  @report_reasons ~w[spam offensive broken-link other]
  @report_statuses ~w[open dismissed actioned]

  def validate_bookmark(body) when is_map(body) do
    url = body |> string("url") |> String.trim()
    title = body |> string("title") |> String.trim()
    notes = optional_string(body, "notes")
    tags = normalized_tags(Map.get(body, "tags", []))
    visibility = Map.get(body, "visibility", "private")

    []
    |> require(url != "", "url", "validation.url.required")
    |> require(
      url == "" or (String.length(url) <= 2000 and http_url?(url)),
      "url",
      "validation.url.invalid"
    )
    |> require(title != "", "title", "validation.title.required")
    |> require(String.length(title) <= 200, "title", "validation.title.too-long")
    |> require(length_of(notes) <= 4000, "notes", "validation.notes.too-long")
    |> require(length(tags) <= 10, "tags", "validation.tags.too-many")
    |> require(Enum.all?(tags, &Regex.match?(@tag_pattern, &1)), "tags", "validation.tag.invalid")
    |> require(visibility in ["private", "public"], "visibility", "validation.visibility.invalid")
    |> result(%{url: url, title: title, notes: notes, tags: tags, visibility: visibility})
  end

  def validate_bookmark(_body), do: validate_bookmark(%{})

  def validate_query_tags(tags) do
    normalized = Enum.map(tags, &String.downcase(String.trim(&1)))

    []
    |> require(
      Enum.all?(normalized, &Regex.match?(@tag_pattern, &1)),
      "tag",
      "validation.tag.invalid"
    )
    |> result(normalized)
  end

  def validate_message(body) when is_map(body) do
    key = body |> string("key") |> String.trim()
    language = body |> string("language") |> String.trim()
    text = string(body, "text")
    description = optional_string(body, "description")

    []
    |> require(
      Regex.match?(@message_key_pattern, key) and String.length(key) <= 150,
      "key",
      "validation.message.key.invalid"
    )
    |> require(
      Regex.match?(@language_pattern, language),
      "language",
      "validation.message.language.invalid"
    )
    |> require(text != "", "text", "validation.message.text.required")
    |> require(String.length(text) <= 2000, "text", "validation.message.text.too-long")
    |> require(
      length_of(description) <= 1000,
      "description",
      "validation.message.description.too-long"
    )
    |> result(%{key: key, language: language, text: text, description: description})
  end

  def validate_message(_body), do: validate_message(%{})

  def validate_report(body) when is_map(body) do
    reason = Map.get(body, "reason")
    comment = optional_string(body, "comment")

    []
    |> require(
      is_binary(reason) and reason in @report_reasons,
      "reason",
      "validation.report.reason.invalid"
    )
    |> require(length_of(comment) <= 1000, "comment", "validation.report.comment.too-long")
    |> result(%{reason: reason, comment: comment})
  end

  def validate_report(_body), do: validate_report(%{})

  def validate_report_resolution(body) when is_map(body) do
    resolution = Map.get(body, "resolution")
    note = optional_string(body, "note")

    []
    |> require(
      is_binary(resolution) and resolution in @report_statuses,
      "resolution",
      "validation.resolution.invalid"
    )
    |> require(length_of(note) <= 1000, "note", "validation.resolution.note.too-long")
    |> result(%{resolution: resolution, note: note})
  end

  def validate_report_resolution(_body), do: validate_report_resolution(%{})

  def validate_bookmark_status(body) when is_map(body) do
    status = Map.get(body, "status")
    note = optional_string(body, "note")

    []
    |> require(status in ["active", "hidden"], "status", "validation.bookmark-status.invalid")
    |> require(length_of(note) <= 1000, "note", "validation.bookmark-status.note.too-long")
    |> result(%{status: status, note: note})
  end

  def validate_bookmark_status(_body), do: validate_bookmark_status(%{})

  def validate_block_reason(status, reason) do
    []
    |> require(
      status != "blocked" or (is_binary(reason) and String.trim(reason) != ""),
      "reason",
      "validation.block.reason.required"
    )
    |> require(length_of(reason) <= 1000, "reason", "validation.block.reason.too-long")
    |> result(:ok)
  end

  defp normalized_tags(tags) when is_list(tags) do
    tags
    |> Enum.map(fn
      value when is_binary(value) -> value
      value -> to_string(value)
    end)
    |> Enum.map(&String.downcase(String.trim(&1)))
    |> Enum.uniq()
  end

  defp normalized_tags(_tags), do: []

  defp http_url?(value) do
    uri = URI.parse(value)
    uri.scheme in ["http", "https"] and is_binary(uri.host) and uri.host != ""
  rescue
    _ -> false
  end

  defp string(body, key) do
    case Map.get(body, key) do
      value when is_binary(value) -> value
      _ -> ""
    end
  end

  defp optional_string(body, key) do
    case Map.get(body, key) do
      value when is_binary(value) -> value
      _ -> nil
    end
  end

  defp length_of(nil), do: 0
  defp length_of(value), do: String.length(value)

  defp require(errors, true, _field, _message_key), do: errors

  defp require(errors, false, field, message_key),
    do: [%{field: field, message_key: message_key} | errors]

  defp result([], value), do: {:ok, value}
  defp result(errors, _value), do: {:error, Enum.reverse(errors)}
end
