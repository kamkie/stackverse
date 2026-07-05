// Package accounts holds the app-level user records (identity itself belongs
// to Keycloak): lazy provisioning on every authenticated request (SPEC rule
// 16), admin blocking (rule 17), and the admin user directory.
package accounts

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/kamkie/stackverse/backends/go-echo/internal/audit"
	"github.com/kamkie/stackverse/backends/go-echo/internal/auth"
	"github.com/kamkie/stackverse/backends/go-echo/internal/logx"
	"github.com/kamkie/stackverse/backends/go-echo/internal/store"
	"github.com/kamkie/stackverse/backends/go-echo/internal/web"
)

const (
	StatusActive  = "active"
	StatusBlocked = "blocked"
)

type Account struct {
	Username      string
	FirstSeen     time.Time
	LastSeen      time.Time
	Status        string
	BlockedReason *string
}

type Store struct {
	pool *pgxpool.Pool
}

func NewStore(pool *pgxpool.Pool) *Store {
	return &Store{pool: pool}
}

// RecordSeen upserts the caller's account row in a single statement, so
// concurrent first requests of a new user cannot race (SPEC rule 16).
func (s *Store) RecordSeen(ctx context.Context, username string) (Account, error) {
	var account Account
	err := s.pool.QueryRow(ctx,
		`insert into user_accounts (username, first_seen, last_seen, status)
		 values ($1, $2, $2, 'active')
		 on conflict (username) do update set last_seen = excluded.last_seen
		 returning username, first_seen, last_seen, status, blocked_reason`,
		username, store.NowUTC(),
	).Scan(&account.Username, &account.FirstSeen, &account.LastSeen, &account.Status, &account.BlockedReason)
	return account, err
}

type accountRow struct {
	Account       Account
	BookmarkCount int64
}

const accountWithCountColumns = `username, first_seen, last_seen, status, blocked_reason,
	(select count(*) from bookmarks b where b.owner = u.username) as bookmark_count`

func scanAccountRow(row pgx.Row) (accountRow, error) {
	var a accountRow
	err := row.Scan(&a.Account.Username, &a.Account.FirstSeen, &a.Account.LastSeen,
		&a.Account.Status, &a.Account.BlockedReason, &a.BookmarkCount)
	return a, err
}

func (s *Store) withBookmarkCount(ctx context.Context, username string) (accountRow, error) {
	row, err := scanAccountRow(s.pool.QueryRow(ctx,
		"select "+accountWithCountColumns+" from user_accounts u where username = $1", username))
	if errors.Is(err, pgx.ErrNoRows) {
		return accountRow{}, web.NotFound()
	}
	return row, err
}

func (s *Store) search(ctx context.Context, q, status string, page, size int) ([]accountRow, int64, error) {
	where := `where ($1 = '' or lower(username) like $1 escape '\') and ($2 = '' or status = $2)`
	qLike := ""
	if q != "" {
		qLike = "%" + web.EscapeLike(strings.ToLower(q)) + "%"
	}
	var total int64
	if err := s.pool.QueryRow(ctx,
		"select count(*) from user_accounts "+where, qLike, status,
	).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.pool.Query(ctx,
		"select "+accountWithCountColumns+" from user_accounts u "+where+
			" order by last_seen desc limit $3 offset $4",
		qLike, status, size, page*size)
	if err != nil {
		return nil, 0, err
	}
	items, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (accountRow, error) { return scanAccountRow(row) })
	return items, total, err
}

// setStatus runs on a caller-supplied Querier so the audit entry can share
// its transaction (SPEC rule 18).
func setStatus(ctx context.Context, db store.Querier, username, status string, reason *string) error {
	_, err := db.Exec(ctx,
		"update user_accounts set status = $2, blocked_reason = $3 where username = $1",
		username, status, reason)
	return err
}

func (s *Store) inTx(ctx context.Context, fn func(tx pgx.Tx) error) error {
	return store.InTx(ctx, s.pool, fn)
}

