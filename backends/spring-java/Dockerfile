# Build with the REPOSITORY ROOT as context (the image needs spec/messages):
#
#   docker build -t stackverse/backend-spring-java:local -f backends/spring-java/Dockerfile .

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build
# wrapper + build scripts first so the dependency layer caches across source changes
COPY backends/spring-java/gradlew ./
COPY backends/spring-java/gradle gradle
COPY backends/spring-java/settings.gradle.kts backends/spring-java/build.gradle.kts ./
RUN sh gradlew dependencies --no-daemon --quiet
COPY backends/spring-java/src src
RUN sh gradlew bootJar --no-daemon

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/build/libs/*.jar app.jar
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
ENTRYPOINT ["java", "-javaagent:/app/otel-javaagent.jar", "-jar", "app.jar"]
