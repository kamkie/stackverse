use anyhow::Context;
use opentelemetry::global;
use opentelemetry::trace::TracerProvider as _;
use opentelemetry_appender_tracing::layer::OpenTelemetryTracingBridge;
use opentelemetry_sdk::Resource;
use opentelemetry_sdk::logs::SdkLoggerProvider;
use opentelemetry_sdk::logs::log_processor_with_async_runtime::BatchLogProcessor;
use opentelemetry_sdk::propagation::TraceContextPropagator;
use opentelemetry_sdk::runtime;
use opentelemetry_sdk::trace::SdkTracerProvider;
use opentelemetry_sdk::trace::span_processor_with_async_runtime::BatchSpanProcessor;
use tracing_subscriber::EnvFilter;
use tracing_subscriber::prelude::*;

use crate::config::Config;

pub struct Guard {
    tracer_provider: Option<SdkTracerProvider>,
    logger_provider: Option<SdkLoggerProvider>,
}

impl Drop for Guard {
    fn drop(&mut self) {
        if let Some(provider) = &self.tracer_provider {
            let _ = provider.shutdown();
        }
        if let Some(provider) = &self.logger_provider {
            let _ = provider.shutdown();
        }
    }
}

pub fn init(config: &Config) -> anyhow::Result<Guard> {
    let filter = EnvFilter::try_new(&config.log_level)
        .or_else(|_| EnvFilter::try_new("info"))
        .context("create tracing filter")?
        .add_directive("hyper=warn".parse()?)
        .add_directive("reqwest=warn".parse()?)
        .add_directive("tower_http=warn".parse()?);

    if config.otel_disabled {
        if config.log_format.eq_ignore_ascii_case("text") {
            tracing_subscriber::registry()
                .with(filter)
                .with(tracing_subscriber::fmt::layer())
                .init();
        } else {
            tracing_subscriber::registry()
                .with(filter)
                .with(
                    tracing_subscriber::fmt::layer()
                        .json()
                        .with_current_span(true),
                )
                .init();
        }
        return Ok(Guard {
            tracer_provider: None,
            logger_provider: None,
        });
    }

    global::set_text_map_propagator(TraceContextPropagator::new());
    let resource = Resource::builder()
        .with_service_name("stackverse-gateway")
        .build();

    let span_exporter = opentelemetry_otlp::SpanExporter::builder()
        .with_http()
        .build()
        .context("build OTLP span exporter")?;
    let span_processor =
        BatchSpanProcessor::builder(span_exporter, runtime::TokioCurrentThread).build();
    let tracer_provider = SdkTracerProvider::builder()
        .with_resource(resource.clone())
        .with_span_processor(span_processor)
        .build();
    global::set_tracer_provider(tracer_provider.clone());
    let tracer = tracer_provider.tracer("stackverse-gateway-rust");
    let trace_layer = tracing_opentelemetry::layer().with_tracer(tracer);

    let log_exporter = opentelemetry_otlp::LogExporter::builder()
        .with_http()
        .build()
        .context("build OTLP log exporter")?;
    let log_processor =
        BatchLogProcessor::builder(log_exporter, runtime::TokioCurrentThread).build();
    let logger_provider = SdkLoggerProvider::builder()
        .with_resource(resource)
        .with_log_processor(log_processor)
        .build();
    let log_filter = EnvFilter::try_new(&config.log_level)
        .or_else(|_| EnvFilter::try_new("info"))
        .context("create OTLP log filter")?
        .add_directive("hyper=off".parse()?)
        .add_directive("opentelemetry=off".parse()?)
        .add_directive("reqwest=off".parse()?);
    let log_layer = OpenTelemetryTracingBridge::new(&logger_provider).with_filter(log_filter);

    if config.log_format.eq_ignore_ascii_case("text") {
        tracing_subscriber::registry()
            .with(filter)
            .with(trace_layer)
            .with(log_layer)
            .with(tracing_subscriber::fmt::layer())
            .init();
    } else {
        tracing_subscriber::registry()
            .with(filter)
            .with(trace_layer)
            .with(log_layer)
            .with(
                tracing_subscriber::fmt::layer()
                    .json()
                    .with_current_span(true),
            )
            .init();
    }

    Ok(Guard {
        tracer_provider: Some(tracer_provider),
        logger_provider: Some(logger_provider),
    })
}
