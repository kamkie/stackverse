# Build with the REPOSITORY ROOT as context (the image needs spec/messages):
#
#   docker build -t stackverse/backend-grails:local -f backends/grails/Dockerfile .

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build
COPY backends/grails/gradlew ./
COPY backends/grails/gradle gradle
COPY backends/grails/settings.gradle backends/grails/build.gradle backends/grails/gradle.properties ./
RUN sh gradlew dependencies --no-daemon --quiet >/dev/null
COPY backends/grails/grails-app grails-app
COPY backends/grails/src src
RUN sh gradlew bootJar --no-daemon

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/build/libs/*.jar app.jar
COPY spec/messages seed/messages
ENV SEED_MESSAGES_DIR=/app/seed/messages
ADD --chmod=444 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.29.0/opentelemetry-javaagent.jar otel-javaagent.jar
ENV OTEL_SDK_DISABLED=true
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/app/otel-javaagent.jar", "-jar", "app.jar"]
