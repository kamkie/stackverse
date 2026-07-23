FROM rust:1.97.1-trixie AS build
WORKDIR /build
COPY Cargo.toml Cargo.lock ./
COPY src ./src
RUN cargo build --release --locked

FROM gcr.io/distroless/cc-debian13:nonroot
WORKDIR /app
COPY --from=build /build/target/release/stackverse-gateway-rust /app/gateway
ENV OTEL_SDK_DISABLED=true
EXPOSE 8000
ENTRYPOINT ["/app/gateway"]
