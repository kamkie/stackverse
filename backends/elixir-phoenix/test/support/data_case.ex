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

  setup tags do
    if tags[:database] == true and System.get_env("STACKVERSE_DB_TESTS") == "true" do
      pid =
        Ecto.Adapters.SQL.Sandbox.start_owner!(StackverseBackend.Repo, shared: not tags[:async])

      on_exit(fn -> Ecto.Adapters.SQL.Sandbox.stop_owner(pid) end)
    end

    :ok
  end
end
