// Package audit is the append-only audit trail (SPEC rule 18): every mutation
// through a moderator/admin capability writes an entry; entries are immutable
// and there is no API to change or delete them.
package audit

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/kamkie/stackverse/backends/go/internal/store"
	"github.com/kamkie/stackverse/backends/go/internal/web"
)

type Service struct {
	pool *pgxpool.Pool
}

func NewService(pool *pgxpool.Pool) *Service {
	return &Service{pool: pool}
}

// executor abstracts the pool and an open transaction, so an audit entry can
// commit or roll back together with the mutation it documents.
type executor interface {
	Exec(ctx context.Context, sql string, arguments ...any) (pgconn.CommandTag, error)
}

// Record appends one entry using the pool.
func (s *Service) Record(ctx context.Context, actor, action, targetType, targetID string, detail map[string]any) error {
	return record(ctx, s.pool, actor, action, targetType, targetID, detail)
}

// RecordTx appends one entry inside an open transaction.
func (s *Service) RecordTx(ctx context.Context, tx pgx.Tx, actor, action, targetType, targetID string, detail map[string]any) error {
	return record(ctx, tx, actor, action, targetType, targetID, detail)
}

func record(ctx context.Context, db executor, actor, action, targetType, targetID string, detail map[string]any) error {
	var detailJSON []byte
	if detail != nil {
		encoded, err := json.Marshal(detail)
		if err != nil {
			return err
		}
		detailJSON = encoded
	}
	_, err := db.Exec(ctx,
		"insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at) values ($1, $2, $3, $4, $5, $6, $7)",
		uuid.New(), actor, action, targetType, targetID, detailJSON, store.NowUTC())
	return err
}

// API serves `GET /api/v1/admin/audit-log` (admin only).
type API struct {
	pool      *pgxpool.Pool
	localizer web.Localizer
	logger    *slog.Logger
}

func NewAPI(pool *pgxpool.Pool, localizer web.Localizer, logger *slog.Logger) *API {
	return &API{pool: pool, localizer: localizer, logger: logger}
}

type entryResponse struct {
	ID         uuid.UUID       `json:"id"`
	Actor      string          `json:"actor"`
	Action     string          `json:"action"`
	TargetType string          `json:"targetType"`
	TargetID   string          `json:"targetId"`
	Detail     json.RawMessage `json:"detail,omitempty"`
	CreatedAt  time.Time       `json:"createdAt"`
}

func (a *API) List(w http.ResponseWriter, r *http.Request) {
	page, size, problem := web.Paging(r)
	if problem != nil {
		web.WriteProblem(w, r, a.localizer, a.logger, problem)
		return
	}
	query := r.URL.Query()
	from, problem := timeParam(query.Get("from"), "from")
	if problem != nil {
		web.WriteProblem(w, r, a.localizer, a.logger, problem)
		return
	}
	to, problem := timeParam(query.Get("to"), "to")
	if problem != nil {
		web.WriteProblem(w, r, a.localizer, a.logger, problem)
		return
	}

	where := `where ($1 = '' or actor = $1)
		and ($2 = '' or action = $2)
		and ($3 = '' or target_type = $3)
		and ($4 = '' or target_id = $4)
		and ($5::timestamptz is null or created_at >= $5)
		and ($6::timestamptz is null or created_at <= $6)`
	args := []any{query.Get("actor"), query.Get("action"), query.Get("targetType"), query.Get("targetId"), from, to}

	var total int64
	if err := a.pool.QueryRow(r.Context(), "select count(*) from audit_entries "+where, args...).Scan(&total); err != nil {
		web.Error(w, r, a.localizer, a.logger, err)
		return
	}
	rows, err := a.pool.Query(r.Context(),
		"select id, actor, action, target_type, target_id, detail, created_at from audit_entries "+where+
			" order by created_at desc, id desc limit $7 offset $8",
		append(args, size, page*size)...)
	if err != nil {
		web.Error(w, r, a.localizer, a.logger, err)
		return
	}
	items, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (entryResponse, error) {
		var e entryResponse
		err := row.Scan(&e.ID, &e.Actor, &e.Action, &e.TargetType, &e.TargetID, (*[]byte)(&e.Detail), &e.CreatedAt)
		return e, err
	})
	if err != nil {
		web.Error(w, r, a.localizer, a.logger, err)
		return
	}
	web.WriteJSON(w, http.StatusOK, web.NewPage(items, page, size, total))
}

func timeParam(raw, name string) (*time.Time, *web.Problem) {
	if raw == "" {
		return nil, nil
	}
	parsed, err := time.Parse(time.RFC3339Nano, raw)
	if err != nil {
		return nil, web.BadRequest(name + " must be an RFC 3339 timestamp")
	}
	return &parsed, nil
}
