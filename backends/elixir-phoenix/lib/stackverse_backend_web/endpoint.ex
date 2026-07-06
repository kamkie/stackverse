defmodule StackverseBackendWeb.Endpoint do
  use Phoenix.Endpoint, otp_app: :stackverse_backend

  plug Plug.Parsers,
    parsers: [:urlencoded, :json],
    pass: ["*/*"],
    json_decoder: Phoenix.json_library()

  plug Plug.MethodOverride
  plug Plug.Head
  plug StackverseBackendWeb.Router
end
