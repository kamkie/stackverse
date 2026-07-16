# Build with the REPOSITORY ROOT as context (the image needs spec/messages):
#
#   docker build -t stackverse/backend-elixir-phoenix:local -f backends/elixir-phoenix/Dockerfile .

FROM elixir:1.20-slim AS build
RUN apt-get update \
  && apt-get install -y --no-install-recommends build-essential ca-certificates git \
  && rm -rf /var/lib/apt/lists/*
WORKDIR /app
ENV MIX_ENV=prod
RUN mix local.hex --force && mix local.rebar --force
COPY backends/elixir-phoenix/mix.exs backends/elixir-phoenix/mix.lock* ./
RUN mix deps.get --only prod && mix deps.compile
COPY backends/elixir-phoenix/config config
COPY backends/elixir-phoenix/lib lib
COPY backends/elixir-phoenix/priv priv
RUN mix compile && mix release

FROM debian:trixie-slim
RUN apt-get update \
  && apt-get install -y --no-install-recommends ca-certificates libncurses6 libstdc++6 openssl \
  && rm -rf /var/lib/apt/lists/* \
  && groupadd --system app \
  && useradd --system --gid app --home-dir /app --no-create-home app
WORKDIR /app
COPY --from=build /app/_build/prod/rel/stackverse_backend ./
COPY spec/messages seed/messages
ENV SEED_MESSAGES_DIR=/app/seed/messages
ENV OTEL_SDK_DISABLED=true
ENV PHX_SERVER=true
USER app
EXPOSE 8080
ENTRYPOINT ["/app/bin/stackverse_backend"]
CMD ["start"]
