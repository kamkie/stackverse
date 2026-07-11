defmodule StackverseBackend.MixProject do
  use Mix.Project

  def project do
    [
      app: :stackverse_backend,
      version: "0.1.0",
      elixir: "~> 1.20",
      elixirc_paths: elixirc_paths(Mix.env()),
      start_permanent: Mix.env() == :prod,
      test_coverage: [tool: ExCoveralls],
      deps: deps()
    ]
  end

  def cli do
    [
      preferred_envs: [
        coveralls: :test,
        "coveralls.detail": :test,
        "coveralls.html": :test,
        "coveralls.lcov": :test
      ]
    ]
  end

  def application do
    [
      mod: {StackverseBackend.Application, []},
      extra_applications: [:crypto, :logger, :runtime_tools, :ssl]
    ]
  end

  defp elixirc_paths(:test), do: ["lib", "test/support"]
  defp elixirc_paths(_env), do: ["lib"]

  defp deps do
    [
      {:ecto_sql, "~> 3.13"},
      {:excoveralls, "~> 0.18", only: :test},
      {:jason, "~> 1.4"},
      {:jose, "~> 1.11"},
      {:phoenix, "~> 1.8"},
      {:bandit, "~> 1.8"},
      {:postgrex, ">= 0.0.0"},
      {:req, "~> 0.5"}
    ]
  end
end
