defmodule StackverseBackend.Inputs.BookmarkInput do
  @moduledoc false

  use Ecto.Schema

  import Ecto.Changeset

  alias StackverseBackend.Inputs.Support

  @primary_key false
  embedded_schema do
    field :url, :string
    field :title, :string
    field :notes, :string
    field :tags, {:array, :string}, default: []
    field :visibility, :string, default: "private"
  end

  @tag_pattern ~r/^[a-z0-9-]{1,30}$/
  @cast_messages %{
    url: "validation.url.invalid",
    title: "validation.title.required",
    notes: "validation.notes.too-long",
    tags: "validation.tag.invalid",
    visibility: "validation.visibility.invalid"
  }

  def changeset(body) do
    changeset =
      %__MODULE__{}
      |> Support.contract_cast(body, [:url, :title, :notes, :tags, :visibility], @cast_messages)
      |> normalize_string(:url, &String.trim/1)
      |> normalize_string(:title, &String.trim/1)
      |> normalize_tags()

    url = get_field(changeset, :url)
    title = get_field(changeset, :title)
    notes = get_field(changeset, :notes)
    tags = get_field(changeset, :tags) || []
    visibility = get_field(changeset, :visibility)

    changeset
    |> Support.require(:url, is_binary(url) and url != "", "validation.url.required")
    |> Support.require(
      :url,
      not (is_binary(url) and url != "") or
        (String.length(url) <= 2000 and http_url?(url)),
      "validation.url.invalid"
    )
    |> Support.require(:title, is_binary(title) and title != "", "validation.title.required")
    |> Support.require(
      :title,
      not (is_binary(title) and title != "") or String.length(title) <= 200,
      "validation.title.too-long"
    )
    |> Support.require(:notes, length_of(notes) <= 4000, "validation.notes.too-long")
    |> Support.require(:tags, length(tags) <= 10, "validation.tags.too-many")
    |> Support.require(
      :tags,
      Enum.all?(tags, &Regex.match?(@tag_pattern, &1)),
      "validation.tag.invalid"
    )
    |> Support.require(
      :visibility,
      visibility in ["private", "public"],
      "validation.visibility.invalid"
    )
  end

  def query_tags_changeset(tags) do
    %__MODULE__{}
    |> Support.contract_cast(%{"tags" => tags}, [:tags], @cast_messages)
    |> normalize_tags()
    |> then(fn changeset ->
      values = get_field(changeset, :tags) || []

      changeset
      |> Support.require(
        :tags,
        Enum.all?(values, &Regex.match?(@tag_pattern, &1)),
        "validation.tag.invalid"
      )
      |> rename_error_field(:tags, :tag)
    end)
  end

  defp normalize_string(changeset, field, normalizer) do
    if Support.cast_valid?(changeset, field) do
      update_change(changeset, field, normalizer)
    else
      changeset
    end
  end

  defp normalize_tags(changeset) do
    if Support.cast_valid?(changeset, :tags) do
      update_change(changeset, :tags, fn tags ->
        tags
        |> Enum.map(&String.downcase(String.trim(&1)))
        |> Enum.uniq()
      end)
    else
      changeset
    end
  end

  defp rename_error_field(changeset, from, to) do
    errors =
      Enum.map(changeset.errors, fn {field, error} ->
        {if(field == from, do: to, else: field), error}
      end)

    %{changeset | errors: errors}
  end

  defp http_url?(value) do
    uri = URI.parse(value)
    uri.scheme in ["http", "https"] and is_binary(uri.host) and uri.host != ""
  rescue
    _ -> false
  end

  defp length_of(nil), do: 0
  defp length_of(value), do: String.length(value)
end
