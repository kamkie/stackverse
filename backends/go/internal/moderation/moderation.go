// Package moderation implements SPEC rules 13–15: reporting public bookmarks,
// the reporter's own report surface, the resolution state machine with sibling
// auto-resolution, and moderator hide/restore.
package moderation

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/kamkie/stackverse/backends/go/internal/audit"
	"github.com/kamkie/stackverse/backends/go/internal/auth"
	"github.com/kamkie/stackverse/backends/go/internal/bookmarks"
	"github.com/kamkie/stackverse/backends/go/internal/logx"
	"github.com/kamkie/stackverse/backends/go/internal/store"
	"github.com/kamkie/stackverse/backends/go/internal/web"
)

const (
	statusOpen      = "open"
	statusDismissed = "dismissed"
	statusActioned  = "actioned"
)

func validReason(reason string) bool {
	switch reason {
	case "spam", "offensive", "broken-link", "other":
		return true
	}
	return false
}

func validStatus(status string) bool {
	return status == statusOpen || status == statusDismissed || status == statusActioned
}

type report struct {
	ID             uuid.UUID
	BookmarkID     uuid.UUID
	Reporter       string
	Reason         string
	Comment        *string
	Status         string
	ResolvedBy     *string
	ResolvedAt     *time.Time
	ResolutionNote *string
	CreatedAt      time.Time
}

type reportRequest struct {
	Reason  string  `json:"reason"`
	Comment *string `json:"comment"`
}

type resolutionRequest struct {
	Resolution string  `json:"resolution"`
	Note       *string `json:"note"`
}

type bookmarkStatusRequest struct {
	Status *string `json:"status"`
	Note   *string `json:"note"`
}

type reportResponse struct {
	ID             uuid.UUID  `json:"id"`
	BookmarkID     uuid.UUID  `json:"bookmarkId"`
	Reporter       string     `json:"reporter"`
	Reason         string     `json:"reason"`
	Comment        *string    `json:"comment,omitempty"`
	Status         string     `json:"status"`
	CreatedAt      time.Time  `json:"createdAt"`
	ResolvedBy     *string    `json:"resolvedBy,omitempty"`
	ResolvedAt     *time.Time `json:"resolvedAt,omitempty"`
	ResolutionNote *string    `json:"resolutionNote,omitempty"`
}

func toResponse(r report) reportResponse {
	return reportResponse{
		ID: r.ID, BookmarkID: r.BookmarkID, Reporter: r.Reporter, Reason: r.Reason,
		Comment: r.Comment, Status: r.Status, CreatedAt: r.CreatedAt,
		ResolvedBy: r.ResolvedBy, ResolvedAt: r.ResolvedAt, ResolutionNote: r.ResolutionNote,
	}
}

const reportColumns = "id, bookmark_id, reporter, reason, comment, status, resolved_by, resolved_at, resolution_note, created_at"

func scanReport(row pgx.Row) (report, error) {
	var r report
	err := row.Scan(&r.ID, &r.BookmarkID, &r.Reporter, &r.Reason, &r.Comment, &r.Status,
		&r.ResolvedBy, &r.ResolvedAt, &r.ResolutionNote, &r.CreatedAt)
	return r, err
}

type API struct {
	pool      *pgxpool.Pool
	bookmarks *bookmarks.Store
	audit     *audit.Service
	localizer web.Localizer
	logger    *slog.Logger
}

func NewAPI(pool *pgxpool.Pool, bookmarkStore *bookmarks.Store, auditService *audit.Service, localizer web.Localizer, logger *slog.Logger) *API {
	return &API{pool: pool, bookmarks: bookmarkStore, audit: auditService, localizer: localizer, logger: logger}
}

func (a *API) fail(w http.ResponseWriter, r *http.Request, err error) {
	web.Error(w, r, a.localizer, a.logger, err)
}

