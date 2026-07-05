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

func TestCanonicalOriginHandlesDefaultPortsAndIPv6(t *testing.T) {
	tests := []struct {
		raw  string
		want string
	}{
		{raw: "http://Example.COM:80", want: "http://example.com"},
		{raw: "https://Example.COM:444", want: "https://example.com:444"},
		{raw: "http://[::1]:8000", want: "http://[::1]:8000"},
	}

	for _, tt := range tests {
		t.Run(tt.raw, func(t *testing.T) {
			parsed, err := url.Parse(tt.raw)
			if err != nil {
				t.Fatal(err)
			}
			if got := canonicalOrigin(parsed); got != tt.want {
				t.Fatalf("origin = %q, want %q", got, tt.want)
			}
		})
	}
}

func TestCanonicalOriginOrEmptyRejectsNonOrigins(t *testing.T) {
	for _, raw := range []string{
		"not a url",
		"https://stackverse.example/path",
		"https://stackverse.example?x=1",
		"https://stackverse.example#fragment",
	} {
		t.Run(raw, func(t *testing.T) {
			if got := canonicalOriginOrEmpty(raw); got != "" {
				t.Fatalf("origin = %q", got)
			}
		})
	}
}

func TestSameOriginStateChangeAllowsMissingBrowserSignals(t *testing.T) {
	request, _ := http.NewRequest(http.MethodPost, "http://gateway/api/v1/bookmarks", nil)
	if !sameOriginStateChangeAllowed(request, "http://localhost:8000") {
		t.Fatalf("missing Origin and Fetch metadata should be allowed")
	}
}

func TestSameOriginStateChangeRequiresExactOriginAndFetchSite(t *testing.T) {
	tests := []struct {
		name        string
		origin      string
		fetchSite   string
		expected    string
		wantAllowed bool
	}{
		{name: "exact origin", origin: "http://localhost:8000", fetchSite: "same-origin", expected: "http://localhost:8000", wantAllowed: true},
		{name: "none fetch site", origin: "http://localhost:8000", fetchSite: "none", expected: "http://localhost:8000", wantAllowed: true},
		{name: "wrong origin", origin: "http://localhost:8001", fetchSite: "same-origin", expected: "http://localhost:8000", wantAllowed: false},
		{name: "same site denied", origin: "http://localhost:8000", fetchSite: "same-site", expected: "http://localhost:8000", wantAllowed: false},
		{name: "invalid origin denied", origin: "http://localhost:8000/path", fetchSite: "", expected: "http://localhost:8000", wantAllowed: false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			request, _ := http.NewRequest(http.MethodPut, "http://gateway/api/v1/bookmarks/1", nil)
			if tt.origin != "" {
				request.Header.Set("Origin", tt.origin)
			}
			if tt.fetchSite != "" {
				request.Header.Set("Sec-Fetch-Site", tt.fetchSite)
			}
			if got := sameOriginStateChangeAllowed(request, tt.expected); got != tt.wantAllowed {
				t.Fatalf("allowed = %v, want %v", got, tt.wantAllowed)
			}
		})
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
