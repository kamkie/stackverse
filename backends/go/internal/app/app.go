// Package app wires the feature packages into one chi router.
package app

import (
	"log/slog"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/kamkie/stackverse/backends/go/internal/accounts"
	"github.com/kamkie/stackverse/backends/go/internal/audit"
	"github.com/kamkie/stackverse/backends/go/internal/auth"
	"github.com/kamkie/stackverse/backends/go/internal/bookmarks"
	"github.com/kamkie/stackverse/backends/go/internal/config"
	"github.com/kamkie/stackverse/backends/go/internal/messages"
	"github.com/kamkie/stackverse/backends/go/internal/moderation"
	"github.com/kamkie/stackverse/backends/go/internal/stats"
	"github.com/kamkie/stackverse/backends/go/internal/web"
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

	router := chi.NewRouter()
	router.Use(web.Recover(localizer, logger))

	// operational probes: anonymous, never authenticated, never account-checked
	router.Get("/healthz", meta.Healthz)
	router.Get("/readyz", meta.Readyz)

	router.Group(func(r chi.Router) {
		r.Use(web.ETagMiddleware)
		r.Use(web.DeprecationHeaders)
		r.Use(authMW.Authenticate)
		r.Use(accounts.Middleware(accountStore, localizer, logger))

		// public or optional-auth surface (SPEC rules 2 + 7): the listing
		// handlers still demand authentication unless visibility=public
		r.Get("/api/v1/bookmarks", bookmarksAPI.ListV1)
		r.Get("/api/v2/bookmarks", bookmarksAPI.ListV2)
		r.Get("/api/v1/bookmarks/{id}", bookmarksAPI.Get)
		r.Get("/api/v1/messages", messagesAPI.List)
		r.Get("/api/v1/messages/bundle", messagesAPI.Bundle)
		r.Get("/api/v1/messages/{id}", messagesAPI.Get)

		// authenticated surface
		r.Group(func(r chi.Router) {
			r.Use(authMW.RequireAuth)
			r.Post("/api/v1/bookmarks", bookmarksAPI.Create)
			r.Put("/api/v1/bookmarks/{id}", bookmarksAPI.Update)
			r.Delete("/api/v1/bookmarks/{id}", bookmarksAPI.Delete)
			r.Post("/api/v1/bookmarks/{id}/reports", moderationAPI.Report)
			r.Get("/api/v1/reports", moderationAPI.ListMine)
			r.Put("/api/v1/reports/{id}", moderationAPI.UpdateMine)
			r.Delete("/api/v1/reports/{id}", moderationAPI.Withdraw)
			r.Get("/api/v1/tags", bookmarksAPI.Tags)
			r.Get("/api/v1/me", auth.Me)
		})

		// moderator surface
		r.Group(func(r chi.Router) {
			r.Use(authMW.RequireRole("moderator"))
			r.Get("/api/v1/admin/reports", moderationAPI.ListQueue)
			r.Put("/api/v1/admin/reports/{id}", moderationAPI.Resolve)
			r.Put("/api/v1/admin/bookmarks/{id}/status", moderationAPI.SetBookmarkStatus)
			r.Get("/api/v1/admin/stats", statsAPI.Get)
		})

		// admin surface
		r.Group(func(r chi.Router) {
			r.Use(authMW.RequireRole("admin"))
			r.Post("/api/v1/messages", messagesAPI.Create)
			r.Put("/api/v1/messages/{id}", messagesAPI.Update)
			r.Delete("/api/v1/messages/{id}", messagesAPI.Delete)
			r.Get("/api/v1/admin/users", accountsAPI.List)
			r.Get("/api/v1/admin/users/{username}", accountsAPI.Get)
			r.Put("/api/v1/admin/users/{username}/status", accountsAPI.SetStatus)
			r.Get("/api/v1/admin/audit-log", auditAPI.List)
		})
	})

	// anything unmatched is a problem document, not a plain-text 404
	router.NotFound(func(w http.ResponseWriter, r *http.Request) {
		web.WriteProblem(w, r, localizer, logger, web.NotFound())
	})
	router.MethodNotAllowed(func(w http.ResponseWriter, r *http.Request) {
		web.WriteProblem(w, r, localizer, logger,
			&web.Problem{Status: http.StatusMethodNotAllowed, Title: "Method Not Allowed"})
	})

	return router, messageStore
}
