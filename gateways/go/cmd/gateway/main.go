// The Stackverse gateway in Go: stdlib net/http + chi routing, Redis-backed
// BFF sessions, and explicit OIDC code-flow / token-refresh handling.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"

	"github.com/kamkie/stackverse/gateways/go/internal/config"
	"github.com/kamkie/stackverse/gateways/go/internal/gateway"
	"github.com/kamkie/stackverse/gateways/go/internal/logx"
	"github.com/kamkie/stackverse/gateways/go/internal/otelx"
	"github.com/kamkie/stackverse/gateways/go/internal/session"
)

func main() {
	if err := run(); err != nil {
		slog.Error("The gateway failed to start", slog.String("error", err.Error()))
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	cfg, err := config.Load()
	if err != nil {
		return err
	}

	otelHandler, otelShutdown, err := otelx.Setup(ctx)
	if err != nil {
		return err
	}

	var extra []slog.Handler
	if otelHandler != nil {
		extra = append(extra, otelHandler)
	}
	logger := logx.New(logx.Level(cfg.LogLevel), cfg.LogFormat, extra...)
	slog.SetDefault(logger)

	redisStore, err := session.NewRedisStore(ctx, cfg.RedisURL)
	if err != nil {
		return err
	}
	defer redisStore.Close()

	var transport http.RoundTripper = http.DefaultTransport
	if otelx.Enabled() {
		transport = otelhttp.NewTransport(transport)
	}
	httpClient := &http.Client{
		Timeout:   10 * time.Second,
		Transport: transport,
	}

	handler, err := gateway.NewHandler(cfg, redisStore, logger, httpClient, transport)
	if err != nil {
		return err
	}
	if otelx.Enabled() {
		handler = otelhttp.NewHandler(handler, "gateway",
			otelhttp.WithSpanNameFormatter(func(_ string, r *http.Request) string {
				return r.Method + " " + r.URL.Path
			}))
	}

	server := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
	}

	errs := make(chan error, 1)
	go func() {
		if err := server.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
			errs <- err
		}
	}()

	logx.Event(ctx, logger, slog.LevelInfo, "application_start", "success",
		"Stackverse Go gateway listening on :"+cfg.Port,
		slog.String("port", cfg.Port),
		slog.String("backend_url", cfg.BackendURL.String()),
		slog.String("frontend_url", cfg.FrontendURLString()),
		slog.String("public_url", cfg.PublicURL.String()),
		slog.String("redis_endpoint", cfg.RedisEndpointForLogs()),
		slog.String("oidc_issuer_uri", cfg.OIDCIssuerURI),
		slog.String("oidc_internal_issuer_uri", cfg.OIDCInternalIssuerURI),
		slog.String("oidc_client_id", cfg.OIDCClientID),
		slog.String("log_level", cfg.LogLevel),
		slog.String("log_format", cfg.LogFormat),
		slog.Bool("otel_enabled", otelx.Enabled()),
	)

	select {
	case err := <-errs:
		return err
	case <-ctx.Done():
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	err = server.Shutdown(shutdownCtx)
	logx.Event(shutdownCtx, logger, slog.LevelInfo, "application_stop", "success",
		"Shutdown complete after termination signal")
	if otelErr := otelShutdown(shutdownCtx); otelErr != nil {
		logger.Warn("Flushing telemetry on shutdown failed", slog.String("error", otelErr.Error()))
	}
	return err
}
