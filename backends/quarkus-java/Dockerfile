# Build with the REPOSITORY ROOT as context (the image needs spec/messages):
#
#   docker build -t stackverse/backend-quarkus-java:local -f backends/quarkus-java/Dockerfile .

FROM maven:3.9.16-eclipse-temurin-21-alpine AS build
WORKDIR /build
# Maven metadata first so the dependency layer caches across source changes.
COPY backends/quarkus-java/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backends/quarkus-java/src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/target/quarkus-app/lib/ lib/
COPY --from=build /build/target/quarkus-app/*.jar ./
COPY --from=build /build/target/quarkus-app/app/ app/
COPY --from=build /build/target/quarkus-app/quarkus/ quarkus/
# the message seed is part of the contract (SPEC rule 12) and ships inside the image
COPY spec/messages seed/messages
ENV SEED_MESSAGES_DIR=/app/seed/messages
ENV OTEL_SDK_DISABLED=true
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
