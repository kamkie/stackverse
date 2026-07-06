# Build with the REPOSITORY ROOT as context (the image needs spec/messages):
#
#   docker build -t stackverse/backend-elixir-phoenix:local -f backends/elixir-phoenix/Dockerfile .

FROM elixir:1.18-alpine AS build
RUN apk add --no-cache build-base git
WORKDIR /app
ENV MIX_ENV=prod
RUN mix local.hex --force && mix local.rebar --force
COPY backends/elixir-phoenix/mix.exs backends/elixir-phoenix/mix.lock* ./
RUN mix deps.get --only prod && mix deps.compile
COPY backends/elixir-phoenix/config config
COPY backends/elixir-phoenix/lib lib
COPY backends/elixir-phoenix/priv priv
RUN mix compile && mix release

FROM alpine:3.24
RUN apk add --no-cache ca-certificates libstdc++ ncurses-libs openssl \
  && addgroup -S app \
  && adduser -S app -G app
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