// Report implements rule 13: only public, non-hidden bookmarks can be
// reported; anything else is a 404 mask. At most one open report per
// (bookmark, reporter) — enforced by a pre-check and, for races, the partial
// unique index.
func (a *API) Report(w http.ResponseWriter, r *http.Request) {
	reporter := auth.FromContext(r.Context()).Username
	bookmarkID, problem := pathUUID(r, "id")
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	bookmark, err := a.bookmarks.ByID(r.Context(), bookmarkID)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	if bookmark.Visibility != bookmarks.VisibilityPublic || bookmark.Status != bookmarks.StatusActive {
		a.fail(w, r, web.NotFound())
		return
	}
	var body reportRequest
	if problem := web.DecodeJSON(r, &body); problem != nil {
		a.fail(w, r, problem)
		return
	}
	if problem := validateReport(body); problem != nil {
		a.fail(w, r, problem)
		return
	}

	duplicate := web.Conflict("You already have an open report on this bookmark.")
	var exists bool
	err = a.pool.QueryRow(r.Context(),
		"select exists (select 1 from reports where bookmark_id = $1 and reporter = $2 and status = 'open')",
		bookmarkID, reporter).Scan(&exists)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	if exists {
		a.fail(w, r, duplicate)
		return
	}
	created := report{
		ID: uuid.New(), BookmarkID: bookmarkID, Reporter: reporter,
		Reason: body.Reason, Comment: body.Comment, Status: statusOpen, CreatedAt: store.NowUTC(),
	}
	_, err = a.pool.Exec(r.Context(),
		"insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at) values ($1, $2, $3, $4, $5, $6, $7)",
		created.ID, created.BookmarkID, created.Reporter, created.Reason, created.Comment, created.Status, created.CreatedAt)
	if err != nil {
		var pgError *pgconn.PgError
		if errors.As(err, &pgError) && pgError.Code == "23505" {
			// lost the race against a concurrent report by the same user
			a.fail(w, r, duplicate)
			return
		}
		a.fail(w, r, err)
		return
	}
	logx.Event(r.Context(), a.logger, slog.LevelInfo, "report_created", "success",
		"Report created on a public bookmark",
		slog.String("actor", reporter),
		slog.String("resource_type", "report"),
		slog.String("resource_id", created.ID.String()),
		slog.String("bookmark_id", bookmarkID.String()),
		slog.String("reason", created.Reason),
	)
	web.WriteJSON(w, http.StatusCreated, toResponse(created))
}

// ListMine is the reporter's feedback loop (rule 13): own reports, newest
// first, optional status filter.
func (a *API) ListMine(w http.ResponseWriter, r *http.Request) {
	reporter := auth.FromContext(r.Context()).Username
	page, size, problem := web.Paging(r)
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	status := r.URL.Query().Get("status")
	if status != "" && !validStatus(status) {
		a.fail(w, r, web.BadRequest("status must be one of: open, dismissed, actioned"))
		return
	}
	where := "where reporter = $1 and ($2 = '' or status = $2)"
	var total int64
	if err := a.pool.QueryRow(r.Context(), "select count(*) from reports "+where, reporter, status).Scan(&total); err != nil {
		a.fail(w, r, err)
		return
	}
	rows, err := a.pool.Query(r.Context(),
		"select "+reportColumns+" from reports "+where+" order by created_at desc, id desc limit $3 offset $4",
		reporter, status, size, page*size)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	a.writeReportPage(w, r, rows, page, size, total)
}

// UpdateMine lets the reporter revise reason/comment while the report is open
// (rule 13). Someone else's report is a 404 mask; a resolved one is a 409.
func (a *API) UpdateMine(w http.ResponseWriter, r *http.Request) {
	reporter := auth.FromContext(r.Context()).Username
	reportID, problem := pathUUID(r, "id")
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	var body reportRequest
	if problem := web.DecodeJSON(r, &body); problem != nil {
		a.fail(w, r, problem)
		return
	}
	var updated report
	err := a.inTx(r.Context(), func(tx pgx.Tx) error {
		locked, err := a.ownReportForUpdate(r.Context(), tx, reporter, reportID)
		if err != nil {
			return err
		}
		if problem := validateReport(body); problem != nil {
			return problem
		}
		if locked.Status != statusOpen {
			return web.Conflict("The report has already been resolved.")
		}
		locked.Reason, locked.Comment = body.Reason, body.Comment
		_, err = tx.Exec(r.Context(), "update reports set reason = $2, comment = $3 where id = $1",
			locked.ID, locked.Reason, locked.Comment)
		updated = locked
		return err
	})
	if err != nil {
		a.fail(w, r, err)
		return
	}
	logx.Event(r.Context(), a.logger, slog.LevelInfo, "report_updated", "success",
		"Report updated by its reporter",
		slog.String("actor", reporter),
		slog.String("resource_type", "report"),
		slog.String("resource_id", updated.ID.String()),
		slog.String("bookmark_id", updated.BookmarkID.String()),
		slog.String("reason", updated.Reason),
	)
	web.WriteJSON(w, http.StatusOK, toResponse(updated))
}

