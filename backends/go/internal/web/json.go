package web

import (
	"encoding/json"
	"io"
	"net/http"
)

// WriteJSON renders a 2xx JSON body. Encoding failures at this point cannot be
// reported to the client (headers are gone); they surface in the server log
// via the caller's error path only when encoding the buffer fails, which for
// the marshallable DTOs here means a programming error.
func WriteJSON(w http.ResponseWriter, status int, body any) {
	writeJSONWithContentType(w, status, "application/json", body)
}

func writeJSONWithContentType(w http.ResponseWriter, status int, contentType string, body any) {
	w.Header().Set("Content-Type", contentType)
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

// DecodeJSON reads a request body into target. Unknown fields are ignored
// (SPEC rule 5); a syntactically broken body — including trailing data after
// the JSON document — is a 400 problem.
func DecodeJSON(r *http.Request, target any) *Problem {
	decoder := json.NewDecoder(r.Body)
	if err := decoder.Decode(target); err != nil {
		return BadRequest("Malformed JSON request body.")
	}
	if err := decoder.Decode(new(json.RawMessage)); err != io.EOF {
		return BadRequest("Malformed JSON request body.")
	}
	return nil
}
