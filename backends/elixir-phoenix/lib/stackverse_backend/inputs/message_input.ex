defmodule StackverseBackend.Inputs.MessageInput do
  @moduledoc false

  use Ecto.Schema

  import Ecto.Changeset

  @primary_key false
  embedded_schema do
    field :key, :string
    field :language, :string
    field :text, :string
    field :description, :string
  end

  @key_pattern ~r/^[a-z0-9-]+(\.[a-z0-9-]+)*$/
  @language_pattern ~r/^[a-z]{2}$/

  def changeset(body) when is_map(body) do
    key = body |> string("key") |> String.trim()
    language = body |> string("language") |> String.trim()
    text = string(body, "text")
    description = optional_string(body, "description")

    change(%__MODULE__{}, %{
      key: key,
      language: language,
      text: text,
      description: description
    })
    |> require(
      Regex.match?(@key_pattern, key) and String.length(key) <= 150,
      :key,
      "validation.message.key.invalid"
    )
    |> require(
      Regex.match?(@language_pattern, language),
      :language,
      "validation.message.language.invalid"
    )
    |> require(text != "", :text, "validation.message.text.required")
    |> require(String.length(text) <= 2000, :text, "validation.message.text.too-long")
    |> require(
      length_of(description) <= 1000,
      :description,
      "validation.message.description.too-long"
    )
  end

  def changeset(_body), do: changeset(%{})

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
