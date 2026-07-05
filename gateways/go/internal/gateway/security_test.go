package gateway

import (
	"net/http"
	"net/url"
	"testing"
)

func TestCanonicalPublicOrigin(t *testing.T) {
	parsed, _ := url.Parse("https://Example.COM:443/path")
	if got := canonicalOrigin(parsed); got != "https://example.com" {
		t.Fatalf("origin = %q", got)
	}
}

func TestSameOriginStateChangeAllowsMissingBrowserSignals(t *testing.T) {
	request, _ := http.NewRequest(http.MethodPost, "http://gateway/api/v1/bookmarks", nil)
	if !sameOriginStateChangeAllowed(request, "http://localhost:8000") {
		t.Fatalf("missing Origin and Fetch metadata should be allowed")
	}
}

func TestAPIPathRequiresSegmentBoundary(t *testing.T) {
	if !isAPIPath("/api/v1/bookmarks") {
		t.Fatalf("expected /api/v1/bookmarks to be an API path")
	}
	if isAPIPath("/apix") {
		t.Fatalf("/apix must not be treated as an API path")
	}
}
