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

  setup_all do
    start_supervised!(StackverseBackendWeb.Endpoint)
    :ok
  end

  setup _tags do
    {:ok, conn: Phoenix.ConnTest.build_conn()}
  end
end
