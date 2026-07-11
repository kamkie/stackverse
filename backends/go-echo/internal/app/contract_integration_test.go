package app

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"io"
	"log/slog"
	"math/big"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/kamkie/stackverse/backends/go-echo/internal/config"
	"github.com/kamkie/stackverse/backends/go-echo/internal/messages"
	"github.com/kamkie/stackverse/backends/go-echo/internal/store"
)

const integrationDatabaseEnv = "STACKVERSE_TEST_DATABASE_URL"

type rawJSON string

type integrationHarness struct {
	handler      http.Handler
	pool         *pgxpool.Pool
	messages     *messages.Store
	privateKey   *rsa.PrivateKey
	issuer       string
	logs         *bytes.Buffer
	logger       *slog.Logger
	seedMessages string
}

type bookmarkWire struct {
	ID         uuid.UUID `json:"id"`
	URL        string    `json:"url"`
	Title      string    `json:"title"`
	Tags       []string  `json:"tags"`
	Visibility string    `json:"visibility"`
	Status     string    `json:"status"`
	Owner      string    `json:"owner"`
}

type reportWire struct {
	ID             uuid.UUID  `json:"id"`
	BookmarkID     uuid.UUID  `json:"bookmarkId"`
	Reporter       string     `json:"reporter"`
	Reason         string     `json:"reason"`
	Comment        *string    `json:"comment"`
	Status         string     `json:"status"`
	ResolvedBy     *string    `json:"resolvedBy"`
	ResolvedAt     *time.Time `json:"resolvedAt"`
	ResolutionNote *string    `json:"resolutionNote"`
}

type messageWire struct {
	ID          uuid.UUID `json:"id"`
	Key         string    `json:"key"`
	Language    string    `json:"language"`
	Text        string    `json:"text"`
	Description *string   `json:"description"`
}

