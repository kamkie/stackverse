package gateway

import (
	"net"
	"net/http"
	"net/url"
	"strings"
)

const (
	contentSecurityPolicy   = "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'"
	strictTransportSecurity = "max-age=31536000; includeSubDomains"
)

func withSecurityHeaders(secure bool, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		headers := w.Header()
		headers.Set("X-Content-Type-Options", "nosniff")
		if secure {
			headers.Set("Strict-Transport-Security", strictTransportSecurity)
		}
		if !isAPIPath(r.URL.Path) {
			headers.Set("Referrer-Policy", "same-origin")
			headers.Set("Content-Security-Policy", contentSecurityPolicy)
			headers.Set("X-Frame-Options", "DENY")
			headers.Set("Cross-Origin-Opener-Policy", "same-origin")
			headers.Set("Cross-Origin-Resource-Policy", "same-origin")
		}
		next.ServeHTTP(w, r)
	})
}

func canonicalOrigin(publicURL *url.URL) string {
	scheme := strings.ToLower(publicURL.Scheme)
	host := strings.ToLower(publicURL.Hostname())
	port := publicURL.Port()
	if port == "" || (scheme == "http" && port == "80") || (scheme == "https" && port == "443") {
		return scheme + "://" + bracketHost(host)
	}
	return scheme + "://" + net.JoinHostPort(host, port)
}

func canonicalOriginOrEmpty(raw string) string {
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Scheme == "" || parsed.Host == "" {
		return ""
	}
	if parsed.Path != "" || parsed.RawQuery != "" || parsed.Fragment != "" {
		return ""
	}
	return canonicalOrigin(parsed)
}

func bracketHost(host string) string {
	if strings.Contains(host, ":") && !strings.HasPrefix(host, "[") {
		return "[" + host + "]"
	}
	return host
}

func isStateChanging(method string) bool {
	switch method {
	case http.MethodPost, http.MethodPut, http.MethodPatch, http.MethodDelete:
		return true
	default:
		return false
	}
}

func sameOriginStateChangeAllowed(r *http.Request, expectedOrigin string) bool {
	if !isStateChanging(r.Method) || !isAPIPath(r.URL.Path) {
		return true
	}

	if origin := r.Header.Get("Origin"); origin != "" && canonicalOriginOrEmpty(origin) != expectedOrigin {
		return false
	}

	fetchSite := r.Header.Get("Sec-Fetch-Site")
	return fetchSite == "" ||
		strings.EqualFold(fetchSite, "same-origin") ||
		strings.EqualFold(fetchSite, "none")
}

func isAPIPath(path string) bool {
	return path == "/api" || strings.HasPrefix(path, "/api/")
}
