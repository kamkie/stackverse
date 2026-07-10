defmodule StackverseBackend.Inputs.BookmarkInput do
  @moduledoc false

  use Ecto.Schema

  import Ecto.Changeset

  @primary_key false
  embedded_schema do
    field :url, :string
    field :title, :string
    field :notes, :string
    field :tags, {:array, :string}, default: []
    field :visibility, :string, default: "private"
  end

  @tag_pattern ~r/^[a-z0-9-]{1,30}$/

  def changeset(body) when is_map(body) do
    url = body |> string("url") |> String.trim()
    title = body |> string("title") |> String.trim()
    notes = optional_string(body, "notes")
    tags = normalized_tags(Map.get(body, "tags", []))

    visibility =
      case Map.fetch(body, "visibility") do
        :error -> "private"
        {:ok, value} when is_binary(value) -> value
        {:ok, _value} -> nil
      end

    change(%__MODULE__{}, %{
      url: url,
      title: title,
      notes: notes,
      tags: tags,
      visibility: visibility
    })
    |> require(url != "", :url, "validation.url.required")
    |> require(
      url == "" or (String.length(url) <= 2000 and http_url?(url)),
      :url,
      "validation.url.invalid"
    )
    |> require(title != "", :title, "validation.title.required")
    |> require(String.length(title) <= 200, :title, "validation.title.too-long")
    |> require(length_of(notes) <= 4000, :notes, "validation.notes.too-long")
    |> require(length(tags) <= 10, :tags, "validation.tags.too-many")
    |> require(
      Enum.all?(tags, &Regex.match?(@tag_pattern, &1)),
      :tags,
      "validation.tag.invalid"
    )
    |> require(
      visibility in ["private", "public"],
      :visibility,
      "validation.visibility.invalid"
    )
  end

  def changeset(_body), do: changeset(%{})

  def query_tags_changeset(tags) do
    normalized = Enum.map(tags, &String.downcase(String.trim(&1)))

    change(%__MODULE__{}, %{tags: normalized})
    |> require(
      Enum.all?(normalized, &Regex.match?(@tag_pattern, &1)),
      :tag,
      "validation.tag.invalid"
    )
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

  defp require(changeset, true, _field, _message_key), do: changeset
  defp require(changeset, false, field, message_key), do: add_error(changeset, field, message_key)
end
