package web

import (
	"log/slog"
	"net/http"
	"runtime/debug"
)

// The v1 bookmarks listing is a permanent deprecation exhibit (docs/SPEC.md):
// deprecated 2026-07-01, nominal sunset 2027-07-01, succeeded by /api/v2/bookmarks.
const (
	v1BookmarksDeprecation = "@1782864000"
	v1BookmarksSunset      = "Thu, 01 Jul 2027 00:00:00 GMT"
	v1BookmarksSuccessor   = `</api/v2/bookmarks>; rel="successor-version"`
)

// DeprecationHeaders adds the RFC 9745 / 8594 / 8288 signaling to every
// `GET /api/v1/bookmarks` response (SPEC "API versioning").
func DeprecationHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet && r.URL.Path == "/api/v1/bookmarks" {
			w.Header().Set("Deprecation", v1BookmarksDeprecation)
			w.Header().Set("Sunset", v1BookmarksSunset)
			w.Header().Set("Link", v1BookmarksSuccessor)
		}
		next.ServeHTTP(w, r)
	})
}

// Recover turns panics into 500 problem documents, logged at ERROR with the
// stack trace and trace id (docs/LOGGING.md §3).
func Recover(localizer Localizer, logger *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			tracked := &trackingResponse{ResponseWriter: w}
			defer func() {
				if recovered := recover(); recovered != nil {
					if recovered == http.ErrAbortHandler {
						panic(recovered)
					}
					logger.ErrorContext(r.Context(), "Panic serving request",
						slog.Any("error", recovered),
						slog.String("method", r.Method),
						slog.String("path", r.URL.Path),
						slog.String("stack", string(debug.Stack())),
					)
					if !tracked.wrote {
						WriteProblem(tracked, r, localizer, logger,
							&Problem{Status: http.StatusInternalServerError, Title: "Internal Server Error"})
					}
				}
			}()
			next.ServeHTTP(tracked, r)
		})
	}
}

type trackingResponse struct {
	http.ResponseWriter
	wrote bool
}

func (t *trackingResponse) WriteHeader(status int) {
	t.wrote = true
	t.ResponseWriter.WriteHeader(status)
}

func (t *trackingResponse) Write(p []byte) (int, error) {
	t.wrote = true
	return t.ResponseWriter.Write(p)
}
