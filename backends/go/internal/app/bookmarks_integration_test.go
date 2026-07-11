package app

import (
	"net/http"
	"slices"
	"strings"
	"testing"
)

func TestIntegrationBookmarkContractAndAuthorization(t *testing.T) {
	h := newIntegrationHarness(t)
	alice := h.token(t, "alice")
	bob := h.token(t, "bob")

	health := h.do(t, http.MethodGet, "/healthz", "", nil)
	requireStatus(t, health, http.StatusOK)
	ready := h.do(t, http.MethodGet, "/readyz", "", nil)
	requireStatus(t, ready, http.StatusOK)

	publicEmpty := h.do(t, http.MethodGet, "/api/v1/bookmarks?visibility=public", "", nil)
	requireStatus(t, publicEmpty, http.StatusOK)
	if publicEmpty.Header.Get("Deprecation") == "" || publicEmpty.Header.Get("Sunset") == "" || publicEmpty.Header.Get("Link") == "" {
		t.Fatalf("v1 listing did not carry every deprecation header: %v", publicEmpty.Header)
	}
	if body := decodeResponse[pageDocument[bookmarkDocument]](t, publicEmpty); len(body.Items) != 0 || body.TotalItems != 0 {
		t.Fatalf("empty public feed = %+v", body)
	}

	anonymousPrivate := h.do(t, http.MethodGet, "/api/v1/bookmarks", "", nil)
	requireProblemStatus(t, anonymousPrivate, http.StatusUnauthorized)
	if got := anonymousPrivate.Header.Get("Content-Type"); !strings.HasPrefix(got, "application/problem+json") {
		t.Fatalf("anonymous private listing content type = %q", got)
	}

	invalidBearer := "this-is-not-a-jwt-and-must-not-be-logged"
	invalid := h.do(t, http.MethodGet, "/api/v1/me", invalidBearer, nil)
	requireProblemStatus(t, invalid, http.StatusUnauthorized)
	if strings.Contains(h.logs.String(), invalidBearer) {
		t.Fatal("invalid bearer token leaked into structured logs")
	}
	if !strings.Contains(h.logs.String(), `"event":"jwt_validation_failed"`) {
		t.Fatalf("missing jwt_validation_failed event: %s", h.logs.String())
	}

	regularDenied := h.do(t, http.MethodGet, "/api/v1/admin/stats", bob, nil)
	requireProblemStatus(t, regularDenied, http.StatusForbidden)
	if !strings.Contains(h.logs.String(), `"event":"authz_denied"`) {
		t.Fatalf("missing authz_denied event: %s", h.logs.String())
	}

	invalidInput := h.do(t, http.MethodPost, "/api/v1/bookmarks", alice, map[string]any{
		"url": "ftp://example.test", "title": " ", "tags": []string{"not valid"},
	})
	requireProblemStatus(t, invalidInput, http.StatusBadRequest)
	problem := decodeResponse[problemDocument](t, invalidInput)
	fields := make([]string, len(problem.Errors))
	for i, field := range problem.Errors {
		fields[i] = field.Field
		if field.MessageKey == "" || field.Message == "" {
			t.Fatalf("unlocalized field problem: %+v", field)
		}
	}
	for _, expected := range []string{"url", "title", "tags"} {
		if !slices.Contains(fields, expected) {
			t.Fatalf("validation fields %v do not contain %q", fields, expected)
		}
	}

	privateBookmark := createBookmark(t, h, alice, "Private Go", "private", []string{"go", "testing"})
	publicOne := createBookmark(t, h, alice, "Public One", "public", []string{"go", "testing"})
	publicTwo := createBookmark(t, h, alice, "Public Two", "public", []string{"go", "web"})

	maskedGet := h.do(t, http.MethodGet, "/api/v1/bookmarks/"+privateBookmark.ID, bob, nil)
	requireProblemStatus(t, maskedGet, http.StatusNotFound)
	maskedUpdate := h.do(t, http.MethodPut, "/api/v1/bookmarks/"+privateBookmark.ID, bob, validBookmarkBody("Stolen", "public", []string{"go"}))
	requireProblemStatus(t, maskedUpdate, http.StatusNotFound)
	maskedDelete := h.do(t, http.MethodDelete, "/api/v1/bookmarks/"+privateBookmark.ID, bob, nil)
	requireProblemStatus(t, maskedDelete, http.StatusNotFound)

	publicRead := h.do(t, http.MethodGet, "/api/v1/bookmarks/"+publicOne.ID, "", nil)
	requireStatus(t, publicRead, http.StatusOK)
	if got := decodeResponse[bookmarkDocument](t, publicRead); got.Owner != "alice" || got.Visibility != "public" {
		t.Fatalf("anonymous public bookmark = %+v", got)
	}

	filtered := h.do(t, http.MethodGet,
		"/api/v1/bookmarks?tag=go&tag=testing&q=public&page=0&size=10", alice, nil)
	requireStatus(t, filtered, http.StatusOK)
	filteredPage := decodeResponse[pageDocument[bookmarkDocument]](t, filtered)
	if filteredPage.TotalItems != 1 || len(filteredPage.Items) != 1 || filteredPage.Items[0].ID != publicOne.ID {
		t.Fatalf("AND tag and q filter = %+v", filteredPage)
	}

	tagsResponse := h.do(t, http.MethodGet, "/api/v1/tags", alice, nil)
	requireStatus(t, tagsResponse, http.StatusOK)
	var tags struct {
		Tags []struct {
			Tag   string `json:"tag"`
			Count int64  `json:"count"`
		} `json:"tags"`
	}
	tags = decodeResponse[struct {
		Tags []struct {
			Tag   string `json:"tag"`
			Count int64  `json:"count"`
		} `json:"tags"`
	}](t, tagsResponse)
	if len(tags.Tags) != 3 || tags.Tags[0].Tag != "go" || tags.Tags[0].Count != 3 {
		t.Fatalf("tag counts = %+v", tags.Tags)
	}

	firstCursorPage := h.do(t, http.MethodGet, "/api/v2/bookmarks?visibility=public&size=1", "", nil)
	requireStatus(t, firstCursorPage, http.StatusOK)
	first := decodeResponse[cursorPageDocument](t, firstCursorPage)
	if len(first.Items) != 1 || first.NextCursor == nil {
		t.Fatalf("first cursor page = %+v", first)
	}
	newestAfterCursor := createBookmark(t, h, alice, "Concurrent Insert", "public", []string{"go"})
	secondCursorPage := h.do(t, http.MethodGet,
		"/api/v2/bookmarks?visibility=public&size=2&cursor="+*first.NextCursor, "", nil)
	requireStatus(t, secondCursorPage, http.StatusOK)
	second := decodeResponse[cursorPageDocument](t, secondCursorPage)
	for _, item := range second.Items {
		if item.ID == first.Items[0].ID || item.ID == newestAfterCursor.ID {
			t.Fatalf("cursor page duplicated/skipped boundary under concurrent insert: first=%+v second=%+v", first, second)
		}
	}
	if len(second.Items) != 1 || (second.Items[0].ID != publicOne.ID && second.Items[0].ID != publicTwo.ID) {
		t.Fatalf("second cursor page = %+v", second)
	}
	badCursor := h.do(t, http.MethodGet, "/api/v2/bookmarks?visibility=public&cursor=malformed", "", nil)
	requireProblemStatus(t, badCursor, http.StatusBadRequest)

	updated := h.do(t, http.MethodPut, "/api/v1/bookmarks/"+privateBookmark.ID, alice,
		validBookmarkBody("Updated Private", "private", []string{"go"}))
	requireStatus(t, updated, http.StatusOK)
	if got := decodeResponse[bookmarkDocument](t, updated); got.Title != "Updated Private" || got.Owner != "alice" {
		t.Fatalf("updated bookmark = %+v", got)
	}

	secondRouter, _ := New(h.cfg, h.pool, h.logger)
	persisted := h.doOn(t, secondRouter, http.MethodGet, "/api/v1/bookmarks/"+publicTwo.ID, "", nil)
	requireStatus(t, persisted, http.StatusOK)
	if got := decodeResponse[bookmarkDocument](t, persisted); got.ID != publicTwo.ID {
		t.Fatalf("second stateless router read = %+v", got)
	}

	deleted := h.do(t, http.MethodDelete, "/api/v1/bookmarks/"+privateBookmark.ID, alice, nil)
	requireStatus(t, deleted, http.StatusNoContent)
	missing := h.do(t, http.MethodGet, "/api/v1/bookmarks/"+privateBookmark.ID, alice, nil)
	requireProblemStatus(t, missing, http.StatusNotFound)

	methodNotAllowed := h.do(t, http.MethodPost, "/healthz", "", nil)
	requireProblemStatus(t, methodNotAllowed, http.StatusMethodNotAllowed)
	unknown := h.do(t, http.MethodGet, "/does-not-exist", "", nil)
	requireProblemStatus(t, unknown, http.StatusNotFound)
}

