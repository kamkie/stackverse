package messages

import (
	"net/http"
	"sort"
	"strconv"
	"strings"
)

// DefaultLanguage is the final fallback of the resolution chain (SPEC rule 8).
const DefaultLanguage = "en"

// ResolveLanguage implements SPEC rule 8: explicit `lang` parameter → first
// supported language in `Accept-Language` (quality-ordered) → `en`.
// Unsupported values fall back down the chain, never error. "Supported" means
// at least one message exists in that language.
func ResolveLanguage(lang, acceptLanguage string, supported map[string]bool) string {
	if lang != "" && supported[lang] {
		return lang
	}
	for _, code := range acceptedLanguages(acceptLanguage) {
		if supported[code] {
			return code
		}
	}
	return DefaultLanguage
}

// acceptedLanguages parses an Accept-Language header into primary subtags,
// highest quality first (stable for equal qualities). Malformed entries are
// skipped — resolution must never error on client input.
func acceptedLanguages(header string) []string {
	type entry struct {
		code    string
		quality float64
		index   int
	}
	var entries []entry
	for index, part := range strings.Split(header, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		tag, parameters, _ := strings.Cut(part, ";")
		tag = strings.TrimSpace(tag)
		if tag == "" || tag == "*" {
			continue
		}
		quality := 1.0
		for _, parameter := range strings.Split(parameters, ";") {
			name, value, ok := strings.Cut(strings.TrimSpace(parameter), "=")
			if ok && strings.TrimSpace(name) == "q" {
				if parsed, err := strconv.ParseFloat(strings.TrimSpace(value), 64); err == nil {
					quality = parsed
				}
			}
		}
		if quality <= 0 {
			continue
		}
		code, _, _ := strings.Cut(tag, "-")
		entries = append(entries, entry{code: strings.ToLower(code), quality: quality, index: index})
	}
	sort.SliceStable(entries, func(a, b int) bool { return entries[a].quality > entries[b].quality })
	codes := make([]string, len(entries))
	for i, e := range entries {
		codes[i] = e.code
	}
	return codes
}

// resolveRequest resolves the language for one request against the languages
// currently present in the messages table.
func (s *Store) resolveRequest(r *http.Request) string {
	supported, err := s.distinctLanguages(r.Context())
	if err != nil {
		return DefaultLanguage
	}
	return ResolveLanguage(r.URL.Query().Get("lang"), r.Header.Get("Accept-Language"), supported)
}

// Localize resolves a message key to localized text for the current request
// (SPEC rule 11): language per rule 8, text from the messages table, `en`
// fallback, and finally the key itself if no text exists at all. Implements
// web.Localizer.
func (s *Store) Localize(r *http.Request, key string) string {
	language := s.resolveRequest(r)
	if text, ok := s.textFor(r.Context(), key, language); ok {
		return text
	}
	if language != DefaultLanguage {
		if text, ok := s.textFor(r.Context(), key, DefaultLanguage); ok {
			return text
		}
	}
	return key
}
