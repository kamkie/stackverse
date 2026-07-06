package web

import (
	"net/http"
	"strconv"
	"unicode/utf8"
)

// Page is the offset-paginated envelope shared by every v1 listing.
type Page[T any] struct {
	Items      []T   `json:"items"`
	Page       int   `json:"page"`
	Size       int   `json:"size"`
	TotalItems int64 `json:"totalItems"`
	TotalPages int   `json:"totalPages"`
}

func NewPage[T any](items []T, page, size int, totalItems int64) Page[T] {
	if items == nil {
		items = []T{}
	}
	totalPages := int((totalItems + int64(size) - 1) / int64(size))
	return Page[T]{Items: items, Page: page, Size: size, TotalItems: totalItems, TotalPages: totalPages}
}

// Paging parses and bounds the shared `page`/`size` query parameters
// (spec: page ≥ 0, size 1–100, defaults 0/20).
func Paging(r *http.Request) (page, size int, problem *Problem) {
	page, problem = intParam(r, "page", 0)
	if problem != nil {
		return 0, 0, problem
	}
	size, problem = PageSize(r)
	if problem != nil {
		return 0, 0, problem
	}
	if page < 0 {
		return 0, 0, BadRequest("page must not be negative")
	}
	return page, size, nil
}

// PageSize parses and bounds only `size` — the cursor-paginated v2 listing has
// no `page` parameter, so an unknown `page` value must be ignored, not 400ed.
func PageSize(r *http.Request) (int, *Problem) {
	size, problem := intParam(r, "size", 20)
	if problem != nil {
		return 0, problem
	}
	if size < 1 || size > 100 {
		return 0, BadRequest("size must be between 1 and 100")
	}
	return size, nil
}

func intParam(r *http.Request, name string, fallback int) (int, *Problem) {
	raw := r.URL.Query().Get(name)
	if raw == "" {
		return fallback, nil
	}
	value, err := strconv.Atoi(raw)
	if err != nil {
		return 0, BadRequest(name + " must be an integer")
	}
	return value, nil
}

// MaxLength bounds free-text query parameters (`q`) per the OpenAPI contract —
// in characters, not bytes.
func MaxLength(value string, max int, name string) *Problem {
	if utf8.RuneCountInString(value) > max {
		return BadRequest(name + " must be at most " + strconv.Itoa(max) + " characters")
	}
	return nil
}