func TestPostgresBackedContractBoundaries(t *testing.T) {
	h := newIntegrationHarness(t)

	t.Run("authentication authorization and blocked account boundary", func(t *testing.T) {
		h.resetAndSeed(t)
		h.logs.Reset()

		requireStatus(t, h.do(t, http.MethodGet, "/healthz", "", nil, nil), http.StatusOK)
		requireStatus(t, h.do(t, http.MethodGet, "/readyz", "", nil, nil), http.StatusOK)
		requireStatus(t, h.do(t, http.MethodGet, "/api/v2/bookmarks?visibility=public", "", nil, nil), http.StatusOK)
		if got := h.queryInt64(t, "select count(*) from user_accounts"); got != 0 {
			t.Fatalf("anonymous public traffic provisioned %d accounts", got)
		}

		unauthenticated := h.do(t, http.MethodPost, "/api/v1/bookmarks", "", rawJSON(`{"url":`), nil)
		requireStatus(t, unauthenticated, http.StatusUnauthorized)
		invalidBearer := "sensitive-invalid-bearer"
		invalid := h.do(t, http.MethodPost, "/api/v1/bookmarks", invalidBearer, rawJSON(`{"url":`), nil)
		requireStatus(t, invalid, http.StatusUnauthorized)
		if got := h.queryInt64(t, "select count(*) from user_accounts"); got != 0 {
			t.Fatalf("rejected authentication provisioned %d accounts", got)
		}

		demoToken := h.token(t, "demo")
		me := h.do(t, http.MethodGet, "/api/v1/me", demoToken, nil, nil)
		requireStatus(t, me, http.StatusOK)
		var identity struct {
			Username string   `json:"username"`
			Roles    []string `json:"roles"`
		}
		decodeResponse(t, me, &identity)
		if identity.Username != "demo" || len(identity.Roles) != 0 {
			t.Fatalf("unexpected /me response: %+v", identity)
		}
		var firstSeen, firstLastSeen time.Time
		if err := h.pool.QueryRow(context.Background(),
			"select first_seen, last_seen from user_accounts where username = 'demo'",
		).Scan(&firstSeen, &firstLastSeen); err != nil {
			t.Fatalf("read lazily provisioned account: %v", err)
		}
		staleLastSeen := time.Date(2000, time.January, 1, 0, 0, 0, 0, time.UTC)
		if _, err := h.pool.Exec(context.Background(),
			"update user_accounts set last_seen = $1 where username = 'demo'", staleLastSeen,
		); err != nil {
			t.Fatalf("make last_seen stale before refresh: %v", err)
		}
		requireStatus(t, h.do(t, http.MethodGet, "/api/v1/me", demoToken, nil, nil), http.StatusOK)
		var secondFirstSeen, secondLastSeen time.Time
		if err := h.pool.QueryRow(context.Background(),
			"select first_seen, last_seen from user_accounts where username = 'demo'",
		).Scan(&secondFirstSeen, &secondLastSeen); err != nil {
			t.Fatalf("read updated provisioned account: %v", err)
		}
		if !secondFirstSeen.Equal(firstSeen) || !secondLastSeen.After(staleLastSeen) {
			t.Fatalf("lazy provisioning timestamps changed incorrectly: first=(%s,%s) second=(%s,%s)",
				firstSeen, firstLastSeen, secondFirstSeen, secondLastSeen)
		}

		deniedBeforeBinding := h.do(t, http.MethodPut, "/api/v1/admin/reports/not-a-uuid", demoToken, rawJSON(`{"resolution":`), nil)
		requireStatus(t, deniedBeforeBinding, http.StatusForbidden)
		moderatorToken := h.token(t, "moderator", "moderator")
		validatedAfterRole := h.do(t, http.MethodPut, "/api/v1/admin/reports/not-a-uuid", moderatorToken, rawJSON(`{"resolution":`), nil)
		requireStatus(t, validatedAfterRole, http.StatusBadRequest)

		adminToken := h.token(t, "admin", "admin", "moderator")
		requireStatus(t, h.do(t, http.MethodGet, "/api/v1/me", adminToken, nil, nil), http.StatusOK)
		selfBlock := h.do(t, http.MethodPut, "/api/v1/admin/users/admin/status", adminToken, map[string]any{
			"status": "blocked", "reason": "self block must be rejected",
		}, nil)
		requireStatus(t, selfBlock, http.StatusConflict)
		const blockReason = "sensitive policy detail that belongs only in the audit trail"
		blocked := h.do(t, http.MethodPut, "/api/v1/admin/users/demo/status", adminToken, map[string]any{
			"status": "blocked",
			"reason": blockReason,
		}, nil)
		requireStatus(t, blocked, http.StatusOK)
		if got := h.queryInt64(t, "select count(*) from audit_entries where actor = 'admin' and action = 'user.blocked' and target_id = 'demo'"); got != 1 {
			t.Fatalf("user block audit count = %d, want 1", got)
		}
		requireStatus(t, h.do(t, http.MethodGet, "/api/v1/me", demoToken, nil, nil), http.StatusForbidden)
		requireStatus(t, h.do(t, http.MethodGet, "/api/v2/bookmarks?visibility=public", demoToken, nil, nil), http.StatusForbidden)
		requireStatus(t, h.do(t, http.MethodGet, "/api/v2/bookmarks?visibility=public", "", nil, nil), http.StatusOK)

		logged := h.logs.String()
		requireStructuredLogEvent(t, logged, "blocked_user_rejected", "denied", "WARN", map[string]string{"actor": "demo"})
		if strings.Contains(logged, demoToken) || strings.Contains(logged, invalidBearer) || strings.Contains(logged, blockReason) ||
			strings.Contains(logged, "Authorization") {
			t.Fatalf("authentication/account logs leaked credential or policy detail: %s", logged)
		}

		unblocked := h.do(t, http.MethodPut, "/api/v1/admin/users/demo/status", adminToken, map[string]any{"status": "active"}, nil)
		requireStatus(t, unblocked, http.StatusOK)
		requireStatus(t, h.do(t, http.MethodGet, "/api/v1/me", demoToken, nil, nil), http.StatusOK)
	})

	t.Run("bookmarks pagination ownership and moderation state machine", func(t *testing.T) {
		h.resetAndSeed(t)
		h.logs.Reset()
		ownerToken := h.token(t, "owner")
		reporterOneToken := h.token(t, "reporter-one")
		reporterTwoToken := h.token(t, "reporter-two")
		moderatorToken := h.token(t, "moderator", "moderator")
		adminToken := h.token(t, "admin", "admin", "moderator")

		first := h.createBookmark(t, ownerToken, "First public bookmark", []string{" Go ", "go", "echo"}, "public")
		time.Sleep(time.Millisecond)
		second := h.createBookmark(t, ownerToken, "Second public bookmark", []string{"go"}, "public")
		privateBookmark := h.createBookmark(t, ownerToken, "Private bookmark", nil, "private")
		if len(first.Tags) != 2 || first.Tags[0] != "echo" || first.Tags[1] != "go" {
			t.Fatalf("normalized response tags = %v", first.Tags)
		}
		masked := h.do(t, http.MethodGet, "/api/v1/bookmarks/"+privateBookmark.ID.String(), reporterOneToken, nil, nil)
		requireStatus(t, masked, http.StatusNotFound)

		v1 := h.do(t, http.MethodGet, "/api/v1/bookmarks?visibility=public&tag=go", "", nil, nil)
		requireStatus(t, v1, http.StatusOK)
		if v1.Header().Get("Deprecation") == "" || v1.Header().Get("Sunset") == "" ||
			v1.Header().Get("Link") != `</api/v2/bookmarks>; rel="successor-version"` {
			t.Fatalf("v1 deprecation headers are incomplete: %v", v1.Header())
		}

		pageOne := h.do(t, http.MethodGet, "/api/v2/bookmarks?visibility=public&size=1", "", nil, nil)
		requireStatus(t, pageOne, http.StatusOK)
		var firstPage struct {
			Items      []bookmarkWire `json:"items"`
			NextCursor string         `json:"nextCursor"`
		}
		decodeResponse(t, pageOne, &firstPage)
		if len(firstPage.Items) != 1 || firstPage.Items[0].ID != second.ID || firstPage.NextCursor == "" {
			t.Fatalf("first cursor page = %+v", firstPage)
		}
		time.Sleep(time.Millisecond)
		newest := h.createBookmark(t, ownerToken, "Inserted between pages", []string{"go"}, "public")
		pageTwo := h.do(t, http.MethodGet,
			"/api/v2/bookmarks?visibility=public&size=1&cursor="+url.QueryEscape(firstPage.NextCursor), "", nil, nil)
		requireStatus(t, pageTwo, http.StatusOK)
		var secondPage struct {
			Items      []bookmarkWire `json:"items"`
			NextCursor string         `json:"nextCursor"`
		}
		decodeResponse(t, pageTwo, &secondPage)
		if len(secondPage.Items) != 1 || secondPage.Items[0].ID != first.ID || secondPage.NextCursor != "" {
			t.Fatalf("cursor page shifted after concurrent insert: first=%+v second=%+v inserted=%s", firstPage.Items, secondPage.Items, newest.ID)
		}

		const privateComment = "free-form report comment must not enter diagnostics"
		reportOne := h.createReport(t, reporterOneToken, first.ID, "spam", privateComment)
		reportTwo := h.createReport(t, reporterTwoToken, first.ID, "offensive", "another private comment")
		duplicate := h.do(t, http.MethodPost, "/api/v1/bookmarks/"+first.ID.String()+"/reports", reporterOneToken,
			map[string]any{"reason": "other"}, nil)
		requireStatus(t, duplicate, http.StatusConflict)
		otherReporterUpdate := h.do(t, http.MethodPut, "/api/v1/reports/"+reportOne.ID.String(), reporterTwoToken,
			map[string]any{"reason": "broken-link"}, nil)
		requireStatus(t, otherReporterUpdate, http.StatusNotFound)
		const replacementComment = "replacement report comment must also stay out of diagnostics"
		updated := h.do(t, http.MethodPut, "/api/v1/reports/"+reportOne.ID.String(), reporterOneToken,
			map[string]any{"reason": "broken-link", "comment": replacementComment}, nil)
		requireStatus(t, updated, http.StatusOK)
		var updatedReport reportWire
		decodeResponse(t, updated, &updatedReport)
		if updatedReport.Reason != "broken-link" || updatedReport.Comment == nil || *updatedReport.Comment != replacementComment {
			t.Fatalf("report update did not persist its replacement fields: %+v", updatedReport)
		}

		const resolutionNote = "sensitive resolution note stored in audit and report state only"
		actioned := h.do(t, http.MethodPut, "/api/v1/admin/reports/"+reportOne.ID.String(), moderatorToken,
			map[string]any{"resolution": "actioned", "note": resolutionNote}, nil)
		requireStatus(t, actioned, http.StatusOK)
		var resolved reportWire
		decodeResponse(t, actioned, &resolved)
		if resolved.Status != "actioned" || resolved.ResolvedBy == nil || *resolved.ResolvedBy != "moderator" {
			t.Fatalf("resolved report = %+v", resolved)
		}
		if got := h.queryInt64(t,
			"select count(*) from reports where bookmark_id = $1 and status = 'actioned' and resolved_by = 'moderator' and resolution_note = $2",
			first.ID, resolutionNote); got != 2 {
			t.Fatalf("auto-resolved reports = %d, want 2", got)
		}
		for _, reportID := range []uuid.UUID{reportOne.ID, reportTwo.ID} {
			if got := h.queryInt64(t,
				"select count(*) from audit_entries where action = 'report.resolved' and target_id = $1", reportID.String()); got != 1 {
				t.Fatalf("report %s resolution audit count = %d, want 1", reportID, got)
			}
		}
		if got := h.queryInt64(t,
			"select count(*) from audit_entries where action = 'bookmark.status-changed' and target_id = $1", first.ID.String()); got != 1 {
			t.Fatalf("automatic bookmark-hide audit count = %d, want 1", got)
		}
		if got := h.queryInt64(t, "select count(*) from bookmarks where id = $1 and status = 'hidden'", first.ID); got != 1 {
			t.Fatal("actioned report did not hide its bookmark")
		}
		requireStatus(t, h.do(t, http.MethodGet, "/api/v1/bookmarks/"+first.ID.String(), "", nil, nil), http.StatusNotFound)
		ownerRead := h.do(t, http.MethodGet, "/api/v1/bookmarks/"+first.ID.String(), ownerToken, nil, nil)
		requireStatus(t, ownerRead, http.StatusOK)
		var hidden bookmarkWire
		decodeResponse(t, ownerRead, &hidden)
		if hidden.Status != "hidden" {
			t.Fatalf("owner hidden bookmark status = %q", hidden.Status)
		}
		hiddenPublish := h.do(t, http.MethodPut, "/api/v1/bookmarks/"+first.ID.String(), ownerToken, map[string]any{
			"url": "https://example.com/hidden", "title": "Still hidden", "tags": []string{"go"}, "visibility": "public",
		}, nil)
		requireStatus(t, hiddenPublish, http.StatusConflict)

		reopened := h.do(t, http.MethodPut, "/api/v1/admin/reports/"+reportOne.ID.String(), moderatorToken,
			map[string]any{"resolution": "open", "note": "must be ignored"}, nil)
		requireStatus(t, reopened, http.StatusOK)
		var reopenedReport reportWire
		decodeResponse(t, reopened, &reopenedReport)
		if reopenedReport.Status != "open" || reopenedReport.ResolvedBy != nil || reopenedReport.ResolvedAt != nil || reopenedReport.ResolutionNote != nil {
			t.Fatalf("reopened report retained resolution fields: %+v", reopenedReport)
		}
		if got := h.queryInt64(t, "select count(*) from bookmarks where id = $1 and status = 'hidden'", first.ID); got != 1 {
			t.Fatal("reopening a report restored the bookmark implicitly")
		}
		dismissed := h.do(t, http.MethodPut, "/api/v1/admin/reports/"+reportOne.ID.String(), moderatorToken,
			map[string]any{"resolution": "dismissed", "note": "sensitive dismissal note"}, nil)
		requireStatus(t, dismissed, http.StatusOK)
		var dismissedReport reportWire
		decodeResponse(t, dismissed, &dismissedReport)
		if dismissedReport.Status != "dismissed" || dismissedReport.ResolvedBy == nil || *dismissedReport.ResolvedBy != "moderator" {
			t.Fatalf("dismissed report = %+v", dismissedReport)
		}
		if got := h.queryInt64(t, "select count(*) from bookmarks where id = $1 and status = 'hidden'", first.ID); got != 1 {
			t.Fatal("dismissing a formerly actioned report restored the bookmark implicitly")
		}
		const revisionNote = "sensitive revised action note"
		revisedAction := h.do(t, http.MethodPut, "/api/v1/admin/reports/"+reportOne.ID.String(), moderatorToken,
			map[string]any{"resolution": "actioned", "note": revisionNote}, nil)
		requireStatus(t, revisedAction, http.StatusOK)
		var revisedReport reportWire
		decodeResponse(t, revisedAction, &revisedReport)
		if revisedReport.Status != "actioned" || revisedReport.ResolutionNote == nil || *revisedReport.ResolutionNote != revisionNote {
			t.Fatalf("dismissed-to-actioned revision = %+v", revisedReport)
		}
		secondReopen := h.do(t, http.MethodPut, "/api/v1/admin/reports/"+reportOne.ID.String(), moderatorToken,
			map[string]any{"resolution": "open", "note": "ignored again"}, nil)
		requireStatus(t, secondReopen, http.StatusOK)
		withdrawn := h.do(t, http.MethodDelete, "/api/v1/reports/"+reportOne.ID.String(), reporterOneToken, nil, nil)
		requireStatus(t, withdrawn, http.StatusNoContent)
		if got := h.queryInt64(t, "select count(*) from reports where id = $1", reportOne.ID); got != 0 {
			t.Fatalf("withdrawn report remains in the database: %d", got)
		}

		const restoreNote = "sensitive manual restore note"
		restored := h.do(t, http.MethodPut, "/api/v1/admin/bookmarks/"+first.ID.String()+"/status", moderatorToken,
			map[string]any{"status": "active", "note": restoreNote}, nil)
		requireStatus(t, restored, http.StatusOK)
		requireStatus(t, h.do(t, http.MethodGet, "/api/v1/bookmarks/"+first.ID.String(), "", nil, nil), http.StatusOK)
		if got := h.queryInt64(t,
			"select count(*) from audit_entries where action = 'report.resolved' and target_id = $1", reportOne.ID.String()); got != 3 {
			t.Fatalf("primary report resolution/revision audit count = %d, want 3", got)
		}
		if got := h.queryInt64(t,
			"select count(*) from audit_entries where action = 'report.reopened' and target_id = $1", reportOne.ID.String()); got != 2 {
			t.Fatalf("primary report reopen audit count = %d, want 2", got)
		}
		if got := h.queryInt64(t,
			"select count(*) from audit_entries where action = 'bookmark.status-changed' and target_id = $1", first.ID.String()); got != 2 {
			t.Fatalf("bookmark hide/restore audit count = %d, want 2", got)
		}

		auditLog := h.do(t, http.MethodGet,
			"/api/v1/admin/audit-log?action=report.resolved&targetId="+url.QueryEscape(reportTwo.ID.String()), adminToken, nil, nil)
		requireStatus(t, auditLog, http.StatusOK)
		var auditPage struct {
			Items []struct {
				Action   string `json:"action"`
				TargetID string `json:"targetId"`
			} `json:"items"`
		}
		decodeResponse(t, auditLog, &auditPage)
		if len(auditPage.Items) != 1 || auditPage.Items[0].Action != "report.resolved" || auditPage.Items[0].TargetID != reportTwo.ID.String() {
			t.Fatalf("filtered audit page = %+v", auditPage.Items)
		}

		logged := h.logs.String()
		requireStructuredLogEvent(t, logged, "report_created", "success", "INFO",
			map[string]string{"actor": "reporter-one", "resource_type": "report"})
		requireStructuredLogEvent(t, logged, "report_updated", "success", "INFO",
			map[string]string{"actor": "reporter-one", "resource_type": "report"})
		requireStructuredLogEvent(t, logged, "report_resolved", "success", "INFO",
			map[string]string{"actor": "moderator", "resource_type": "report"})
		requireStructuredLogEvent(t, logged, "report_reopened", "success", "INFO",
			map[string]string{"actor": "moderator", "resource_type": "report"})
		requireStructuredLogEvent(t, logged, "report_withdrawn", "success", "INFO",
			map[string]string{"actor": "reporter-one", "resource_type": "report"})
		requireStructuredLogEvent(t, logged, "bookmark_status_changed", "success", "INFO",
			map[string]string{"actor": "moderator", "resource_type": "bookmark"})
		for _, privateValue := range []string{privateComment, replacementComment, "another private comment", resolutionNote, "sensitive dismissal note", revisionNote, restoreNote} {
			if strings.Contains(logged, privateValue) {
				t.Fatalf("moderation diagnostics leaked private comment/note %q: %s", privateValue, logged)
			}
		}
	})

	t.Run("message persistence caching seed idempotency and audit", func(t *testing.T) {
		h.resetAndSeed(t)
		h.logs.Reset()
		adminToken := h.token(t, "admin", "admin", "moderator")

		before := h.do(t, http.MethodGet, "/api/v1/messages/bundle?lang=pl", "", nil, nil)
		requireStatus(t, before, http.StatusOK)
		beforeETag := before.Header().Get("ETag")
		if beforeETag == "" || before.Header().Get("Cache-Control") != "no-cache" || before.Header().Get("Content-Language") != "pl" {
			t.Fatalf("message bundle caching headers = %v", before.Header())
		}
		listBefore := h.do(t, http.MethodGet, "/api/v1/messages?key=ui.integration.only-en", "", nil, nil)
		requireStatus(t, listBefore, http.StatusOK)
		listBeforeETag := listBefore.Header().Get("ETag")
		if listBeforeETag == "" {
			t.Fatal("message list did not carry an ETag")
		}

		const privateText = "translation text that must never enter diagnostics"
		const privateDescription = "translator-only context"
		created := h.do(t, http.MethodPost, "/api/v1/messages", adminToken, map[string]any{
			"key": "ui.integration.only-en", "language": "en", "text": privateText, "description": privateDescription,
		}, nil)
		requireStatus(t, created, http.StatusCreated)
		var createdMessage messageWire
		decodeResponse(t, created, &createdMessage)
		if created.Header().Get("Location") != "/api/v1/messages/"+createdMessage.ID.String() {
			t.Fatalf("message Location = %q", created.Header().Get("Location"))
		}
		duplicate := h.do(t, http.MethodPost, "/api/v1/messages", adminToken, map[string]any{
			"key": "ui.integration.only-en", "language": "en", "text": "duplicate",
		}, nil)
		requireStatus(t, duplicate, http.StatusConflict)

		after := h.do(t, http.MethodGet, "/api/v1/messages/bundle?lang=pl", "", nil, nil)
		requireStatus(t, after, http.StatusOK)
		afterETag := after.Header().Get("ETag")
		if afterETag == "" || afterETag == beforeETag {
			t.Fatalf("message write did not change bundle ETag: before=%q after=%q", beforeETag, afterETag)
		}
		listAfter := h.do(t, http.MethodGet, "/api/v1/messages?key=ui.integration.only-en", "", nil, nil)
		requireStatus(t, listAfter, http.StatusOK)
		if listAfter.Header().Get("ETag") == "" || listAfter.Header().Get("ETag") == listBeforeETag {
			t.Fatalf("message create did not invalidate list ETag: before=%q after=%q", listBeforeETag, listAfter.Header().Get("ETag"))
		}
		var bundle struct {
			Language string            `json:"language"`
			Messages map[string]string `json:"messages"`
		}
		decodeResponse(t, after, &bundle)
		if bundle.Language != "pl" || bundle.Messages["ui.integration.only-en"] != privateText {
			t.Fatalf("per-key English fallback missing from Polish bundle: %+v", bundle)
		}
		notModified := h.do(t, http.MethodGet, "/api/v1/messages/bundle?lang=pl", "", nil,
			map[string]string{"If-None-Match": afterETag})
		requireStatus(t, notModified, http.StatusNotModified)
		if notModified.Body.Len() != 0 {
			t.Fatalf("304 bundle body = %q", notModified.Body.String())
		}

		readCreated := h.do(t, http.MethodGet, "/api/v1/messages/"+createdMessage.ID.String(), "", nil, nil)
		requireStatus(t, readCreated, http.StatusOK)
		createdETag := readCreated.Header().Get("ETag")
		if createdETag == "" {
			t.Fatal("message item did not carry an ETag")
		}
		const privateUpdatedText = "updated translation text that must stay out of diagnostics"
		updatedCreated := h.do(t, http.MethodPut, "/api/v1/messages/"+createdMessage.ID.String(), adminToken, map[string]any{
			"key": "ui.integration.only-en", "language": "en", "text": privateUpdatedText, "description": privateDescription,
		}, nil)
		requireStatus(t, updatedCreated, http.StatusOK)
		readUpdated := h.do(t, http.MethodGet, "/api/v1/messages/"+createdMessage.ID.String(), "", nil, nil)
		requireStatus(t, readUpdated, http.StatusOK)
		if readUpdated.Header().Get("ETag") == "" || readUpdated.Header().Get("ETag") == createdETag {
			t.Fatalf("message update did not invalidate item ETag: before=%q after=%q", createdETag, readUpdated.Header().Get("ETag"))
		}
		var updatedMessage messageWire
		decodeResponse(t, readUpdated, &updatedMessage)
		if updatedMessage.Text != privateUpdatedText {
			t.Fatalf("updated message text = %q", updatedMessage.Text)
		}

		var seededID uuid.UUID
		if err := h.pool.QueryRow(context.Background(),
			"select id from messages where key = 'validation.title.required' and language = 'en'",
		).Scan(&seededID); err != nil {
			t.Fatalf("find seeded message: %v", err)
		}
		const runtimeOverride = "Runtime-edited title validation"
		updatedSeed := h.do(t, http.MethodPut, "/api/v1/messages/"+seededID.String(), adminToken, map[string]any{
			"key": "validation.title.required", "language": "en", "text": runtimeOverride,
		}, nil)
		requireStatus(t, updatedSeed, http.StatusOK)
		if err := h.messages.Seed(context.Background(), h.seedMessages, h.logger); err != nil {
			t.Fatalf("repeat message seed: %v", err)
		}
		var persistedOverride string
		if err := h.pool.QueryRow(context.Background(),
			"select text from messages where id = $1", seededID,
		).Scan(&persistedOverride); err != nil {
			t.Fatalf("read runtime-edited seed: %v", err)
		}
		if persistedOverride != runtimeOverride {
			t.Fatalf("repeat seed overwrote runtime edit: %q", persistedOverride)
		}

		listBeforeDelete := h.do(t, http.MethodGet, "/api/v1/messages?key=ui.integration.only-en", "", nil, nil)
		requireStatus(t, listBeforeDelete, http.StatusOK)
		deleted := h.do(t, http.MethodDelete, "/api/v1/messages/"+createdMessage.ID.String(), adminToken, nil, nil)
		requireStatus(t, deleted, http.StatusNoContent)
		requireStatus(t, h.do(t, http.MethodGet, "/api/v1/messages/"+createdMessage.ID.String(), "", nil, nil), http.StatusNotFound)
		listAfterDelete := h.do(t, http.MethodGet, "/api/v1/messages?key=ui.integration.only-en", "", nil, nil)
		requireStatus(t, listAfterDelete, http.StatusOK)
		if listAfterDelete.Header().Get("ETag") == "" || listAfterDelete.Header().Get("ETag") == listBeforeDelete.Header().Get("ETag") {
			t.Fatalf("message delete did not invalidate list ETag: before=%q after=%q",
				listBeforeDelete.Header().Get("ETag"), listAfterDelete.Header().Get("ETag"))
		}
		for _, expected := range []struct {
			action   string
			targetID uuid.UUID
		}{
			{action: "message.created", targetID: createdMessage.ID},
			{action: "message.updated", targetID: createdMessage.ID},
			{action: "message.updated", targetID: seededID},
			{action: "message.deleted", targetID: createdMessage.ID},
		} {
			if got := h.queryInt64(t,
				"select count(*) from audit_entries where action = $1 and target_id = $2", expected.action, expected.targetID.String()); got != 1 {
				t.Fatalf("audit %s/%s count = %d, want 1", expected.action, expected.targetID, got)
			}
		}

		auditLog := h.do(t, http.MethodGet,
			"/api/v1/admin/audit-log?action=message.created&targetId="+createdMessage.ID.String(), adminToken, nil, nil)
		requireStatus(t, auditLog, http.StatusOK)
		var page struct {
			Items []json.RawMessage `json:"items"`
		}
		decodeResponse(t, auditLog, &page)
		if len(page.Items) != 1 {
			t.Fatalf("message-created audit entries = %d, want 1", len(page.Items))
		}

		logged := h.logs.String()
		for _, event := range []string{"message_created", "message_updated", "message_deleted"} {
			requireStructuredLogEvent(t, logged, event, "success", "INFO",
				map[string]string{"actor": "admin", "resource_type": "message"})
		}
		requireStructuredLogEvent(t, logged, "message_seed_imported", "success", "INFO", nil)
		if strings.Contains(logged, privateText) || strings.Contains(logged, privateUpdatedText) ||
			strings.Contains(logged, privateDescription) || strings.Contains(logged, runtimeOverride) {
			t.Fatalf("message diagnostics leaked text or translator context: %s", logged)
		}
	})

	t.Run("stats audit filters and Echo error mapping", func(t *testing.T) {
		h.resetAndSeed(t)
		ownerToken := h.token(t, "owner")
		otherOwnerToken := h.token(t, "other-owner")
		reporterToken := h.token(t, "reporter")
		moderatorToken := h.token(t, "moderator", "moderator")
		adminToken := h.token(t, "admin", "admin", "moderator")
		first := h.createBookmark(t, ownerToken, "Go stats one", []string{"go", "echo"}, "public")
		h.createBookmark(t, ownerToken, "Go stats two", []string{"go"}, "private")
		third := h.createBookmark(t, otherOwnerToken, "Go stats three", []string{"go"}, "public")
		h.createReport(t, reporterToken, third.ID, "other", "stats fixture report")
		requireStatus(t, h.do(t, http.MethodPut, "/api/v1/admin/bookmarks/"+first.ID.String()+"/status", moderatorToken,
			map[string]any{"status": "hidden", "note": "stats fixture"}, nil), http.StatusOK)

		dayBefore := time.Now().UTC().Format(time.DateOnly)
		stats := h.do(t, http.MethodGet, "/api/v1/admin/stats", moderatorToken, nil, nil)
		dayAfter := time.Now().UTC().Format(time.DateOnly)
		requireStatus(t, stats, http.StatusOK)
		if stats.Header().Get("Cache-Control") != "no-cache" || stats.Header().Get("ETag") == "" {
			t.Fatalf("stats caching headers = %v", stats.Header())
		}
		var body struct {
			Totals struct {
				Users           int64 `json:"users"`
				Bookmarks       int64 `json:"bookmarks"`
				PublicBookmarks int64 `json:"publicBookmarks"`
				HiddenBookmarks int64 `json:"hiddenBookmarks"`
				OpenReports     int64 `json:"openReports"`
			} `json:"totals"`
			Daily []struct {
				Date             string `json:"date"`
				BookmarksCreated int64  `json:"bookmarksCreated"`
				ActiveUsers      int64  `json:"activeUsers"`
			} `json:"daily"`
			TopTags []struct {
				Tag   string `json:"tag"`
				Count int64  `json:"count"`
			} `json:"topTags"`
		}
		decodeResponse(t, stats, &body)
		if body.Totals.Bookmarks != 3 || body.Totals.PublicBookmarks != 2 || body.Totals.HiddenBookmarks != 1 ||
			body.Totals.OpenReports != 1 || body.Totals.Users < 4 {
			t.Fatalf("unexpected stats totals: %+v", body.Totals)
		}
		if len(body.Daily) != 30 {
			t.Fatalf("daily stats length = %d, want 30", len(body.Daily))
		}
		bookmarksCreated := int64(0)
		activeUsers := int64(0)
		zeroBookmarkDays := 0
		var previousDay time.Time
		for i, daily := range body.Daily {
			day, err := time.Parse(time.DateOnly, daily.Date)
			if err != nil {
				t.Fatalf("daily[%d] date %q is invalid: %v", i, daily.Date, err)
			}
			if i > 0 && !day.Equal(previousDay.AddDate(0, 0, 1)) {
				t.Fatalf("daily dates are not consecutive at %d: previous=%s current=%s", i, previousDay, day)
			}
			previousDay = day
			bookmarksCreated += daily.BookmarksCreated
			activeUsers += daily.ActiveUsers
			if daily.BookmarksCreated == 0 {
				zeroBookmarkDays++
			}
		}
		lastDate := body.Daily[len(body.Daily)-1].Date
		if lastDate != dayBefore && lastDate != dayAfter {
			t.Fatalf("daily series ends on %q, request UTC day was %q..%q", lastDate, dayBefore, dayAfter)
		}
		if bookmarksCreated != 3 || activeUsers < 4 || zeroBookmarkDays < 28 {
			t.Fatalf("daily aggregates bookmarks=%d activeUsers=%d zeroBookmarkDays=%d", bookmarksCreated, activeUsers, zeroBookmarkDays)
		}
		if len(body.TopTags) < 2 || body.TopTags[0].Tag != "go" || body.TopTags[0].Count != 3 {
			t.Fatalf("top tags = %+v", body.TopTags)
		}
		stats304 := h.do(t, http.MethodGet, "/api/v1/admin/stats", moderatorToken, nil,
			map[string]string{"If-None-Match": stats.Header().Get("ETag")})
		requireStatus(t, stats304, http.StatusNotModified)

		invalidTime := h.do(t, http.MethodGet, "/api/v1/admin/audit-log?from=not-a-time", adminToken, nil, nil)
		requireStatus(t, invalidTime, http.StatusBadRequest)
		validRange := h.do(t, http.MethodGet,
			"/api/v1/admin/audit-log?from="+url.QueryEscape(time.Now().UTC().Add(-time.Hour).Format(time.RFC3339Nano))+"&size=1",
			adminToken, nil, nil)
		requireStatus(t, validRange, http.StatusOK)

		methodNotAllowed := h.do(t, http.MethodPatch, "/healthz", "", nil, nil)
		requireStatus(t, methodNotAllowed, http.StatusMethodNotAllowed)
		if methodNotAllowed.Header().Get("Content-Type") != "application/problem+json" {
			t.Fatalf("Echo 405 content type = %q", methodNotAllowed.Header().Get("Content-Type"))
		}
	})
}

