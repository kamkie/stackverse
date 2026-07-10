defmodule StackverseBackend.Schemas.Report do
  @moduledoc false

  use Ecto.Schema

  import Ecto.Changeset

  @primary_key {:id, Ecto.UUID, autogenerate: true}
  schema "reports" do
    field :bookmark_id, Ecto.UUID
    field :reporter, :string
    field :reason, :string
    field :comment, :string
    field :status, :string
    field :resolved_by, :string
    field :resolved_at, :utc_datetime_usec
    field :resolution_note, :string

    timestamps(inserted_at: :created_at, updated_at: false, type: :utc_datetime_usec)
  end

  def create_changeset(reporter, bookmark_id, input) do
    %__MODULE__{}
    |> cast(Map.merge(input, %{reporter: reporter, bookmark_id: bookmark_id, status: "open"}), [
      :bookmark_id,
      :reporter,
      :reason,
      :comment,
      :status
    ])
    |> validate_required([:bookmark_id, :reporter, :reason, :status])
    |> unique_constraint([:bookmark_id, :reporter], name: :uq_reports_one_open_per_reporter)
  end

  def update_changeset(report, input), do: cast(report, input, [:reason, :comment])

  def resolution_changeset(report, resolution, actor, note) do
    changeset =
      if resolution == "open" do
        change(report, status: "open", resolved_by: nil, resolved_at: nil, resolution_note: nil)
      else
        change(report,
          status: resolution,
          resolved_by: actor,
          resolved_at: DateTime.utc_now() |> DateTime.truncate(:microsecond),
          resolution_note: note
        )
      end

    unique_constraint(changeset, [:bookmark_id, :reporter],
      name: :uq_reports_one_open_per_reporter
    )
  end

  def to_row(nil), do: nil

  def to_row(report) do
    %{
      "id" => report.id,
      "bookmark_id" => report.bookmark_id,
      "reporter" => report.reporter,
      "reason" => report.reason,
      "comment" => report.comment,
      "status" => report.status,
      "resolved_by" => report.resolved_by,
      "resolved_at" => report.resolved_at,
      "resolution_note" => report.resolution_note,
      "created_at" => report.created_at
    }
  end
end
