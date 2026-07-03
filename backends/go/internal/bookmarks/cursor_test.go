package bookmarks

import (
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestCursorRoundTrip(t *testing.T) {
	original := Cursor{
		CreatedAt: time.Date(2026, 7, 3, 10, 11, 12, 123456000, time.UTC),
		ID:        uuid.MustParse("018f4a1e-0000-7000-8000-000000000001"),
	}
	decoded, problem := DecodeCursor(original.Encode())
	if problem != nil {
		t.Fatalf("decoding a freshly encoded cursor failed: %v", problem)
	}
	if !decoded.CreatedAt.Equal(original.CreatedAt) || decoded.ID != original.ID {
		t.Fatalf("round trip changed the cursor: got %+v, want %+v", decoded, original)
	}
}

func TestCursorRoundTripWithoutFractionalSeconds(t *testing.T) {
	original := Cursor{CreatedAt: time.Date(2026, 7, 3, 10, 11, 12, 0, time.UTC), ID: uuid.New()}
	decoded, problem := DecodeCursor(original.Encode())
	if problem != nil {
		t.Fatalf("decoding failed: %v", problem)
	}
	if !decoded.CreatedAt.Equal(original.CreatedAt) {
		t.Fatalf("round trip changed the timestamp: got %v, want %v", decoded.CreatedAt, original.CreatedAt)
	}
}

func TestDecodeCursorRejectsMalformedInput(t *testing.T) {
	cases := []string{
		"definitely-not-a-cursor!",
		"",
		"aGVsbG8",                    // decodes but has no separator
		"MjAyNi0wNy0wM3xub3QtYS1pZA", // "2026-07-03|not-a-id"
		"bm90LWEtdGltZXwwMThmNGExZS0wMDAwLTcwMDAtODAwMC0wMDAwMDAwMDAwMDE", // "not-a-time|<uuid>"
	}
	for _, cursor := range cases {
		if _, problem := DecodeCursor(cursor); problem == nil {
			t.Errorf("cursor %q: expected a 400 problem, got none", cursor)
		} else if problem.Status != 400 {
			t.Errorf("cursor %q: expected status 400, got %d", cursor, problem.Status)
		}
	}
}
