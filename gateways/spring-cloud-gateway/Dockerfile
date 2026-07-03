# Build with this directory as context (gateways build self-contained):
#
#   docker build -t stackverse/gateway-spring-cloud-gateway:local gateways/spring-cloud-gateway

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build
# wrapper + build scripts first so the dependency layer caches across source changes
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN sh gradlew dependencies --no-daemon --quiet
COPY src src
RUN sh gradlew bootJar --no-daemon

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/build/libs/*.jar app.jar
# OpenTelemetry auto-instrumentation (traces/metrics/logs over OTLP). Inert
# unless enabled per-run: set OTEL_SDK_DISABLED=false and OTEL_* env vars
# (see docs/RUNNING.md and compose.yaml's observability profile).
ADD --chmod=444 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.29.0/opentelemetry-javaagent.jar otel-javaagent.jar
ENV OTEL_SDK_DISABLED=true
USER app
ENV PORT=8000
EXPOSE 8000
ENTRYPOINT ["java", "-javaagent:/app/otel-javaagent.jar", "-jar", "app.jar"]
