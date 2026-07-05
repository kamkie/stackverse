# Build with the REPOSITORY ROOT as context (the image needs spec/messages):
#
#   docker build -t stackverse/backend-python-fastapi:local -f backends/python-fastapi/Dockerfile .

FROM python:3.14.6-slim AS build
WORKDIR /build
RUN python -m venv /venv
ENV PATH="/venv/bin:${PATH}"
RUN python -m pip install --upgrade pip
COPY backends/python-fastapi/pyproject.toml ./
COPY backends/python-fastapi/stackverse_backend stackverse_backend
RUN python -m pip install --no-cache-dir .

FROM python:3.14.6-slim
RUN addgroup --system app && adduser --system --ingroup app app
WORKDIR /app
ENV PATH="/venv/bin:${PATH}"
COPY --from=build /venv /venv
COPY backends/python-fastapi/migrations migrations
COPY spec/messages seed/messages
ENV MIGRATIONS_DIR=/app/migrations
ENV SEED_MESSAGES_DIR=/app/seed/messages
ENV OTEL_SDK_DISABLED=true
USER app
EXPOSE 8080
ENTRYPOINT ["python", "-m", "stackverse_backend"]