func newIntegrationHarness(t *testing.T) *integrationHarness {
	t.Helper()
	dsn := os.Getenv(integrationDatabaseEnv)
	if dsn == "" {
		t.Skipf("set %s to run PostgreSQL-backed integration tests", integrationDatabaseEnv)
	}

	privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("generate integration RSA key: %v", err)
	}
	var issuerServer *httptest.Server
	issuerServer = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/jwks" {
			http.NotFound(w, r)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"keys": []any{map[string]string{
			"kty": "RSA",
			"kid": "integration",
			"use": "sig",
			"n":   base64.RawURLEncoding.EncodeToString(privateKey.N.Bytes()),
			"e":   base64.RawURLEncoding.EncodeToString(big.NewInt(int64(privateKey.E)).Bytes()),
		}}})
	}))
	t.Cleanup(issuerServer.Close)

	logs := &bytes.Buffer{}
	logger := slog.New(slog.NewJSONHandler(logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	pool, err := store.Open(ctx, dsn, logger)
	if err != nil {
		t.Fatalf("open integration database: %v", err)
	}
	t.Cleanup(pool.Close)
	handler, messageStore := New(config.Config{
		IssuerURI: issuerServer.URL,
		JWKSURI:   issuerServer.URL + "/jwks",
	}, pool, logger)

	_, sourceFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("resolve integration test source path")
	}
	seedDir := filepath.Clean(filepath.Join(filepath.Dir(sourceFile), "..", "..", "..", "..", "spec", "messages"))
	return &integrationHarness{
		handler: handler, pool: pool, messages: messageStore, privateKey: privateKey,
		issuer: issuerServer.URL, logs: logs, logger: logger, seedMessages: seedDir,
	}
}

