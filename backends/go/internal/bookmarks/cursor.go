package bookmarks

import (
	"encoding/base64"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/kamkie/stackverse/backends/go/internal/web"
)

// Cursor is the keyset position for the v2 listing: the (createdAt, id) of the
// last item on the previous page, wrapped in base64url so clients treat it as
// opaque. Keyset pagination is what makes v2 stable under concurrent inserts —
// new rows land before the cursor position and cannot shift the next page.
//
// "Unresolvable cursor → 400" is interpreted as *undecodable*: a well-formed
// but never-issued cursor is indistinguishable, on a stateless service, from a
// legitimate cursor whose boundary row was deleted between pages — and that
// one must keep working.
type Cursor struct {
	CreatedAt time.Time
	ID        uuid.UUID
}

func (c Cursor) Encode() string {
	raw := c.CreatedAt.UTC().Format(time.RFC3339Nano) + "|" + c.ID.String()
	return base64.RawURLEncoding.EncodeToString([]byte(raw))
}

func DecodeCursor(cursor string) (Cursor, *web.Problem) {
	malformed := web.BadRequest("The cursor is malformed or unresolvable.")
	decoded, err := base64.RawURLEncoding.DecodeString(cursor)
	if err != nil {
		return Cursor{}, malformed
	}
	createdAt, id, ok := strings.Cut(string(decoded), "|")
	if !ok {
		return Cursor{}, malformed
	}
	parsedTime, err := time.Parse(time.RFC3339Nano, createdAt)
	if err != nil {
		return Cursor{}, malformed
	}
	parsedID, err := uuid.Parse(id)
	if err != nil {
		return Cursor{}, malformed
	}
	return Cursor{CreatedAt: parsedTime, ID: parsedID}, nil
}
