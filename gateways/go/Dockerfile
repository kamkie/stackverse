# Build with the gateway directory as context:
#
#   docker build -t stackverse/gateway-go:local gateways/go

FROM golang:1.26.4-alpine AS build
WORKDIR /build
COPY go.mod go.sum ./
RUN go mod download
COPY . ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /gateway ./cmd/gateway

FROM gcr.io/distroless/static-debian12:nonroot
WORKDIR /app
COPY --from=build /gateway /app/gateway
ENV OTEL_SDK_DISABLED=true
EXPOSE 8000
ENTRYPOINT ["/app/gateway"]
