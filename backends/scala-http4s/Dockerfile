FROM eclipse-temurin:25-jdk AS build

ARG SBT_VERSION=2.0.1

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
COPY backends/scala-http4s ./backends/scala-http4s

RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
    | tar -xz -C /opt \
    && ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt

WORKDIR /workspace/backends/scala-http4s
RUN sbt "clean; stage" \
    && mkdir -p /workspace/backend-stage \
    && cp -R target/out/jvm/scala-*/stackverse-backend-scala-http4s/universal/stage/. /workspace/backend-stage/

FROM eclipse-temurin:25-jre

RUN groupadd -r stackverse \
    && useradd -r -g stackverse -u 10001 stackverse

WORKDIR /app
COPY --from=build /workspace/backend-stage/ /app/
COPY spec/messages /app/spec/messages

RUN chmod +x /app/bin/stackverse-backend-scala-http4s \
    && chown -R stackverse:stackverse /app

ENV PORT=8080
ENV SEED_MESSAGES_DIR=/app/spec/messages

EXPOSE 8080
USER stackverse

ENTRYPOINT ["sh", "-c", "exec /app/bin/stackverse-backend-scala-http4s"]