// Withdraw removes the caller's own open report and frees the
// one-open-report slot (rule 13).
func (a *API) Withdraw(w http.ResponseWriter, r *http.Request) {
	reporter := auth.FromContext(r.Context()).Username
	reportID, problem := pathUUID(r, "id")
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	var withdrawn report
	err := a.inTx(r.Context(), func(tx pgx.Tx) error {
		locked, err := a.ownReportForUpdate(r.Context(), tx, reporter, reportID)
		if err != nil {
			return err
		}
		if locked.Status != statusOpen {
			return web.Conflict("The report has already been resolved.")
		}
		_, err = tx.Exec(r.Context(), "delete from reports where id = $1", locked.ID)
		withdrawn = locked
		return err
	})
	if err != nil {
		a.fail(w, r, err)
		return
	}
	logx.Event(r.Context(), a.logger, slog.LevelInfo, "report_withdrawn", "success",
		"Report withdrawn by its reporter",
		slog.String("actor", reporter),
		slog.String("resource_type", "report"),
		slog.String("resource_id", withdrawn.ID.String()),
		slog.String("bookmark_id", withdrawn.BookmarkID.String()),
	)
	w.WriteHeader(http.StatusNoContent)
}

// ListQueue is the moderation queue: reports by status (default open),
// oldest first.
func (a *API) ListQueue(w http.ResponseWriter, r *http.Request) {
	page, size, problem := web.Paging(r)
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	status := r.URL.Query().Get("status")
	if status == "" {
		status = statusOpen
	}
	if !validStatus(status) {
		a.fail(w, r, web.BadRequest("status must be one of: open, dismissed, actioned"))
		return
	}
	var total int64
	if err := a.pool.QueryRow(r.Context(), "select count(*) from reports where status = $1", status).Scan(&total); err != nil {
		a.fail(w, r, err)
		return
	}
	rows, err := a.pool.Query(r.Context(),
		"select "+reportColumns+" from reports where status = $1 order by created_at, id limit $2 offset $3",
		status, size, page*size)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	a.writeReportPage(w, r, rows, page, size, total)
}