func createBookmark(t *testing.T, h *integrationHarness, token, title, visibility string, tags []string) bookmarkDocument {
	t.Helper()
	response := h.do(t, http.MethodPost, "/api/v1/bookmarks", token, validBookmarkBody(title, visibility, tags))
	requireStatus(t, response, http.StatusCreated)
	bookmark := decodeResponse[bookmarkDocument](t, response)
	if bookmark.ID == "" || response.Header.Get("Location") != "/api/v1/bookmarks/"+bookmark.ID {
		t.Fatalf("created bookmark/location = %+v / %q", bookmark, response.Header.Get("Location"))
	}
	return bookmark
}

func validBookmarkBody(title, visibility string, tags []string) map[string]any {
	return map[string]any{
		"url":        "https://example.test/" + strings.ToLower(strings.ReplaceAll(title, " ", "-")),
		"title":      title,
		"notes":      "Notes for " + title,
		"tags":       tags,
		"visibility": visibility,
	}
}

func requireProblemStatus(t *testing.T, response apiResponse, expected int) {
	t.Helper()
	requireStatus(t, response, expected)
	problem := decodeResponse[problemDocument](t, response)
	if problem.Status != expected || problem.Title == "" {
		t.Fatalf("problem body = %+v, want status %d", problem, expected)
	}
}
