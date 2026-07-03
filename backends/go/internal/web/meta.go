package web

import (
	"log/slog"
	"net/http"
	"sync/atomic"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Meta serves the operational probes (`meta` tag in the OpenAPI contract):
// liveness is the process being up, readiness checks the database.
type Meta struct {
	pool   *pgxpool.Pool
	logger *slog.Logger
	// ready tracks the last probe outcome: readiness *transitions* are signal
	// (WARN on loss, INFO on recovery) while individual probes are noise and
	// are never access-logged (docs/LOGGING.md §5).
	ready atomic.Bool
}

func NewMeta(pool *pgxpool.Pool, logger *slog.Logger) *Meta {
	meta := &Meta{pool: pool, logger: logger}
	meta.ready.Store(true)
	return meta
}

func (m *Meta) Healthz(w http.ResponseWriter, r *http.Request) {
	WriteJSON(w, http.StatusOK, map[string]string{"status": "up"})
}

func (m *Meta) Readyz(w http.ResponseWriter, r *http.Request) {
	var one int
	err := m.pool.QueryRow(r.Context(), "select 1").Scan(&one)
	if err != nil {
		if m.ready.Swap(false) {
			m.logger.WarnContext(r.Context(), "Readiness lost: the database is unreachable",
				slog.String("error", err.Error()))
		}
		WriteJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "unavailable"})
		return
	}
	if !m.ready.Swap(true) {
		m.logger.InfoContext(r.Context(), "Readiness recovered: the database is reachable again")
	}
	WriteJSON(w, http.StatusOK, map[string]string{"status": "ready"})
}
