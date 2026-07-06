package bookmarks

import (
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

func str(s string) *string { return &s }

func TestBookmarkVisibleTo(t *testing.T) {
	cases := []struct {
		name     string
		bookmark Bookmark
		caller   string
		want     bool
	}{
		{
			name:     "owner sees hidden private bookmark",
			bookmark: Bookmark{Owner: "alice", Visibility: VisibilityPrivate, Status: StatusHidden},
			caller:   "alice",
			want:     true,
		},
		{
			name:     "anonymous sees active public bookmark",
			bookmark: Bookmark{Owner: "alice", Visibility: VisibilityPublic, Status: StatusActive},
			caller:   "",
			want:     true,
		},
		{
			name:     "hidden public bookmark is masked from others",
			bookmark: Bookmark{Owner: "alice", Visibility: VisibilityPublic, Status: StatusHidden},
			caller:   "bob",
			want:     false,
		},
		{
			name:     "private bookmark is masked from others",
			bookmark: Bookmark{Owner: "alice", Visibility: VisibilityPrivate, Status: StatusActive},
			caller:   "bob",
			want:     false,
		},
	}
	for _, tt := range cases {
		t.Run(tt.name, func(t *testing.T) {
			if got := tt.bookmark.VisibleTo(tt.caller); got != tt.want {
				t.Fatalf("VisibleTo(%q) = %v, want %v", tt.caller, got, tt.want)
			}
		})
	}
}

func TestToResponseSortsTagsWithoutMutatingBookmark(t *testing.T) {
	created := time.Date(2026, 7, 3, 10, 0, 0, 0, time.UTC)
	bookmark := Bookmark{
		ID: uuid.New(), Owner: "demo", URL: "https://example.com", Title: "Title",
		Tags: []string{"z", "a"}, Visibility: VisibilityPublic, Status: StatusActive,
		CreatedAt: created, UpdatedAt: created,
	}

	response := ToResponse(bookmark)
	if len(response.Tags) != 2 || response.Tags[0] != "a" || response.Tags[1] != "z" {
		t.Fatalf("response tags should be sorted, got %v", response.Tags)
	}
	if bookmark.Tags[0] != "z" || bookmark.Tags[1] != "a" {
		t.Fatalf("ToResponse must not mutate stored tag order, got %v", bookmark.Tags)
	}
}

func TestListQueryWhereAppliesPublicScopeAndEscapedFilters(t *testing.T) {
	where, args := listQuery{
		caller:     "alice",
		visibility: VisibilityPublic,
		tags:       []string{"go", "web"},
		q:          `50%_off`,
	}.where()

	if strings.Contains(where, "owner =") {
		t.Fatalf("public listing must not be owner-scoped: %s", where)
	}
	if !strings.Contains(where, "visibility = 'public' and status = 'active'") {
		t.Fatalf("public listing must exclude hidden bookmarks: %s", where)
	}
	if !strings.Contains(where, "tags @> $1") {
		t.Fatalf("tag AND filter missing from where clause: %s", where)
	}
	if !strings.Contains(where, `escape '\'`) {
		t.Fatalf("q filter must use escaped LIKE patterns: %s", where)
	}
	if len(args) != 2 {
		t.Fatalf("args = %v, want tag array and q pattern", args)
	}
	tags, ok := args[0].([]string)
	if !ok || len(tags) != 2 || tags[0] != "go" || tags[1] != "web" {
		t.Fatalf("tag args = %#v", args[0])
	}
	if got := args[1]; got != `%50\%\_off%` {
		t.Fatalf("q pattern = %#v", got)
	}
}

func TestListQueryWhereDefaultsToCallerOwnedScope(t *testing.T) {
	where, args := listQuery{caller: "alice"}.where()
	if where != "where owner = $1" {
		t.Fatalf("default listing should be caller-owned, got %q", where)
	}
	if len(args) != 1 || args[0] != "alice" {
		t.Fatalf("args = %v, want caller", args)
	}
}

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

func TestValidateQueryTags(t *testing.T) {
	tags, problem := validateQueryTags([]string{" Go ", "web"})
	if problem != nil {
		t.Fatalf("valid query tags rejected: %+v", problem.Fields)
	}
	if len(tags) != 2 || tags[0] != "go" || tags[1] != "web" {
		t.Fatalf("query tags must be trimmed and lowercased in order; got %v", tags)
	}

	_, problem = validateQueryTags([]string{"valid", "no spaces!"})
	if problem == nil {
		t.Fatal("expected invalid query tag to be rejected")
	}
	if len(problem.Fields) != 1 || problem.Fields[0].Field != "tag" ||
		problem.Fields[0].MessageKey != "validation.tag.invalid" {
		t.Fatalf("expected a tag validation problem, got %+v", problem.Fields)
	}
}
