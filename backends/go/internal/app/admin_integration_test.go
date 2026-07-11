package app

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/kamkie/stackverse/backends/go/internal/messages"
)

func TestIntegrationMessagesAccountsStatsAndAudit(t *testing.T) {
	h := newIntegrationHarness(t)
	admin := h.token(t, "ada", "moderator", "admin")
	moderator := h.token(t, "morgan", "moderator")
	bob := h.token(t, "bob")

	initialBundle := h.do(t, http.MethodGet, "/api/v1/messages/bundle?lang=pl", "", nil)
	requireStatus(t, initialBundle, http.StatusOK)
	initialETag := initialBundle.Header.Get("ETag")
	if initialETag == "" || initialBundle.Header.Get("Cache-Control") != "no-cache" || initialBundle.Header.Get("Content-Language") != "pl" {
		t.Fatalf("bundle caching/language headers = %v", initialBundle.Header)
	}
	request304 := h.doWithHeader(t, http.MethodGet, "/api/v1/messages/bundle?lang=pl", "", nil,
		"If-None-Match", initialETag)
	requireStatus(t, request304, http.StatusNotModified)
	if len(request304.Body) != 0 {
		t.Fatalf("304 body = %q", request304.Body)
	}

	messageList := h.do(t, http.MethodGet, "/api/v1/messages?language=en&q=validation&page=0&size=5", "", nil)
	requireStatus(t, messageList, http.StatusOK)
	if page := decodeResponse[pageDocument[messageDocument]](t, messageList); page.TotalItems == 0 || len(page.Items) == 0 {
		t.Fatalf("seeded message search = %+v", page)
	}

	regularWrite := h.do(t, http.MethodPost, "/api/v1/messages", bob,
		map[string]any{"key": "custom.denied", "language": "en", "text": "denied"})
	requireProblemStatus(t, regularWrite, http.StatusForbidden)
	invalidMessage := h.do(t, http.MethodPost, "/api/v1/messages", admin,
		map[string]any{"key": "Invalid Key", "language": "english", "text": ""})
	requireProblemStatus(t, invalidMessage, http.StatusBadRequest)

	messageText := "sensitive message text belongs in the audit trail"
	messageDescription := "translator-only free-form description"
	createdResponse := h.do(t, http.MethodPost, "/api/v1/messages", admin, map[string]any{
		"key": "custom.integration", "language": "en", "text": messageText, "description": messageDescription,
	})
	requireStatus(t, createdResponse, http.StatusCreated)
	created := decodeResponse[messageDocument](t, createdResponse)
	if createdResponse.Header.Get("Location") != "/api/v1/messages/"+created.ID {
		t.Fatalf("message location = %q", createdResponse.Header.Get("Location"))
	}
	duplicate := h.do(t, http.MethodPost, "/api/v1/messages", admin,
		map[string]any{"key": "custom.integration", "language": "en", "text": "duplicate"})
	requireProblemStatus(t, duplicate, http.StatusConflict)

	publicMessage := h.do(t, http.MethodGet, "/api/v1/messages/"+created.ID, "", nil)
	requireStatus(t, publicMessage, http.StatusOK)
	if got := decodeResponse[messageDocument](t, publicMessage); got.Text != messageText || got.Description == nil || *got.Description != messageDescription {
		t.Fatalf("public message = %+v", got)
	}

	updatedText := "updated runtime text is also audit-only"
	updatedResponse := h.do(t, http.MethodPut, "/api/v1/messages/"+created.ID, admin, map[string]any{
		"key": "custom.integration", "language": "en", "text": updatedText, "description": messageDescription,
	})
	requireStatus(t, updatedResponse, http.StatusOK)
	if got := decodeResponse[messageDocument](t, updatedResponse); got.Text != updatedText {
		t.Fatalf("updated message = %+v", got)
	}
	afterWriteBundle := h.do(t, http.MethodGet, "/api/v1/messages/bundle?lang=pl", "", nil)
	requireStatus(t, afterWriteBundle, http.StatusOK)
	if afterWriteBundle.Header.Get("ETag") == initialETag {
		t.Fatalf("message write did not invalidate bundle ETag %q", initialETag)
	}
	var bundle struct {
		Language string            `json:"language"`
		Messages map[string]string `json:"messages"`
	}
	bundle = decodeResponse[struct {
		Language string            `json:"language"`
		Messages map[string]string `json:"messages"`
	}](t, afterWriteBundle)
	if bundle.Language != "pl" || bundle.Messages["custom.integration"] != updatedText {
		t.Fatalf("bundle fallback = %+v", bundle)
	}

	// Provision Bob and give him one bookmark so the admin directory exercises
	// lazy accounts and its derived bookmark count.
	me := h.do(t, http.MethodGet, "/api/v1/me", bob, nil)
	requireStatus(t, me, http.StatusOK)
	createBookmark(t, h, bob, "Bob Bookmark", "private", []string{"go", "admin"})
	adminMe := h.do(t, http.MethodGet, "/api/v1/me", admin, nil)
	requireStatus(t, adminMe, http.StatusOK)

	users := h.do(t, http.MethodGet, "/api/v1/admin/users?q=bo&status=active", admin, nil)
	requireStatus(t, users, http.StatusOK)
	userPage := decodeResponse[pageDocument[accountDocument]](t, users)
	if userPage.TotalItems != 1 || len(userPage.Items) != 1 || userPage.Items[0].Username != "bob" || userPage.Items[0].BookmarkCount != 1 {
		t.Fatalf("admin user search = %+v", userPage)
	}
	user := h.do(t, http.MethodGet, "/api/v1/admin/users/bob", admin, nil)
	requireStatus(t, user, http.StatusOK)

	missingStatus := h.do(t, http.MethodPut, "/api/v1/admin/users/bob/status", admin, map[string]any{})
	requireProblemStatus(t, missingStatus, http.StatusBadRequest)
	missingReason := h.do(t, http.MethodPut, "/api/v1/admin/users/bob/status", admin,
		map[string]any{"status": "blocked"})
	requireProblemStatus(t, missingReason, http.StatusBadRequest)
	selfBlock := h.do(t, http.MethodPut, "/api/v1/admin/users/ada/status", admin,
		map[string]any{"status": "blocked", "reason": "self"})
	requireProblemStatus(t, selfBlock, http.StatusConflict)

	blockReason := "sensitive account block reason"
	blocked := h.do(t, http.MethodPut, "/api/v1/admin/users/bob/status", admin,
		map[string]any{"status": "blocked", "reason": blockReason})
	requireStatus(t, blocked, http.StatusOK)
	if got := decodeResponse[accountDocument](t, blocked); got.Status != "blocked" || got.BlockedReason == nil || *got.BlockedReason != blockReason {
		t.Fatalf("blocked account = %+v", got)
	}
	blockedRequest := h.do(t, http.MethodGet, "/api/v1/me", bob, nil)
	requireProblemStatus(t, blockedRequest, http.StatusForbidden)
	anonymousStillWorks := h.do(t, http.MethodGet, "/api/v1/messages/bundle", "", nil)
	requireStatus(t, anonymousStillWorks, http.StatusOK)
	unblocked := h.do(t, http.MethodPut, "/api/v1/admin/users/bob/status", admin,
		map[string]any{"status": "active"})
	requireStatus(t, unblocked, http.StatusOK)
	requireStatus(t, h.do(t, http.MethodGet, "/api/v1/me", bob, nil), http.StatusOK)

	stats := h.do(t, http.MethodGet, "/api/v1/admin/stats", moderator, nil)
	requireStatus(t, stats, http.StatusOK)
	statsBody := decodeResponse[statsDocument](t, stats)
	if statsBody.Totals.Users < 3 || statsBody.Totals.Bookmarks != 1 || len(statsBody.Daily) != 30 || len(statsBody.TopTags) != 2 {
		t.Fatalf("admin stats = %+v", statsBody)
	}
	bookmarksCreated := int64(0)
	var previousDay time.Time
	for index, day := range statsBody.Daily {
		parsed, err := time.Parse(time.DateOnly, day.Date)
		if err != nil {
			t.Fatalf("stats day %q is not an ISO date: %v", day.Date, err)
		}
		if index > 0 && !parsed.Equal(previousDay.AddDate(0, 0, 1)) {
			t.Fatalf("stats days are not consecutive at %d: previous=%s current=%s", index, previousDay, parsed)
		}
		previousDay = parsed
		bookmarksCreated += day.BookmarksCreated
	}
	if bookmarksCreated != 1 {
		t.Fatalf("30-day stats should contain the one created bookmark, got %d: %+v", bookmarksCreated, statsBody.Daily)
	}
	statsETag := stats.Header.Get("ETag")
	if statsETag == "" || stats.Header.Get("Cache-Control") != "no-cache" {
		t.Fatalf("stats cache headers = %v", stats.Header)
	}
	stats304 := h.doWithHeader(t, http.MethodGet, "/api/v1/admin/stats", moderator, nil,
		"If-None-Match", statsETag)
	requireStatus(t, stats304, http.StatusNotModified)

	regularAudit := h.do(t, http.MethodGet, "/api/v1/admin/audit-log", bob, nil)
	requireProblemStatus(t, regularAudit, http.StatusForbidden)
	badTime := h.do(t, http.MethodGet, "/api/v1/admin/audit-log?from=not-a-time", admin, nil)
	requireProblemStatus(t, badTime, http.StatusBadRequest)
	auditResponse := h.do(t, http.MethodGet, "/api/v1/admin/audit-log?action=message.created&targetId="+created.ID, admin, nil)
	requireStatus(t, auditResponse, http.StatusOK)
	auditPage := decodeResponse[pageDocument[auditDocument]](t, auditResponse)
	if auditPage.TotalItems != 1 || len(auditPage.Items) != 1 || auditPage.Items[0].Detail["text"] != messageText {
		t.Fatalf("message audit = %+v", auditPage)
	}

	// Runtime edits must survive an idempotent seed replay (SPEC rule 12).
	var seedID, seedKey, seedLanguage string
	var seedDescription *string
	if err := h.pool.QueryRow(t.Context(),
		"select id::text, key, language, description from messages where language = 'en' and key like 'ui.%' order by key limit 1").
		Scan(&seedID, &seedKey, &seedLanguage, &seedDescription); err != nil {
		t.Fatalf("find seeded message: %v", err)
	}
	runtimeText := "runtime edit survives startup seed"
	seedBody := map[string]any{"key": seedKey, "language": seedLanguage, "text": runtimeText}
	if seedDescription != nil {
		seedBody["description"] = *seedDescription
	}
	seedUpdated := h.do(t, http.MethodPut, "/api/v1/messages/"+seedID, admin, seedBody)
	requireStatus(t, seedUpdated, http.StatusOK)
	seedLogger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	if err := messages.NewStore(h.pool).Seed(context.Background(), contractMessageSeedDir(t), seedLogger); err != nil {
		t.Fatalf("replay message seed: %v", err)
	}
	seedRead := h.do(t, http.MethodGet, "/api/v1/messages/"+seedID, "", nil)
	requireStatus(t, seedRead, http.StatusOK)
	if got := decodeResponse[messageDocument](t, seedRead); got.Text != runtimeText {
		t.Fatalf("seed overwrote runtime edit: %+v", got)
	}

	deleted := h.do(t, http.MethodDelete, "/api/v1/messages/"+created.ID, admin, nil)
	requireStatus(t, deleted, http.StatusNoContent)
	requireProblemStatus(t, h.do(t, http.MethodGet, "/api/v1/messages/"+created.ID, "", nil), http.StatusNotFound)

	logs := h.logs.String()
	for _, secret := range []string{messageText, updatedText, messageDescription, blockReason} {
		if strings.Contains(logs, secret) {
			t.Fatalf("free-form admin data %q leaked into diagnostics: %s", secret, logs)
		}
	}
	for _, event := range []string{"message_created", "message_updated", "message_deleted", "user_blocked", "blocked_user_rejected", "user_unblocked"} {
		if !strings.Contains(logs, `"event":"`+event+`"`) {
			t.Fatalf("missing admin/security event %q", event)
		}
	}
}

type accountDocument struct {
	Username      string  `json:"username"`
	Status        string  `json:"status"`
	BlockedReason *string `json:"blockedReason"`
	BookmarkCount int64   `json:"bookmarkCount"`
}

type statsDocument struct {
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

type auditDocument struct {
	Actor      string         `json:"actor"`
	Action     string         `json:"action"`
	TargetType string         `json:"targetType"`
	TargetID   string         `json:"targetId"`
	Detail     map[string]any `json:"detail"`
}
