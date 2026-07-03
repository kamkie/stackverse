package web

import (
	"bytes"
	"crypto/md5"
	"encoding/hex"
	"net/http"
	"strings"
)

// ETagMiddleware implements ETag / `If-None-Match` / `304` for message reads
// and stats (SPEC rules 10 + 19). Hashing the response body is what keeps this
// stateless: any write changes the body, hence the ETag — with no version
// counter to coordinate between instances.
func ETagMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || !etagApplies(r.URL.Path) {
			next.ServeHTTP(w, r)
			return
		}
		recorder := &bufferedResponse{header: http.Header{}, status: http.StatusOK}
		next.ServeHTTP(recorder, r)

		headers := w.Header()
		for name, values := range recorder.header {
			headers[name] = values
		}
		if recorder.status == http.StatusOK {
			sum := md5.Sum(recorder.body.Bytes())
			etag := `"` + hex.EncodeToString(sum[:]) + `"`
			headers.Set("ETag", etag)
			if ifNoneMatchMatches(r.Header.Get("If-None-Match"), etag) {
				headers.Del("Content-Type")
				headers.Del("Content-Length")
				w.WriteHeader(http.StatusNotModified)
				return // empty body
			}
		}
		w.WriteHeader(recorder.status)
		_, _ = w.Write(recorder.body.Bytes())
	})
}

func etagApplies(path string) bool {
	return strings.HasPrefix(path, "/api/v1/messages") || path == "/api/v1/admin/stats"
}

func ifNoneMatchMatches(header, etag string) bool {
	for _, candidate := range strings.Split(header, ",") {
		candidate = strings.TrimSpace(candidate)
		if candidate == etag || candidate == "*" {
			return true
		}
	}
	return false
}

// bufferedResponse captures a handler's output so the middleware can hash it.
type bufferedResponse struct {
	header http.Header
	status int
	body   bytes.Buffer
}

func (b *bufferedResponse) Header() http.Header { return b.header }

func (b *bufferedResponse) WriteHeader(status int) { b.status = status }

func (b *bufferedResponse) Write(p []byte) (int, error) { return b.body.Write(p) }
