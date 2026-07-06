defmodule StackverseBackend.Repo do
  use Ecto.Repo,
    otp_app: :stackverse_backend,
    adapter: Ecto.Adapters.Postgres
end
