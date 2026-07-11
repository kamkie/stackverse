defmodule StackverseBackendWeb.ConnCase do
  @moduledoc false

  use ExUnit.CaseTemplate

  using do
    quote do
      @endpoint StackverseBackendWeb.Endpoint

      import Plug.Conn
      import Phoenix.ConnTest
    end
  end

  setup_all tags do
    start_supervised!(StackverseBackendWeb.Endpoint)

    if tags[:authenticated_api] == true do
      {key, original_settings} = StackverseBackend.TestAuth.install!()
      on_exit(fn -> StackverseBackend.TestAuth.uninstall!(original_settings) end)
      {:ok, signing_key: key}
    else
      :ok
    end
  end

  setup tags do
    if tags[:database] == true and System.get_env("STACKVERSE_DB_TESTS") == "true" do
      pid =
        Ecto.Adapters.SQL.Sandbox.start_owner!(StackverseBackend.Repo, shared: not tags[:async])

      on_exit(fn -> Ecto.Adapters.SQL.Sandbox.stop_owner(pid) end)
    end

    {:ok, conn: Phoenix.ConnTest.build_conn()}
  end
end
