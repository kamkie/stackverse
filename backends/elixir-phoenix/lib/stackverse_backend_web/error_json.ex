defmodule StackverseBackendWeb.ErrorJSON do
  @moduledoc false

  def render(_template, assigns) do
    status = assigns[:status] || 500
    %{type: "about:blank", title: Plug.Conn.Status.reason_phrase(status), status: status}
  end
end
