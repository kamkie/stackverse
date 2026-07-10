defmodule StackverseBackend.Inputs.MessageInput do
  @moduledoc false

  use Ecto.Schema

  import Ecto.Changeset

  alias StackverseBackend.Inputs.Support

  @primary_key false
  embedded_schema do
    field :key, :string
    field :language, :string
    field :text, :string
    field :description, :string
  end

  @key_pattern ~r/^[a-z0-9-]+(\.[a-z0-9-]+)*$/
  @language_pattern ~r/^[a-z]{2}$/
  @cast_messages %{
    key: "validation.message.key.invalid",
    language: "validation.message.language.invalid",
    text: "validation.message.text.required",
    description: "validation.message.description.too-long"
  }

  def changeset(body) do
    changeset =
      %__MODULE__{}
      |> Support.contract_cast(body, [:key, :language, :text, :description], @cast_messages)
      |> trim(:key)
      |> trim(:language)

    key = get_field(changeset, :key)
    language = get_field(changeset, :language)
    text = get_field(changeset, :text)
    description = get_field(changeset, :description)

    changeset
    |> Support.require(
      :key,
      is_binary(key) and Regex.match?(@key_pattern, key) and String.length(key) <= 150,
      "validation.message.key.invalid"
    )
    |> Support.require(
      :language,
      is_binary(language) and Regex.match?(@language_pattern, language),
      "validation.message.language.invalid"
    )
    |> Support.require(:text, is_binary(text) and text != "", "validation.message.text.required")
    |> Support.require(
      :text,
      not (is_binary(text) and text != "") or String.length(text) <= 2000,
      "validation.message.text.too-long"
    )
    |> Support.require(
      :description,
      Support.length_of(description) <= 1000,
      "validation.message.description.too-long"
    )
  end

  defp trim(changeset, field) do
    if Support.cast_valid?(changeset, field),
      do: update_change(changeset, field, &String.trim/1),
      else: changeset
  end
end