// Resolve implements rule 14: `actioned` hides the bookmark and drags every
// sibling open report along; decisions are revisable and `open` re-opens.
// Moving away from `actioned` never restores the bookmark (rule 15 keeps
// hide/restore explicit).
//
// Lock order: bookmark row first, then report rows. `actioned` writes the
// bookmark *and* every sibling open report, so two moderators resolving
// different reports of the same bookmark would otherwise lock report→bookmark
// in opposite orders and deadlock. Taking the bookmark lock up front
// serializes `actioned` resolutions per bookmark; every other path touches a
// single report and keeps its single report lock.
func (a *API) Resolve(w http.ResponseWriter, r *http.Request) {
	actor := auth.FromContext(r.Context()).Username
	reportID, problem := pathUUID(r, "id")
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	var body resolutionRequest
	if problem := web.DecodeJSON(r, &body); problem != nil {
		a.fail(w, r, problem)
		return
	}
	validator := &web.Validator{}
	validator.Check(validStatus(body.Resolution), "resolution", "validation.resolution.invalid")
	validator.Check(web.RuneLen(body.Note) <= 1000, "note", "validation.resolution.note.too-long")
	if problem := validator.Problem(); problem != nil {
		a.fail(w, r, problem)
		return
	}

	var resolved report
	var events []func()
	err := a.inTx(r.Context(), func(tx pgx.Tx) error {
		events = nil // the closure retries on serialization errors never happen here, but stay idempotent
		if body.Resolution == statusActioned {
			// bookmarkId is immutable, so an unlocked scalar read is a safe lock
			// target; a vanished bookmark cascades its reports away and the
			// locked re-read below 404s
			var bookmarkID uuid.UUID
			err := tx.QueryRow(r.Context(), "select bookmark_id from reports where id = $1", reportID).Scan(&bookmarkID)
			if errors.Is(err, pgx.ErrNoRows) {
				return web.NotFound()
			}
			if err != nil {
				return err
			}
			if _, err := tx.Exec(r.Context(), "select id from bookmarks where id = $1 for update", bookmarkID); err != nil {
				return err
			}
		}
		locked, err := scanReport(tx.QueryRow(r.Context(),
			"select "+reportColumns+" from reports where id = $1 for update", reportID))
		if errors.Is(err, pgx.ErrNoRows) {
			return web.NotFound()
		}
		if err != nil {
			return err
		}

		if body.Resolution == statusOpen {
			resolved, err = a.reopenOne(r, tx, locked, actor, &events)
			return err
		}
		resolved, err = a.resolveOne(r, tx, locked, body.Resolution, actor, body.Note, false, &events)
		if err != nil {
			return err
		}
		if body.Resolution != statusActioned {
			return nil
		}
		if err := a.hideBookmark(r, tx, locked.BookmarkID, actor, body.Note, &events); err != nil {
			return err
		}
		siblings, err := tx.Query(r.Context(),
			"select "+reportColumns+" from reports where bookmark_id = $1 and status = 'open' and id <> $2 order by id for update",
			locked.BookmarkID, locked.ID)
		if err != nil {
			return err
		}
		openSiblings, err := pgx.CollectRows(siblings, func(row pgx.CollectableRow) (report, error) { return scanReport(row) })
		if err != nil {
			return err
		}
		for _, sibling := range openSiblings {
			if _, err := a.resolveOne(r, tx, sibling, statusActioned, actor, body.Note, true, &events); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		a.fail(w, r, err)
		return
	}
	for _, emit := range events {
		emit() // diagnostics only — emitted after the transaction committed
	}
	web.WriteJSON(w, http.StatusOK, toResponse(resolved))
}

// SetBookmarkStatus implements rule 15: hide/restore switches `status` only;
// `visibility` is never touched.
func (a *API) SetBookmarkStatus(w http.ResponseWriter, r *http.Request) {
	actor := auth.FromContext(r.Context()).Username
	bookmarkID, problem := pathUUID(r, "id")
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	var body bookmarkStatusRequest
	if problem := web.DecodeJSON(r, &body); problem != nil {
		a.fail(w, r, problem)
		return
	}
	validator := &web.Validator{}
	validator.Check(body.Status != nil && (*body.Status == bookmarks.StatusActive || *body.Status == bookmarks.StatusHidden),
		"status", "validation.bookmark-status.invalid")
	validator.Check(web.RuneLen(body.Note) <= 1000, "note", "validation.bookmark-status.note.too-long")
	if problem := validator.Problem(); problem != nil {
		a.fail(w, r, problem)
		return
	}
	bookmark, err := a.bookmarks.ByID(r.Context(), bookmarkID)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	previous := bookmark.Status
	bookmark.Status = *body.Status
	bookmark.UpdatedAt = store.NowUTC()
	_, err = a.pool.Exec(r.Context(), "update bookmarks set status = $2, updated_at = $3 where id = $1",
		bookmark.ID, bookmark.Status, bookmark.UpdatedAt)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	err = a.audit.Record(r.Context(), actor, "bookmark.status-changed", "bookmark", bookmark.ID.String(),
		map[string]any{"from": previous, "to": bookmark.Status, "note": body.Note})
	if err != nil {
		a.fail(w, r, err)
		return
	}
	logx.Event(r.Context(), a.logger, slog.LevelInfo, "bookmark_status_changed", "success",
		"Bookmark moderation status changed",
		slog.String("actor", actor),
		slog.String("resource_type", "bookmark"),
		slog.String("resource_id", bookmark.ID.String()),
		slog.String("from", previous),
		slog.String("to", bookmark.Status),
	)
	web.WriteJSON(w, http.StatusOK, bookmarks.ToResponse(bookmark))
}

func (a *API) reopenOne(r *http.Request, tx pgx.Tx, locked report, actor string, events *[]func()) (report, error) {
	locked.Status = statusOpen
	locked.ResolvedBy, locked.ResolvedAt, locked.ResolutionNote = nil, nil, nil
	_, err := tx.Exec(r.Context(),
		"update reports set status = 'open', resolved_by = null, resolved_at = null, resolution_note = null where id = $1",
		locked.ID)
	if err != nil {
		return report{}, err
	}
	err = a.audit.RecordTx(r.Context(), tx, actor, "report.reopened", "report", locked.ID.String(),
		map[string]any{"bookmarkId": locked.BookmarkID.String()})
	if err != nil {
		return report{}, err
	}
	*events = append(*events, func() {
		logx.Event(r.Context(), a.logger, slog.LevelInfo, "report_reopened", "success", "Report re-opened",
			slog.String("actor", actor),
			slog.String("resource_type", "report"),
			slog.String("resource_id", locked.ID.String()),
			slog.String("bookmark_id", locked.BookmarkID.String()),
		)
	})
	return locked, nil
}

func (a *API) resolveOne(r *http.Request, tx pgx.Tx, locked report, resolution, actor string, note *string, autoResolved bool, events *[]func()) (report, error) {
	now := store.NowUTC()
	locked.Status, locked.ResolvedBy, locked.ResolvedAt, locked.ResolutionNote = resolution, &actor, &now, note
	_, err := tx.Exec(r.Context(),
		"update reports set status = $2, resolved_by = $3, resolved_at = $4, resolution_note = $5 where id = $1",
		locked.ID, resolution, actor, now, note)
	if err != nil {
		return report{}, err
	}
	err = a.audit.RecordTx(r.Context(), tx, actor, "report.resolved", "report", locked.ID.String(), map[string]any{
		"bookmarkId":   locked.BookmarkID.String(),
		"resolution":   resolution,
		"note":         note,
		"autoResolved": autoResolved,
	})
	if err != nil {
		return report{}, err
	}
	*events = append(*events, func() {
		logx.Event(r.Context(), a.logger, slog.LevelInfo, "report_resolved", "success", "Report resolved",
			slog.String("actor", actor),
			slog.String("resource_type", "report"),
			slog.String("resource_id", locked.ID.String()),
			slog.String("bookmark_id", locked.BookmarkID.String()),
			slog.String("resolution", resolution),
			slog.Bool("auto_resolved", autoResolved),
		)
	})
	return locked, nil
}

func (a *API) hideBookmark(r *http.Request, tx pgx.Tx, bookmarkID uuid.UUID, actor string, note *string, events *[]func()) error {
	var status string
	err := tx.QueryRow(r.Context(), "select status from bookmarks where id = $1", bookmarkID).Scan(&status)
	if errors.Is(err, pgx.ErrNoRows) {
		return web.NotFound()
	}
	if err != nil {
		return err
	}
	if status == bookmarks.StatusHidden {
		return nil
	}
	_, err = tx.Exec(r.Context(), "update bookmarks set status = 'hidden', updated_at = $2 where id = $1",
		bookmarkID, store.NowUTC())
	if err != nil {
		return err
	}
	err = a.audit.RecordTx(r.Context(), tx, actor, "bookmark.status-changed", "bookmark", bookmarkID.String(),
		map[string]any{"from": bookmarks.StatusActive, "to": bookmarks.StatusHidden, "note": note})
	if err != nil {
		return err
	}
	*events = append(*events, func() {
		logx.Event(r.Context(), a.logger, slog.LevelInfo, "bookmark_status_changed", "success",
			"Bookmark hidden by an actioned report",
			slog.String("actor", actor),
			slog.String("resource_type", "bookmark"),
			slog.String("resource_id", bookmarkID.String()),
			slog.String("from", bookmarks.StatusActive),
			slog.String("to", bookmarks.StatusHidden),
		)
	})
	return nil
}

// ownReportForUpdate locks the caller's own report; someone else's report is a
// 404 mask — existence is not disclosed.
func (a *API) ownReportForUpdate(ctx context.Context, tx pgx.Tx, reporter string, reportID uuid.UUID) (report, error) {
	locked, err := scanReport(tx.QueryRow(ctx,
		"select "+reportColumns+" from reports where id = $1 for update", reportID))
	if errors.Is(err, pgx.ErrNoRows) {
		return report{}, web.NotFound()
	}
	if err != nil {
		return report{}, err
	}
	if locked.Reporter != reporter {
		return report{}, web.NotFound()
	}
	return locked, nil
}

func (a *API) inTx(ctx context.Context, fn func(tx pgx.Tx) error) error {
	tx, err := a.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := fn(tx); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (a *API) writeReportPage(w http.ResponseWriter, r *http.Request, rows pgx.Rows, page, size int, total int64) {
	items, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (report, error) { return scanReport(row) })
	if err != nil {
		a.fail(w, r, err)
		return
	}
	responses := make([]reportResponse, len(items))
	for i, item := range items {
		responses[i] = toResponse(item)
	}
	web.WriteJSON(w, http.StatusOK, web.NewPage(responses, page, size, total))
}

func validateReport(body reportRequest) *web.Problem {
	validator := &web.Validator{}
	validator.Check(validReason(body.Reason), "reason", "validation.report.reason.invalid")
	validator.Check(web.RuneLen(body.Comment) <= 1000, "comment", "validation.report.comment.too-long")
	return validator.Problem()
}

func pathUUID(r *http.Request, name string) (uuid.UUID, *web.Problem) {
	id, err := uuid.Parse(chi.URLParam(r, name))
	if err != nil {
		return uuid.Nil, web.BadRequest(name + " must be a UUID")
	}
	return id, nil
}
