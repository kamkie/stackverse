package bookmarks

import (
	"strings"
	"testing"
)

func str(s string) *string { return &s }

func TestValidateBookmark(t *testing.T) {
	valid := request{URL: "https://example.com/x", Title: "a title"}
	input, problem := validate(valid)
	if problem != nil {
		t.Fatalf("valid input rejected: %+v", problem.Fields)
	}
	if input.visibility != VisibilityPrivate {
		t.Fatalf("visibility must default to private, got %q", input.visibility)
	}

	cases := []struct {
		name  string
		body  request
		field string
	}{
		{"missing url", request{Title: "t"}, "url"},
		{"relative url", request{URL: "/not/absolute", Title: "t"}, "url"},
		{"non-http scheme", request{URL: "ftp://example.com", Title: "t"}, "url"},
		{"hostless url", request{URL: "https://", Title: "t"}, "url"},
		{"overlong url", request{URL: "https://example.com/" + strings.Repeat("x", 2000), Title: "t"}, "url"},
		{"missing title", request{URL: "https://example.com"}, "title"},
		{"blank title", request{URL: "https://example.com", Title: "   "}, "title"},
		{"overlong title", request{URL: "https://example.com", Title: strings.Repeat("x", 201)}, "title"},
		{"overlong notes", request{URL: "https://example.com", Title: "t", Notes: str(strings.Repeat("x", 4001))}, "notes"},
		{"too many tags", request{URL: "https://example.com", Title: "t",
			Tags: []string{"t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9", "t10"}}, "tags"},
		{"invalid tag characters", request{URL: "https://example.com", Title: "t", Tags: []string{"no spaces!"}}, "tags"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, problem := validate(c.body)
			if problem == nil {
				t.Fatal("expected a validation problem, got none")
			}
			fields := make([]string, len(problem.Fields))
			for i, field := range problem.Fields {
				fields[i] = field.Field
			}
			if !strings.Contains(strings.Join(fields, ","), c.field) {
				t.Fatalf("expected a violation on %q, got %v", c.field, fields)
			}
		})
	}
}

func TestValidateBookmarkNormalizesTags(t *testing.T) {
	input, problem := validate(request{
		URL: "https://example.com", Title: "t",
		Tags: []string{" Kotlin ", "kotlin", "go"},
	})
	if problem != nil {
		t.Fatalf("valid input rejected: %+v", problem.Fields)
	}
	if len(input.tags) != 2 || input.tags[0] != "kotlin" || input.tags[1] != "go" {
		t.Fatalf("tags must be trimmed, lowercased, deduplicated in order; got %v", input.tags)
	}
}

func TestValidateBookmarkDeduplicatesBeforeCounting(t *testing.T) {
	// 11 raw entries, 1 after normalization — must pass the ≤10 rule
	tags := make([]string, 11)
	for i := range tags {
		tags[i] = "same"
	}
	input, problem := validate(request{URL: "https://example.com", Title: "t", Tags: tags})
	if problem != nil {
		t.Fatalf("deduplicated tags rejected: %+v", problem.Fields)
	}
	if len(input.tags) != 1 {
		t.Fatalf("expected one tag after deduplication, got %v", input.tags)
	}
}
