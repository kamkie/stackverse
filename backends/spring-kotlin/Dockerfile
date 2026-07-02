# Build with the REPOSITORY ROOT as context (the image needs spec/messages):
#
#   docker build -t stackverse/backend-spring-kotlin:local -f backends/spring-kotlin/Dockerfile .

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
# wrapper + build scripts first so the dependency layer caches across source changes
COPY backends/spring-kotlin/gradlew ./
COPY backends/spring-kotlin/gradle gradle
COPY backends/spring-kotlin/settings.gradle.kts backends/spring-kotlin/build.gradle.kts ./
RUN sh gradlew dependencies --no-daemon --quiet
COPY backends/spring-kotlin/src src
RUN sh gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/build/libs/*.jar app.jar
# the message seed is part of the contract (SPEC rule 12) and ships inside the image
COPY spec/messages seed/messages
ENV SEED_MESSAGES_DIR=/app/seed/messages
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
