package messages

import (
	"strings"
	"testing"
)

func TestResolveLanguage(t *testing.T) {
	supported := map[string]bool{"en": true, "pl": true}
	cases := []struct {
		name           string
		lang           string
		acceptLanguage string
		want           string
	}{
		{"default is en", "", "", "en"},
		{"explicit lang wins", "pl", "en", "pl"},
		{"unsupported lang falls to Accept-Language", "zz", "pl", "pl"},
		{"unsupported lang and header fall to en", "zz", "zz", "en"},
		{"header languages are quality-ordered", "", "en;q=0.5, zz, pl;q=0.8", "pl"},
		{"first supported header entry wins", "", "pl, en;q=0.5", "pl"},
		{"region subtags map to the primary language", "", "pl-PL, en", "pl"},
		{"wildcard entries are skipped", "", "*, pl", "pl"},
		{"q=0 entries are skipped", "", "pl;q=0, en", "en"},
		{"garbage headers never error", "", ";;;=,,q=x", "en"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := ResolveLanguage(c.lang, c.acceptLanguage, supported); got != c.want {
				t.Fatalf("ResolveLanguage(%q, %q) = %q, want %q", c.lang, c.acceptLanguage, got, c.want)
			}
		})
	}
}

func TestValidateMessage(t *testing.T) {
	long := func(n int) string {
		out := make([]byte, n)
		for i := range out {
			out[i] = 'x'
		}
		return string(out)
	}
	valid := request{Key: "conformance.test", Language: "en", Text: "hello"}
	if _, problem := validate(valid); problem != nil {
		t.Fatalf("valid input rejected: %v", problem.Fields)
	}
	cases := []struct {
		name  string
		body  request
		field string
	}{
		{"uppercase key", request{Key: "Not.Lower", Language: "en", Text: "x"}, "key"},
		{"empty key", request{Key: "", Language: "en", Text: "x"}, "key"},
		{"overlong key", request{Key: long(151), Language: "en", Text: "x"}, "key"},
		{"three-letter language", request{Key: "a.b", Language: "english", Text: "x"}, "language"},
		{"empty text", request{Key: "a.b", Language: "en", Text: ""}, "text"},
		{"overlong text", request{Key: "a.b", Language: "en", Text: long(2001)}, "text"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, problem := validate(c.body)
			if problem == nil {
				t.Fatal("expected a validation problem, got none")
			}
			found := false
			for _, field := range problem.Fields {
				if field.Field == c.field {
					found = true
				}
			}
			if !found {
				t.Fatalf("expected a violation on %q, got %+v", c.field, problem.Fields)
			}
		})
	}
}

func TestValidateMessageTrimsKeyAndLanguageButPreservesText(t *testing.T) {
	input, problem := validate(request{
		Key:      " ui.nav.home ",
		Language: " en ",
		Text:     "  keep surrounding spaces  ",
	})
	if problem != nil {
		t.Fatalf("valid input rejected: %+v", problem.Fields)
	}
	if input.Key != "ui.nav.home" || input.Language != "en" {
		t.Fatalf("key/language should be trimmed, got key=%q language=%q", input.Key, input.Language)
	}
	if input.Text != "  keep surrounding spaces  " {
		t.Fatalf("message text should be preserved exactly, got %q", input.Text)
	}
}

func TestValidateMessageDescriptionLimitCountsRunes(t *testing.T) {
	description := strings.Repeat("ą", 1001)
	_, problem := validate(request{
		Key:         "ui.nav.home",
		Language:    "en",
		Text:        "Home",
		Description: &description,
	})
	if problem == nil {
		t.Fatal("expected overlong description to be rejected")
	}
	if len(problem.Fields) != 1 ||
		problem.Fields[0].Field != "description" ||
		problem.Fields[0].MessageKey != "validation.message.description.too-long" {
		t.Fatalf("unexpected validation problem: %+v", problem.Fields)
	}
}
