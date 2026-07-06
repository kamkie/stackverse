// Package app wires the feature packages into one Echo router.
package app

import (
	"log/slog"
	"net/http"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/labstack/echo/v4"

	"github.com/kamkie/stackverse/backends/go-echo/internal/accounts"
	"github.com/kamkie/stackverse/backends/go-echo/internal/audit"
	"github.com/kamkie/stackverse/backends/go-echo/internal/auth"
	"github.com/kamkie/stackverse/backends/go-echo/internal/bookmarks"
	"github.com/kamkie/stackverse/backends/go-echo/internal/config"
	"github.com/kamkie/stackverse/backends/go-echo/internal/messages"
	"github.com/kamkie/stackverse/backends/go-echo/internal/moderation"
	"github.com/kamkie/stackverse/backends/go-echo/internal/stats"
	"github.com/kamkie/stackverse/backends/go-echo/internal/web"
)

// New assembles the router. The middleware order mirrors the reference
// backend: recover → ETag → deprecation headers → JWT authentication →
// account upsert + blocked check → per-route auth/role requirements.
func New(cfg config.Config, pool *pgxpool.Pool, logger *slog.Logger) (http.Handler, *messages.Store) {
	messageStore := messages.NewStore(pool)
	localizer := messageStore // implements web.Localizer

	auditService := audit.NewService(pool)
	bookmarkStore := bookmarks.NewStore(pool)
	accountStore := accounts.NewStore(pool)

	keys := auth.NewKeys(cfg.IssuerURI, cfg.JWKSURI, logger)
	authMW := auth.NewMiddleware(keys, cfg.IssuerURI, config.Audience, localizer, logger)

	messagesAPI := messages.NewAPI(messageStore, auditService, logger)
	bookmarksAPI := bookmarks.NewAPI(bookmarkStore, localizer, logger)
	moderationAPI := moderation.NewAPI(pool, bookmarkStore, auditService, localizer, logger)
	accountsAPI := accounts.NewAPI(accountStore, auditService, localizer, logger)
	auditAPI := audit.NewAPI(pool, localizer, logger)
	statsAPI := stats.NewAPI(pool, bookmarkStore, localizer, logger)
	meta := web.NewMeta(pool, logger)

	router := echo.New()
	router.HideBanner = true
	router.HidePort = true
	router.Use(echo.WrapMiddleware(web.Recover(localizer, logger)))
	router.HTTPErrorHandler = func(err error, c echo.Context) {
		if c.Response().Committed {
			return
		}
		status := http.StatusInternalServerError
		var title string
		if httpError, ok := err.(*echo.HTTPError); ok {
			status = httpError.Code
			if message, ok := httpError.Message.(string); ok {
				title = message
			}
		}
		if status == http.StatusNotFound {
			web.WriteProblem(c.Response().Writer, c.Request(), localizer, logger, web.NotFound())
			return
		}
		if title == "" {
			title = http.StatusText(status)
		}
		web.WriteProblem(c.Response().Writer, c.Request(), localizer, logger,
			&web.Problem{Status: status, Title: title})
	}

	// operational probes: anonymous, never authenticated, never account-checked
	router.GET("/healthz", web.Wrap(meta.Healthz))
	router.GET("/readyz", web.Wrap(meta.Readyz))

	baseMiddleware := []echo.MiddlewareFunc{
		echo.WrapMiddleware(web.ETagMiddleware),
		echo.WrapMiddleware(web.DeprecationHeaders),
		echo.WrapMiddleware(authMW.Authenticate),
		echo.WrapMiddleware(accounts.Middleware(accountStore, localizer, logger)),
	}
	withMiddleware := func(extra ...echo.MiddlewareFunc) []echo.MiddlewareFunc {
		chain := make([]echo.MiddlewareFunc, 0, len(baseMiddleware)+len(extra))
		chain = append(chain, baseMiddleware...)
		return append(chain, extra...)
	}
	authenticatedMiddleware := withMiddleware(echo.WrapMiddleware(authMW.RequireAuth))
	moderatorMiddleware := withMiddleware(echo.WrapMiddleware(authMW.RequireRole("moderator")))
	adminMiddleware := withMiddleware(echo.WrapMiddleware(authMW.RequireRole("admin")))

	// public or optional-auth surface (SPEC rules 2 + 7): the listing
	// handlers still demand authentication unless visibility=public
	router.GET("/api/v1/bookmarks", web.Wrap(bookmarksAPI.ListV1), baseMiddleware...)
	router.GET("/api/v2/bookmarks", web.Wrap(bookmarksAPI.ListV2), baseMiddleware...)
	router.GET("/api/v1/bookmarks/:id", web.Wrap(bookmarksAPI.Get), baseMiddleware...)
	router.GET("/api/v1/messages", web.Wrap(messagesAPI.List), baseMiddleware...)
	router.GET("/api/v1/messages/bundle", web.Wrap(messagesAPI.Bundle), baseMiddleware...)
	router.GET("/api/v1/messages/:id", web.Wrap(messagesAPI.Get), baseMiddleware...)

	// authenticated surface
	router.POST("/api/v1/bookmarks", web.Wrap(bookmarksAPI.Create), authenticatedMiddleware...)
	router.PUT("/api/v1/bookmarks/:id", web.Wrap(bookmarksAPI.Update), authenticatedMiddleware...)
	router.DELETE("/api/v1/bookmarks/:id", web.Wrap(bookmarksAPI.Delete), authenticatedMiddleware...)
	router.POST("/api/v1/bookmarks/:id/reports", web.Wrap(moderationAPI.Report), authenticatedMiddleware...)
	router.GET("/api/v1/reports", web.Wrap(moderationAPI.ListMine), authenticatedMiddleware...)
	router.PUT("/api/v1/reports/:id", web.Wrap(moderationAPI.UpdateMine), authenticatedMiddleware...)
	router.DELETE("/api/v1/reports/:id", web.Wrap(moderationAPI.Withdraw), authenticatedMiddleware...)
	router.GET("/api/v1/tags", web.Wrap(bookmarksAPI.Tags), authenticatedMiddleware...)
	router.GET("/api/v1/me", web.Wrap(auth.Me), authenticatedMiddleware...)

	// moderator surface
	router.GET("/api/v1/admin/reports", web.Wrap(moderationAPI.ListQueue), moderatorMiddleware...)
	router.PUT("/api/v1/admin/reports/:id", web.Wrap(moderationAPI.Resolve), moderatorMiddleware...)
	router.PUT("/api/v1/admin/bookmarks/:id/status", web.Wrap(moderationAPI.SetBookmarkStatus), moderatorMiddleware...)
	router.GET("/api/v1/admin/stats", web.Wrap(statsAPI.Get), moderatorMiddleware...)

	// admin surface
	router.POST("/api/v1/messages", web.Wrap(messagesAPI.Create), adminMiddleware...)
	router.PUT("/api/v1/messages/:id", web.Wrap(messagesAPI.Update), adminMiddleware...)
	router.DELETE("/api/v1/messages/:id", web.Wrap(messagesAPI.Delete), adminMiddleware...)
	router.GET("/api/v1/admin/users", web.Wrap(accountsAPI.List), adminMiddleware...)
	router.GET("/api/v1/admin/users/:username", web.Wrap(accountsAPI.Get), adminMiddleware...)
	router.PUT("/api/v1/admin/users/:username/status", web.Wrap(accountsAPI.SetStatus), adminMiddleware...)
	router.GET("/api/v1/admin/audit-log", web.Wrap(auditAPI.List), adminMiddleware...)

	return router, messageStore
}
