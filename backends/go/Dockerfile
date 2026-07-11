# Build with the REPOSITORY ROOT as context (the image needs spec/messages):
#
#   docker build -t stackverse/backend-go:local -f backends/go/Dockerfile .

FROM golang:1.26.5-alpine AS build
WORKDIR /build
# module files first so the dependency layer caches across source changes
COPY backends/go/go.mod backends/go/go.sum ./
RUN go mod download && go mod verify
COPY backends/go/ ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /backend ./cmd/backend

# distroless static: no shell, no libc, non-root `nonroot` user built in
FROM gcr.io/distroless/static-debian13:nonroot
WORKDIR /app
COPY --from=build /backend /app/backend
# the message seed is part of the contract (SPEC rule 12) and ships inside the image
COPY spec/messages seed/messages
ENV SEED_MESSAGES_DIR=/app/seed/messages
ENV OTEL_SDK_DISABLED=true
EXPOSE 8080
ENTRYPOINT ["/app/backend"]
