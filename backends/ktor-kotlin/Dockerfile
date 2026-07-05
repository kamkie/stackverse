# Build with the REPOSITORY ROOT as context (the image needs spec/messages):
#
#   docker build -t stackverse/backend-ktor-kotlin:local -f backends/ktor-kotlin/Dockerfile .

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build
# wrapper + build scripts first so the dependency layer caches across source changes
COPY backends/ktor-kotlin/gradlew ./
COPY backends/ktor-kotlin/gradle gradle
COPY backends/ktor-kotlin/settings.gradle.kts backends/ktor-kotlin/build.gradle.kts ./
RUN sh gradlew dependencies --no-daemon --quiet
COPY backends/ktor-kotlin/src src
RUN sh gradlew installDist --no-daemon

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/build/install/stackverse-backend-ktor-kotlin/ ./
# the message seed is part of the contract (SPEC rule 12) and ships inside the image
COPY spec/messages seed/messages
ENV SEED_MESSAGES_DIR=/app/seed/messages
# OpenTelemetry auto-instrumentation (traces/metrics/logs over OTLP). Inert
# unless enabled per-run: set OTEL_SDK_DISABLED=false and OTEL_* env vars
# (see docs/RUNNING.md and compose.yaml's observability profile).
ADD --chmod=444 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.29.0/opentelemetry-javaagent.jar otel-javaagent.jar
ENV OTEL_SDK_DISABLED=true
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/otel-javaagent.jar"
USER app
EXPOSE 8080
ENTRYPOINT ["/app/bin/stackverse-backend-ktor-kotlin"]
