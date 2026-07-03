// The Stackverse backend in Go: stdlib net/http + chi routing, pgx, and a
// hand-rolled JWKS validator — see backends/go/README.md for the idioms this
// implementation demonstrates.
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

	"github.com/kamkie/stackverse/backends/go/internal/app"
	"github.com/kamkie/stackverse/backends/go/internal/config"
	"github.com/kamkie/stackverse/backends/go/internal/logx"
	"github.com/kamkie/stackverse/backends/go/internal/otelx"
	"github.com/kamkie/stackverse/backends/go/internal/store"
)

func main() {
	if err := run(); err != nil {
		// FATAL: the process cannot continue — logged once, exit non-zero
		slog.Error("The backend failed to start", slog.String("error", err.Error()))
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	cfg := config.Load()

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

	pool, err := store.Open(ctx, cfg.DSN(), logger)
	if err != nil {
		return err
	}
	defer pool.Close()

	handler, messageStore := app.New(cfg, pool, logger)

	if err := messageStore.Seed(ctx, cfg.SeedMessagesDir, logger); err != nil {
		return err
	}

	if otelx.Enabled() {
		handler = otelhttp.NewHandler(handler, "backend",
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

	// effective config with secrets redacted (docs/LOGGING.md §5)
	logx.Event(ctx, logger, slog.LevelInfo, "application_start", "success",
		"Stackverse Go backend listening on :"+cfg.Port,
		slog.String("port", cfg.Port),
		slog.String("db_host", cfg.DBHost),
		slog.String("db_name", cfg.DBName),
		slog.String("oidc_issuer_uri", cfg.IssuerURI),
		slog.String("oidc_jwks_uri", cfg.JWKSURI),
		slog.String("seed_messages_dir", cfg.SeedMessagesDir),
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
