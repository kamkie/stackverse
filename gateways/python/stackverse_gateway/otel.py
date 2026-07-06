from __future__ import annotations

import logging
from collections.abc import Callable

from starlette.applications import Starlette

from .config import GatewayConfig
from .logging import LOGGER_NAME


def configure_otel(app: Starlette, config: GatewayConfig) -> Callable[[], None]:
    if not config.otel_enabled:
        return lambda: None

    from opentelemetry import _logs, metrics, trace
    from opentelemetry.exporter.otlp.proto.grpc._log_exporter import OTLPLogExporter
    from opentelemetry.exporter.otlp.proto.grpc.metric_exporter import OTLPMetricExporter
    from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
    from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
    from opentelemetry.instrumentation.starlette import StarletteInstrumentor
    from opentelemetry.sdk._logs import LoggerProvider, LoggingHandler
    from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
    from opentelemetry.sdk.metrics import MeterProvider
    from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import BatchSpanProcessor

    resource = Resource.create({})

    tracer_provider = TracerProvider(resource=resource)
    tracer_provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
    trace.set_tracer_provider(tracer_provider)

    meter_provider = MeterProvider(
        resource=resource, metric_readers=[PeriodicExportingMetricReader(OTLPMetricExporter())]
    )
    metrics.set_meter_provider(meter_provider)

    logger_provider = LoggerProvider(resource=resource)
    logger_provider.add_log_record_processor(BatchLogRecordProcessor(OTLPLogExporter()))
    _logs.set_logger_provider(logger_provider)
    logging.getLogger(LOGGER_NAME).addHandler(LoggingHandler(level=logging.NOTSET, logger_provider=logger_provider))

    StarletteInstrumentor.instrument_app(app)
    HTTPXClientInstrumentor().instrument()

    def shutdown() -> None:
        logger_provider.shutdown()
        meter_provider.shutdown()
        tracer_provider.shutdown()

    return shutdown
