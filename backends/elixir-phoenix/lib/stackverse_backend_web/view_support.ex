defmodule StackverseBackendWeb.ViewSupport do
  @moduledoc false

  def iso(nil), do: nil
  def iso(%DateTime{} = value), do: DateTime.to_iso8601(value)

  def iso(%NaiveDateTime{} = value),
    do: value |> DateTime.from_naive!("Etc/UTC") |> DateTime.to_iso8601()
end