func (h *integrationHarness) resetAndSeed(t *testing.T) {
	t.Helper()
	_, err := h.pool.Exec(context.Background(),
		"truncate table audit_entries, reports, messages, bookmarks, user_accounts restart identity cascade")
	if err != nil {
		t.Fatalf("reset integration database: %v", err)
	}
	if err := h.messages.Seed(context.Background(), h.seedMessages, h.logger); err != nil {
		t.Fatalf("seed integration messages: %v", err)
	}
}

func (h *integrationHarness) token(t *testing.T, username string, roles ...string) string {
	t.Helper()
	token := jwt.NewWithClaims(jwt.SigningMethodRS256, jwt.MapClaims{
		"iss":                h.issuer,
		"aud":                config.Audience,
		"exp":                time.Now().Add(time.Hour).Unix(),
		"preferred_username": username,
		"name":               strings.ToUpper(username[:1]) + username[1:],
		"email":              username + "@example.com",
		"realm_access":       map[string]any{"roles": roles},
	})
	token.Header["kid"] = "integration"
	signed, err := token.SignedString(h.privateKey)
	if err != nil {
		t.Fatalf("sign token for %s: %v", username, err)
	}
	return signed
}

func (h *integrationHarness) do(
	t *testing.T,
	method, path, bearer string,
	body any,
	headers map[string]string,
) *httptest.ResponseRecorder {
	t.Helper()
	var reader io.Reader
	if body != nil {
		if raw, ok := body.(rawJSON); ok {
			reader = strings.NewReader(string(raw))
		} else {
			encoded, err := json.Marshal(body)
			if err != nil {
				t.Fatalf("encode request body: %v", err)
			}
			reader = bytes.NewReader(encoded)
		}
	}
	request := httptest.NewRequest(method, path, reader)
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	if bearer != "" {
		request.Header.Set("Authorization", "Bearer "+bearer)
	}
	for name, value := range headers {
		request.Header.Set(name, value)
	}
	response := httptest.NewRecorder()
	h.handler.ServeHTTP(response, request)
	return response
}

