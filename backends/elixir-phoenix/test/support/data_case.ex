defmodule StackverseBackend.DataCase do
  @moduledoc false

  use ExUnit.CaseTemplate

  using do
    quote do
      alias Ecto.Changeset
      alias StackverseBackend.Repo

      import Ecto
      import Ecto.Changeset
      import Ecto.Query
    end
  end
end
