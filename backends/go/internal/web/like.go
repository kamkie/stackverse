package web

import (
	"strings"
	"unicode/utf8"
)

// EscapeLike makes a user-supplied substring safe inside a LIKE pattern —
// wildcards in the query are literals, not patterns. Pair with `escape '\'`.
func EscapeLike(value string) string {
	value = strings.ReplaceAll(value, `\`, `\\`)
	value = strings.ReplaceAll(value, `%`, `\%`)
	return strings.ReplaceAll(value, `_`, `\_`)
}

// RuneLen is the length of an optional string in runes — the contract's
// character limits count characters, not bytes.
func RuneLen(value *string) int {
	if value == nil {
		return 0
	}
	return utf8.RuneCountInString(*value)
}
