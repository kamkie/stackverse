defmodule StackverseBackend.Persistence do
  @moduledoc false

  def unique_constraint?(changeset) do
    Enum.any?(changeset.errors, fn {_field, {_message, metadata}} ->
      metadata[:constraint] == :unique
    end)
  end

  def raise_invalid!(changeset),
    do: raise(Ecto.InvalidChangesetError, action: changeset.action, changeset: changeset)
end
