defmodule StackverseBackend.Schemas.Message do
  @moduledoc false

  use Ecto.Schema

  import Ecto.Changeset

  @primary_key {:id, Ecto.UUID, autogenerate: true}
  schema "messages" do
    field :key, :string
    field :language, :string
    field :text, :string
    field :description, :string

    timestamps(inserted_at: :created_at, updated_at: :updated_at, type: :utc_datetime_usec)
  end

  def changeset(message, input) do
    message
    |> cast(input, [:key, :language, :text, :description])
    |> validate_required([:key, :language, :text])
    |> unique_constraint([:key, :language], name: :uq_messages_key_language)
  end

  def to_row(nil), do: nil

  def to_row(message) do
    %{
      "id" => message.id,
      "key" => message.key,
      "language" => message.language,
      "text" => message.text,
      "description" => message.description,
      "created_at" => message.created_at,
      "updated_at" => message.updated_at
    }
  end
end
