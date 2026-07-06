# Build with the REPOSITORY ROOT as context (the image needs spec/messages):
#
#   docker build -t stackverse/backend-python-django:local -f backends/python-django/Dockerfile .

FROM python:3.14.6-slim AS build
WORKDIR /build
RUN python -m venv /venv
ENV PATH="/venv/bin:${PATH}"
RUN python -m pip install --upgrade pip
COPY backends/python-django/pyproject.toml ./
COPY backends/python-django/stackverse_api stackverse_api
COPY backends/python-django/stackverse_django stackverse_django
RUN python -m pip install --no-cache-dir .

FROM python:3.14.6-slim
RUN addgroup --system app && adduser --system --ingroup app app
WORKDIR /app
ENV PATH="/venv/bin:${PATH}"
COPY --from=build /venv /venv
COPY spec/messages seed/messages
ENV DJANGO_SETTINGS_MODULE=stackverse_django.settings
ENV SEED_MESSAGES_DIR=/app/seed/messages
ENV OTEL_SDK_DISABLED=true
USER app
EXPOSE 8080
ENTRYPOINT ["python", "-m", "stackverse_api"]
