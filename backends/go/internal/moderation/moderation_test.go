package moderation

import (
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestValidReasonAndStatusAreContractValues(t *testing.T) {
	for _, reason := range []string{"spam", "offensive", "broken-link", "other"} {
		if !validReason(reason) {
			t.Fatalf("reason %q should be valid", reason)
		}
	}
	if validReason("broken_link") || validReason("") {
		t.Fatal("non-contract report reasons must be invalid")
	}

	for _, status := range []string{statusOpen, statusDismissed, statusActioned} {
		if !validStatus(status) {
			t.Fatalf("status %q should be valid", status)
		}
	}
	if validStatus("resolved") || validStatus("") {
		t.Fatal("non-contract report statuses must be invalid")
	}
}

func TestValidateReport(t *testing.T) {
	comment := strings.Repeat("x", 1000)
	if problem := validateReport(reportRequest{Reason: "other", Comment: &comment}); problem != nil {
		t.Fatalf("valid report rejected: %+v", problem.Fields)
	}

	tooLong := strings.Repeat("x", 1001)
	cases := []struct {
		name  string
		body  reportRequest
		field string
	}{
		{"invalid reason", reportRequest{Reason: "duplicate"}, "reason"},
		{"overlong comment", reportRequest{Reason: "spam", Comment: &tooLong}, "comment"},
	}

	for _, tt := range cases {
		t.Run(tt.name, func(t *testing.T) {
			problem := validateReport(tt.body)
			if problem == nil {
				t.Fatal("expected validation problem")
			}
			found := false
			for _, field := range problem.Fields {
				if field.Field == tt.field {
					found = true
				}
			}
			if !found {
				t.Fatalf("expected field %q, got %+v", tt.field, problem.Fields)
			}
		})
	}
}

func TestToResponsePreservesResolutionFields(t *testing.T) {
	comment := "broken"
	resolver := "moderator"
	note := "hidden"
	resolvedAt := time.Date(2026, 7, 3, 12, 0, 0, 0, time.UTC)
	createdAt := resolvedAt.Add(-time.Hour)
	reportID := uuid.New()
	bookmarkID := uuid.New()

	response := toResponse(report{
		ID: reportID, BookmarkID: bookmarkID, Reporter: "demo",
		Reason: "broken-link", Comment: &comment, Status: statusActioned,
		ResolvedBy: &resolver, ResolvedAt: &resolvedAt, ResolutionNote: &note,
		CreatedAt: createdAt,
	})

	if response.ID != reportID ||
		response.BookmarkID != bookmarkID ||
		response.Reporter != "demo" ||
		response.Reason != "broken-link" ||
		response.Comment == nil ||
		*response.Comment != comment ||
		response.Status != statusActioned ||
		response.ResolvedBy == nil ||
		*response.ResolvedBy != resolver ||
		response.ResolvedAt == nil ||
		!response.ResolvedAt.Equal(resolvedAt) ||
		response.ResolutionNote == nil ||
		*response.ResolutionNote != note ||
		!response.CreatedAt.Equal(createdAt) {
		t.Fatalf("unexpected response: %+v", response)
	}
}
