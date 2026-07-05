# Build with the REPOSITORY ROOT as context (the image needs spec/messages):
#
#   docker build -t stackverse/backend-micronaut-java:local -f backends/micronaut-java/Dockerfile .

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build
# wrapper + build scripts first so the dependency layer caches across source changes
COPY backends/micronaut-java/gradlew ./
COPY backends/micronaut-java/gradle gradle
COPY backends/micronaut-java/settings.gradle.kts backends/micronaut-java/build.gradle.kts ./
RUN sh gradlew dependencies --no-daemon --quiet
COPY backends/micronaut-java/src src
RUN sh gradlew installDist --no-daemon

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/build/install/stackverse-backend-micronaut-java/lib lib
# the message seed is part of the contract (SPEC rule 12) and ships inside the image
COPY spec/messages seed/messages
ENV SEED_MESSAGES_DIR=/app/seed/messages
# OpenTelemetry auto-instrumentation (traces/metrics/logs over OTLP). Inert
# unless enabled per-run: set OTEL_SDK_DISABLED=false and OTEL_* env vars
# (see docs/RUNNING.md and compose.yaml's observability profile).
ADD --chmod=444 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.29.0/opentelemetry-javaagent.jar otel-javaagent.jar
ENV OTEL_SDK_DISABLED=true
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/app/otel-javaagent.jar", "-cp", "/app/lib/*", "dev.stackverse.backend.Application"]
