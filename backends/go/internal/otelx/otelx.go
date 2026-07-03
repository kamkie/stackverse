// Package otelx wires the OpenTelemetry SDK: traces, metrics, and log records
// over OTLP, driven entirely by the standard OTEL_* environment variables and
// silent by default (OTEL_SDK_DISABLED=true; see docs/ARCHITECTURE.md).
package otelx

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"strings"

	"go.opentelemetry.io/contrib/bridges/otelslog"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlplog/otlploggrpc"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetricgrpc"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/log/global"
	"go.opentelemetry.io/otel/propagation"
	sdklog "go.opentelemetry.io/otel/sdk/log"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
)

// Enabled reports whether telemetry export is on for this process.
func Enabled() bool {
	return strings.EqualFold(os.Getenv("OTEL_SDK_DISABLED"), "false")
}

// Setup initializes the tracer, meter, and logger providers. It returns a
// slog.Handler that bridges log records into the OTLP pipeline (nil when
// disabled) and a shutdown function flushing all exporters.
func Setup(ctx context.Context) (slog.Handler, func(context.Context) error, error) {
	if !Enabled() {
		return nil, func(context.Context) error { return nil }, nil
	}

	// service.name comes from OTEL_SERVICE_NAME, extra attributes from
	// OTEL_RESOURCE_ATTRIBUTES — both read by the default resource detectors
	res, err := resource.New(ctx, resource.WithFromEnv(), resource.WithTelemetrySDK(), resource.WithHost())
	if err != nil {
		return nil, nil, err
	}

	traceExporter, err := otlptracegrpc.New(ctx)
	if err != nil {
		return nil, nil, err
	}
	tracerProvider := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(traceExporter),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(tracerProvider)
	// one browser action, one trace: the gateway propagates W3C traceparent
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{}, propagation.Baggage{}))

	metricExporter, err := otlpmetricgrpc.New(ctx)
	if err != nil {
		return nil, nil, err
	}
	meterProvider := sdkmetric.NewMeterProvider(
		sdkmetric.WithReader(sdkmetric.NewPeriodicReader(metricExporter)),
		sdkmetric.WithResource(res),
	)
	otel.SetMeterProvider(meterProvider)

	logExporter, err := otlploggrpc.New(ctx)
	if err != nil {
		return nil, nil, err
	}
	loggerProvider := sdklog.NewLoggerProvider(
		sdklog.WithProcessor(sdklog.NewBatchProcessor(logExporter)),
		sdklog.WithResource(res),
	)
	global.SetLoggerProvider(loggerProvider)

	shutdown := func(ctx context.Context) error {
		return errors.Join(
			tracerProvider.Shutdown(ctx),
			meterProvider.Shutdown(ctx),
			loggerProvider.Shutdown(ctx),
		)
	}
	return otelslog.NewHandler("stackverse-backend-go", otelslog.WithLoggerProvider(loggerProvider)), shutdown, nil
}