func (h *integrationHarness) createBookmark(
	t *testing.T,
	token, title string,
	tags []string,
	visibility string,
) bookmarkWire {
	t.Helper()
	response := h.do(t, http.MethodPost, "/api/v1/bookmarks", token, map[string]any{
		"url":   "https://example.com/" + url.PathEscape(strings.ToLower(strings.ReplaceAll(title, " ", "-"))),
		"title": title, "tags": tags, "visibility": visibility, "unknownField": "ignored",
	}, nil)
	requireStatus(t, response, http.StatusCreated)
	var bookmark bookmarkWire
	decodeResponse(t, response, &bookmark)
	if bookmark.ID == uuid.Nil || bookmark.Owner == "" || bookmark.Visibility != visibility {
		t.Fatalf("unexpected created bookmark: %+v", bookmark)
	}
	return bookmark
}

func (h *integrationHarness) createReport(
	t *testing.T,
	token string,
	bookmarkID uuid.UUID,
	reason, comment string,
) reportWire {
	t.Helper()
	response := h.do(t, http.MethodPost, "/api/v1/bookmarks/"+bookmarkID.String()+"/reports", token,
		map[string]any{"reason": reason, "comment": comment}, nil)
	requireStatus(t, response, http.StatusCreated)
	var report reportWire
	decodeResponse(t, response, &report)
	if report.ID == uuid.Nil || report.BookmarkID != bookmarkID || report.Status != "open" {
		t.Fatalf("unexpected created report: %+v", report)
	}
	return report
}

