defmodule StackverseBackend.Schemas.AuditEntry do
  @moduledoc false

  use Ecto.Schema

  import Ecto.Changeset

  @primary_key {:id, Ecto.UUID, autogenerate: true}
  schema "audit_entries" do
    field :actor, :string
    field :action, :string
    field :target_type, :string
    field :target_id, :string
    field :detail, :map

    timestamps(inserted_at: :created_at, updated_at: false, type: :utc_datetime_usec)
  end

  def changeset(attrs) do
    %__MODULE__{}
    |> cast(attrs, [:actor, :action, :target_type, :target_id, :detail])
    |> validate_required([:actor, :action, :target_type, :target_id])
  end

  def to_row(entry) do
    %{
      "id" => entry.id,
      "actor" => entry.actor,
      "action" => entry.action,
      "target_type" => entry.target_type,
      "target_id" => entry.target_id,
      "detail" => entry.detail,
      "created_at" => entry.created_at
    }
  end
end