// Middleware runs right after JWT authentication: it upserts the caller's
// account row and rejects blocked accounts with a localized 403 problem
// (rules 16 + 17). The anonymous public surface never reaches this code path
// with an identity, so it keeps working for everyone else.
func Middleware(accountStore *Store, localizer web.Localizer, logger *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			identity := auth.FromContext(r.Context())
			if identity == nil {
				next.ServeHTTP(w, r)
				return
			}
			account, err := accountStore.RecordSeen(r.Context(), identity.Username)
			if err != nil {
				web.Error(w, r, localizer, logger, err)
				return
			}
			if account.Status == StatusBlocked {
				logx.Event(r.Context(), logger, slog.LevelWarn, "blocked_user_rejected", "denied",
					"Refused a request from a blocked account",
					slog.String("actor", identity.Username),
				)
				web.WriteProblem(w, r, localizer, logger, web.ForbiddenKey("error.account.blocked"))
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

type statusRequest struct {
	Status *string `json:"status"`
	Reason *string `json:"reason"`
}

type accountResponse struct {
	Username      string    `json:"username"`
	FirstSeen     time.Time `json:"firstSeen"`
	LastSeen      time.Time `json:"lastSeen"`
	Status        string    `json:"status"`
	BlockedReason *string   `json:"blockedReason,omitempty"`
	BookmarkCount int64     `json:"bookmarkCount"`
}

func toResponse(row accountRow) accountResponse {
	return accountResponse{
		Username: row.Account.Username, FirstSeen: row.Account.FirstSeen, LastSeen: row.Account.LastSeen,
		Status: row.Account.Status, BlockedReason: row.Account.BlockedReason, BookmarkCount: row.BookmarkCount,
	}
}

// API serves the admin user directory and blocking (admin role required).
type API struct {
	store     *Store
	audit     *audit.Service
	localizer web.Localizer
	logger    *slog.Logger
}

func NewAPI(store *Store, auditService *audit.Service, localizer web.Localizer, logger *slog.Logger) *API {
	return &API{store: store, audit: auditService, localizer: localizer, logger: logger}
}

func (a *API) fail(w http.ResponseWriter, r *http.Request, err error) {
	web.Error(w, r, a.localizer, a.logger, err)
}

func (a *API) List(w http.ResponseWriter, r *http.Request) {
	page, size, problem := web.Paging(r)
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	q := r.URL.Query().Get("q")
	if problem := web.MaxLength(q, 100, "q"); problem != nil {
		a.fail(w, r, problem)
		return
	}
	status := r.URL.Query().Get("status")
	if status != "" && status != StatusActive && status != StatusBlocked {
		a.fail(w, r, web.BadRequest("status must be one of: active, blocked"))
		return
	}
	rows, total, err := a.store.search(r.Context(), q, status, page, size)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	responses := make([]accountResponse, len(rows))
	for i, row := range rows {
		responses[i] = toResponse(row)
	}
	web.WriteJSON(w, http.StatusOK, web.NewPage(responses, page, size, total))
}

func (a *API) Get(w http.ResponseWriter, r *http.Request) {
	row, err := a.store.withBookmarkCount(r.Context(), web.URLParam(r, "username"))
	if err != nil {
		a.fail(w, r, err)
		return
	}
	web.WriteJSON(w, http.StatusOK, toResponse(row))
}

// SetStatus implements rule 17: block/unblock with audit; admins cannot block
// themselves; blocking takes effect on the target's next request.
func (a *API) SetStatus(w http.ResponseWriter, r *http.Request) {
	actor := auth.FromContext(r.Context()).Username
	username := web.URLParam(r, "username")
	var body statusRequest
	if problem := web.DecodeJSON(r, &body); problem != nil {
		a.fail(w, r, problem)
		return
	}
	if body.Status == nil {
		a.fail(w, r, web.BadRequest("status is required"))
		return
	}
	status := *body.Status
	if status != StatusActive && status != StatusBlocked {
		a.fail(w, r, web.BadRequest("status must be one of: active, blocked"))
		return
	}
	if _, err := a.store.withBookmarkCount(r.Context(), username); err != nil {
		a.fail(w, r, err)
		return
	}

	var reason *string
	if body.Reason != nil {
		trimmed := strings.TrimSpace(*body.Reason)
		reason = &trimmed
	}
	if status == StatusBlocked {
		validator := &web.Validator{}
		validator.Check(reason != nil && *reason != "", "reason", "validation.block.reason.required")
		validator.Check(web.RuneLen(reason) <= 1000, "reason", "validation.block.reason.too-long")
		if problem := validator.Problem(); problem != nil {
			a.fail(w, r, problem)
			return
		}
		if username == actor {
			a.fail(w, r, web.Conflict("Admins cannot block themselves."))
			return
		}
		err := a.store.inTx(r.Context(), func(tx pgx.Tx) error {
			if err := setStatus(r.Context(), tx, username, StatusBlocked, reason); err != nil {
				return err
			}
			return a.audit.RecordTx(r.Context(), tx, actor, "user.blocked", "user", username, map[string]any{"reason": reason})
		})
		if err != nil {
			a.fail(w, r, err)
			return
		}
		logx.Event(r.Context(), a.logger, slog.LevelInfo, "user_blocked", "success", "User account blocked",
			slog.String("actor", actor),
			slog.String("resource_type", "user"),
			slog.String("resource_id", username),
		)
	} else {
		err := a.store.inTx(r.Context(), func(tx pgx.Tx) error {
			if err := setStatus(r.Context(), tx, username, StatusActive, nil); err != nil {
				return err
			}
			return a.audit.RecordTx(r.Context(), tx, actor, "user.unblocked", "user", username, nil)
		})
		if err != nil {
			a.fail(w, r, err)
			return
		}
		logx.Event(r.Context(), a.logger, slog.LevelInfo, "user_unblocked", "success", "User account unblocked",
			slog.String("actor", actor),
			slog.String("resource_type", "user"),
			slog.String("resource_id", username),
		)
	}

	row, err := a.store.withBookmarkCount(r.Context(), username)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	web.WriteJSON(w, http.StatusOK, toResponse(row))
}