func (h *integrationHarness) queryInt64(t *testing.T, sql string, args ...any) int64 {
	t.Helper()
	var value int64
	if err := h.pool.QueryRow(context.Background(), sql, args...).Scan(&value); err != nil {
		t.Fatalf("query scalar %q: %v", sql, err)
	}
	return value
}

func requireStatus(t *testing.T, response *httptest.ResponseRecorder, want int) {
	t.Helper()
	if response.Code != want {
		t.Fatalf("status = %d, want %d; content-type=%q body=%s",
			response.Code, want, response.Header().Get("Content-Type"), response.Body.String())
	}
}

func decodeResponse(t *testing.T, response *httptest.ResponseRecorder, target any) {
	t.Helper()
	if err := json.Unmarshal(response.Body.Bytes(), target); err != nil {
		t.Fatalf("decode response status=%d body=%q: %v", response.Code, response.Body.String(), err)
	}
}

func requireStructuredLogEvent(
	t *testing.T,
	logs, event, outcome, level string,
	required map[string]string,
) {
	t.Helper()
	for _, line := range strings.Split(strings.TrimSpace(logs), "\n") {
		var record map[string]any
		if err := json.Unmarshal([]byte(line), &record); err != nil || record["event"] != event {
			continue
		}
		if record["outcome"] != outcome || record["level"] != level {
			t.Fatalf("event %q outcome/level = %v/%v, want %s/%s: %v",
				event, record["outcome"], record["level"], outcome, level, record)
		}
		for key, want := range required {
			if got := record[key]; got != want {
				t.Fatalf("event %q field %q = %v, want %q: %v", event, key, got, want, record)
			}
		}
		if resourceType, ok := required["resource_type"]; ok && resourceType != "" {
			if resourceID, _ := record["resource_id"].(string); resourceID == "" {
				t.Fatalf("event %q omitted resource_id: %v", event, record)
			}
		}
		return
	}
	t.Fatalf("structured event %q not found in logs: %s", event, logs)
}
