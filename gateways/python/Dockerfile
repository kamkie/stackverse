# Build with this implementation directory as context:
#
#   docker build -t stackverse/gateway-python:local gateways/python

FROM python:3.14.6-slim AS build
WORKDIR /build
RUN python -m venv /venv
ENV PATH="/venv/bin:${PATH}"
RUN python -m pip install --upgrade pip
COPY pyproject.toml ./
COPY stackverse_gateway stackverse_gateway
RUN python -m pip install --no-cache-dir .

FROM python:3.14.6-slim
RUN addgroup --system app && adduser --system --ingroup app app
WORKDIR /app
ENV PATH="/venv/bin:${PATH}"
ENV OTEL_SDK_DISABLED=true
COPY --from=build /venv /venv
USER app
EXPOSE 8000
ENTRYPOINT ["python", "-m", "stackverse_gateway"]
