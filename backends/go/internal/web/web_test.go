package web

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestETagMiddleware(t *testing.T) {
	handler := ETagMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-cache")
		WriteJSON(w, http.StatusOK, map[string]string{"hello": "world"})
	}))

	first := httptest.NewRecorder()
	handler.ServeHTTP(first, httptest.NewRequest(http.MethodGet, "/api/v1/messages", nil))
	etag := first.Header().Get("ETag")
	if etag == "" {
		t.Fatal("a 200 message read must carry an ETag")
	}
	if first.Header().Get("Cache-Control") != "no-cache" {
		t.Fatal("Cache-Control must pass through the middleware")
	}

	revalidated := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/api/v1/messages", nil)
	request.Header.Set("If-None-Match", etag)
	handler.ServeHTTP(revalidated, request)
	if revalidated.Code != http.StatusNotModified {
		t.Fatalf("matching If-None-Match must yield 304, got %d", revalidated.Code)
	}
	if revalidated.Body.Len() != 0 {
		t.Fatalf("a 304 must have an empty body, got %q", revalidated.Body.String())
	}

	stale := httptest.NewRecorder()
	request = httptest.NewRequest(http.MethodGet, "/api/v1/messages", nil)
	request.Header.Set("If-None-Match", `"different"`)
	handler.ServeHTTP(stale, request)
	if stale.Code != http.StatusOK {
		t.Fatalf("a non-matching If-None-Match must yield 200, got %d", stale.Code)
	}
}

func TestETagMiddlewareSkipsOtherPaths(t *testing.T) {
	handler := ETagMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		WriteJSON(w, http.StatusOK, map[string]string{"hello": "world"})
	}))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/api/v1/bookmarks", nil))
	if response.Header().Get("ETag") != "" {
		t.Fatal("bookmark responses must not get an ETag")
	}
}

func TestDeprecationHeadersOnV1BookmarksListingOnly(t *testing.T) {
	handler := DeprecationHeaders(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	listing := httptest.NewRecorder()
	handler.ServeHTTP(listing, httptest.NewRequest(http.MethodGet, "/api/v1/bookmarks?size=1", nil))
	if listing.Header().Get("Deprecation") == "" || listing.Header().Get("Sunset") == "" {
		t.Fatal("GET /api/v1/bookmarks must carry Deprecation and Sunset headers")
	}
	if link := listing.Header().Get("Link"); link != `</api/v2/bookmarks>; rel="successor-version"` {
		t.Fatalf("unexpected successor link: %q", link)
	}

	v2 := httptest.NewRecorder()
	handler.ServeHTTP(v2, httptest.NewRequest(http.MethodGet, "/api/v2/bookmarks", nil))
	if v2.Header().Get("Deprecation") != "" {
		t.Fatal("v2 must not be deprecated")
	}

	item := httptest.NewRecorder()
	handler.ServeHTTP(item, httptest.NewRequest(http.MethodGet, "/api/v1/bookmarks/some-id", nil))
	if item.Header().Get("Deprecation") != "" {
		t.Fatal("only the listing is deprecated, not the item routes")
	}
}

func TestPagingBounds(t *testing.T) {
	cases := []struct {
		query string
		valid bool
	}{
		{"", true},
		{"?page=0&size=1", true},
		{"?size=100", true},
		{"?size=0", false},
		{"?size=101", false},
		{"?page=-1", false},
		{"?page=x", false},
		{"?size=1.5", false},
	}
	for _, c := range cases {
		r := httptest.NewRequest(http.MethodGet, "/x"+c.query, nil)
		_, _, problem := Paging(r)
		if c.valid && problem != nil {
			t.Errorf("%q: expected valid paging, got %v", c.query, problem)
		}
		if !c.valid && problem == nil {
			t.Errorf("%q: expected a 400 problem, got none", c.query)
		}
	}
}

func TestPagingDefaults(t *testing.T) {
	page, size, problem := Paging(httptest.NewRequest(http.MethodGet, "/x", nil))
	if problem != nil || page != 0 || size != 20 {
		t.Fatalf("defaults must be page=0 size=20, got page=%d size=%d problem=%v", page, size, problem)
	}
}

func TestEscapeLike(t *testing.T) {
	if got := EscapeLike(`50%_of\things`); got != `50\%\_of\\things` {
		t.Fatalf("unexpected escape: %q", got)
	}
}

func TestNewPageTotals(t *testing.T) {
	page := NewPage([]int{1, 2}, 0, 2, 5)
	if page.TotalPages != 3 {
		t.Fatalf("5 items at size 2 are 3 pages, got %d", page.TotalPages)
	}
	empty := NewPage[int](nil, 0, 20, 0)
	if empty.Items == nil {
		t.Fatal("items must serialize as [], never null")
	}
	if empty.TotalPages != 0 {
		t.Fatalf("an empty result has 0 pages, got %d", empty.TotalPages)
	}
}
