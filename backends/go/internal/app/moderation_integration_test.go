package app

import (
	"net/http"
	"strings"
	"testing"
)

func TestIntegrationModerationLifecycleAndPrivacy(t *testing.T) {
	h := newIntegrationHarness(t)
	alice := h.token(t, "alice")
	bob := h.token(t, "bob")
	charlie := h.token(t, "charlie")
	moderator := h.token(t, "morgan", "moderator")

	publicBookmark := createBookmark(t, h, alice, "Reported Public", "public", []string{"go", "moderation"})
	privateBookmark := createBookmark(t, h, alice, "Private Mask", "private", []string{"go"})

	privateReport := h.do(t, http.MethodPost, "/api/v1/bookmarks/"+privateBookmark.ID+"/reports", bob,
		map[string]any{"reason": "spam"})
	requireProblemStatus(t, privateReport, http.StatusNotFound)
	invalidReport := h.do(t, http.MethodPost, "/api/v1/bookmarks/"+publicBookmark.ID+"/reports", bob,
		map[string]any{"reason": "not-a-reason"})
	requireProblemStatus(t, invalidReport, http.StatusBadRequest)

	bobComment := "private reporter comment must stay out of diagnostics"
	bobReportResponse := h.do(t, http.MethodPost, "/api/v1/bookmarks/"+publicBookmark.ID+"/reports", bob,
		map[string]any{"reason": "spam", "comment": bobComment})
	requireStatus(t, bobReportResponse, http.StatusCreated)
	bobReport := decodeResponse[reportDocument](t, bobReportResponse)

	duplicate := h.do(t, http.MethodPost, "/api/v1/bookmarks/"+publicBookmark.ID+"/reports", bob,
		map[string]any{"reason": "other"})
	requireProblemStatus(t, duplicate, http.StatusConflict)

	charlieComment := "another reporter secret"
	charlieResponse := h.do(t, http.MethodPost, "/api/v1/bookmarks/"+publicBookmark.ID+"/reports", charlie,
		map[string]any{"reason": "offensive", "comment": charlieComment})
	requireStatus(t, charlieResponse, http.StatusCreated)
	charlieReport := decodeResponse[reportDocument](t, charlieResponse)

	mine := h.do(t, http.MethodGet, "/api/v1/reports?status=open", bob, nil)
	requireStatus(t, mine, http.StatusOK)
	if page := decodeResponse[pageDocument[reportDocument]](t, mine); page.TotalItems != 1 || page.Items[0].ID != bobReport.ID {
		t.Fatalf("reporter list = %+v", page)
	}

	foreignUpdate := h.do(t, http.MethodPut, "/api/v1/reports/"+bobReport.ID, alice,
		map[string]any{"reason": "other"})
	requireProblemStatus(t, foreignUpdate, http.StatusNotFound)
	updated := h.do(t, http.MethodPut, "/api/v1/reports/"+bobReport.ID, bob,
		map[string]any{"reason": "broken-link", "comment": bobComment})
	requireStatus(t, updated, http.StatusOK)
	if got := decodeResponse[reportDocument](t, updated); got.Reason != "broken-link" {
		t.Fatalf("updated report = %+v", got)
	}

	withdrawBookmark := createBookmark(t, h, alice, "Withdraw Target", "public", []string{"reports"})
	withdrawCreated := h.do(t, http.MethodPost, "/api/v1/bookmarks/"+withdrawBookmark.ID+"/reports", bob,
		map[string]any{"reason": "spam"})
	requireStatus(t, withdrawCreated, http.StatusCreated)
	withdrawID := decodeResponse[reportDocument](t, withdrawCreated).ID
	withdrawn := h.do(t, http.MethodDelete, "/api/v1/reports/"+withdrawID, bob, nil)
	requireStatus(t, withdrawn, http.StatusNoContent)
	recreated := h.do(t, http.MethodPost, "/api/v1/bookmarks/"+withdrawBookmark.ID+"/reports", bob,
		map[string]any{"reason": "other"})
	requireStatus(t, recreated, http.StatusCreated)

	regularQueue := h.do(t, http.MethodGet, "/api/v1/admin/reports", bob, nil)
	requireProblemStatus(t, regularQueue, http.StatusForbidden)
	queue := h.do(t, http.MethodGet, "/api/v1/admin/reports?status=open&size=10", moderator, nil)
	requireStatus(t, queue, http.StatusOK)
	if page := decodeResponse[pageDocument[reportDocument]](t, queue); page.TotalItems != 3 {
		t.Fatalf("moderation queue = %+v", page)
	}

	moderationNote := "sensitive moderation note must stay in audit only"
	actioned := h.do(t, http.MethodPut, "/api/v1/admin/reports/"+bobReport.ID, moderator,
		map[string]any{"resolution": "actioned", "note": moderationNote})
	requireStatus(t, actioned, http.StatusOK)
	if got := decodeResponse[reportDocument](t, actioned); got.Status != "actioned" || got.ResolvedBy == nil || *got.ResolvedBy != "morgan" {
		t.Fatalf("actioned report = %+v", got)
	}

	charlieMine := h.do(t, http.MethodGet, "/api/v1/reports?status=actioned", charlie, nil)
	requireStatus(t, charlieMine, http.StatusOK)
	charliePage := decodeResponse[pageDocument[reportDocument]](t, charlieMine)
	if charliePage.TotalItems != 1 || charliePage.Items[0].ID != charlieReport.ID || charliePage.Items[0].Status != "actioned" {
		t.Fatalf("auto-resolved sibling = %+v", charliePage)
	}

	hiddenPublic := h.do(t, http.MethodGet, "/api/v1/bookmarks/"+publicBookmark.ID, "", nil)
	requireProblemStatus(t, hiddenPublic, http.StatusNotFound)
	hiddenOwner := h.do(t, http.MethodGet, "/api/v1/bookmarks/"+publicBookmark.ID, alice, nil)
	requireStatus(t, hiddenOwner, http.StatusOK)
	if got := decodeResponse[bookmarkDocument](t, hiddenOwner); got.Status != "hidden" {
		t.Fatalf("owner hidden bookmark = %+v", got)
	}
	ownerRepublish := h.do(t, http.MethodPut, "/api/v1/bookmarks/"+publicBookmark.ID, alice,
		validBookmarkBody("Owner Cannot Republish", "public", []string{"go"}))
	requireProblemStatus(t, ownerRepublish, http.StatusConflict)

	resolvedUpdate := h.do(t, http.MethodPut, "/api/v1/reports/"+bobReport.ID, bob,
		map[string]any{"reason": "other"})
	requireProblemStatus(t, resolvedUpdate, http.StatusConflict)
	resolvedWithdraw := h.do(t, http.MethodDelete, "/api/v1/reports/"+bobReport.ID, bob, nil)
	requireProblemStatus(t, resolvedWithdraw, http.StatusConflict)

	reopened := h.do(t, http.MethodPut, "/api/v1/admin/reports/"+bobReport.ID, moderator,
		map[string]any{"resolution": "open", "note": "ignored while reopening"})
	requireStatus(t, reopened, http.StatusOK)
	if got := decodeResponse[reportDocument](t, reopened); got.Status != "open" || got.ResolvedBy != nil || got.ResolutionNote != nil {
		t.Fatalf("reopened report retained resolution fields: %+v", got)
	}
	stillHidden := h.do(t, http.MethodGet, "/api/v1/bookmarks/"+publicBookmark.ID, "", nil)
	requireProblemStatus(t, stillHidden, http.StatusNotFound)

	dismissed := h.do(t, http.MethodPut, "/api/v1/admin/reports/"+bobReport.ID, moderator,
		map[string]any{"resolution": "dismissed", "note": "dismissed"})
	requireStatus(t, dismissed, http.StatusOK)
	if got := decodeResponse[reportDocument](t, dismissed); got.Status != "dismissed" {
		t.Fatalf("dismissed report = %+v", got)
	}

	restored := h.do(t, http.MethodPut, "/api/v1/admin/bookmarks/"+publicBookmark.ID+"/status", moderator,
		map[string]any{"status": "active", "note": "explicit restore"})
	requireStatus(t, restored, http.StatusOK)
	if got := decodeResponse[bookmarkDocument](t, restored); got.Status != "active" || got.Visibility != "public" {
		t.Fatalf("restored bookmark = %+v", got)
	}

	newOpen := h.do(t, http.MethodPost, "/api/v1/bookmarks/"+publicBookmark.ID+"/reports", bob,
		map[string]any{"reason": "spam"})
	requireStatus(t, newOpen, http.StatusCreated)
	newOpenID := decodeResponse[reportDocument](t, newOpen).ID
	conflictingReopen := h.do(t, http.MethodPut, "/api/v1/admin/reports/"+bobReport.ID, moderator,
		map[string]any{"resolution": "open"})
	requireProblemStatus(t, conflictingReopen, http.StatusConflict)
	withdrawNew := h.do(t, http.MethodDelete, "/api/v1/reports/"+newOpenID, bob, nil)
	requireStatus(t, withdrawNew, http.StatusNoContent)

	reopenAgain := h.do(t, http.MethodPut, "/api/v1/admin/reports/"+bobReport.ID, moderator,
		map[string]any{"resolution": "open"})
	requireStatus(t, reopenAgain, http.StatusOK)
	actionAgain := h.do(t, http.MethodPut, "/api/v1/admin/reports/"+bobReport.ID, moderator,
		map[string]any{"resolution": "actioned", "note": moderationNote})
	requireStatus(t, actionAgain, http.StatusOK)
	restoreAgain := h.do(t, http.MethodPut, "/api/v1/admin/bookmarks/"+publicBookmark.ID+"/status", moderator,
		map[string]any{"status": "active"})
	requireStatus(t, restoreAgain, http.StatusOK)

	if strings.Contains(h.logs.String(), bobComment) || strings.Contains(h.logs.String(), charlieComment) || strings.Contains(h.logs.String(), moderationNote) {
		t.Fatalf("free-form moderation data leaked into diagnostic logs: %s", h.logs.String())
	}
	for _, event := range []string{"report_created", "report_updated", "report_withdrawn", "report_resolved", "report_reopened", "bookmark_status_changed"} {
		if !strings.Contains(h.logs.String(), `"event":"`+event+`"`) {
			t.Fatalf("missing moderation event %q in logs", event)
		}
	}

	var auditDetail string
	if err := h.pool.QueryRow(t.Context(),
		"select detail::text from audit_entries where action = 'report.resolved' and target_id = $1 and detail->>'resolution' = 'actioned' order by created_at limit 1",
		bobReport.ID).Scan(&auditDetail); err != nil {
		t.Fatalf("read authoritative moderation audit detail: %v", err)
	}
	if !strings.Contains(auditDetail, moderationNote) || !strings.Contains(auditDetail, `"autoResolved": false`) {
		t.Fatalf("audit detail did not retain resolution evidence: %s", auditDetail)
	}
}
