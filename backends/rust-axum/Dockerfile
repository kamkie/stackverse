# Build with the REPOSITORY ROOT as context (the image needs spec/messages):
#
#   docker build -t stackverse/backend-rust-axum:local -f backends/rust-axum/Dockerfile .

FROM rust:1.97.1-trixie AS build
WORKDIR /build
COPY backends/rust-axum/Cargo.toml backends/rust-axum/Cargo.lock ./
COPY backends/rust-axum/migrations ./migrations
COPY backends/rust-axum/src ./src
RUN cargo build --release --locked

FROM gcr.io/distroless/cc-debian13:nonroot
WORKDIR /app
COPY --from=build /build/target/release/stackverse-rust-axum /app/backend
COPY spec/messages seed/messages
ENV SEED_MESSAGES_DIR=/app/seed/messages
ENV OTEL_SDK_DISABLED=true
EXPOSE 8080
ENTRYPOINT ["/app/backend"]
