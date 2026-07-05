# Build with the REPOSITORY ROOT as context (the image needs spec/messages):
#
#   docker build -t stackverse/backend-open-liberty-java:local -f backends/open-liberty-java/Dockerfile .

FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
# wrapper + build files first so the dependency layer caches across source changes
COPY backends/open-liberty-java/.mvn .mvn
COPY backends/open-liberty-java/mvnw backends/open-liberty-java/mvnw.cmd backends/open-liberty-java/pom.xml ./
RUN sh mvnw -B -ntp dependency:go-offline
COPY backends/open-liberty-java/src src
RUN sh mvnw -B -ntp package

FROM icr.io/appcafe/open-liberty:kernel-slim-java21-openj9-ubi-minimal
COPY --chown=1001:0 backends/open-liberty-java/src/main/liberty/config/ /config/
COPY --chown=1001:0 --from=build /build/target/stackverse-open-liberty-java.war /config/apps/stackverse-open-liberty-java.war
# the message seed is part of the contract (SPEC rule 12) and ships inside the image
COPY --chown=1001:0 spec/messages /opt/stackverse/spec/messages
ENV SEED_MESSAGES_DIR=/opt/stackverse/spec/messages
ENV PORT=8080
ENV OTEL_SDK_DISABLED=true
ADD --chown=1001:0 --chmod=444 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.29.0/opentelemetry-javaagent.jar /opt/stackverse/otel-javaagent.jar
ENV JVM_ARGS="-javaagent:/opt/stackverse/otel-javaagent.jar"
RUN features.sh
EXPOSE 8080
