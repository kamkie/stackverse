package bookmarks

import (
	"log/slog"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/kamkie/stackverse/backends/go/internal/auth"
	"github.com/kamkie/stackverse/backends/go/internal/store"
	"github.com/kamkie/stackverse/backends/go/internal/web"
)

var tagPattern = regexp.MustCompile(`^[a-z0-9-]{1,30}$`)

type request struct {
	URL        string   `json:"url"`
	Title      string   `json:"title"`
	Notes      *string  `json:"notes"`
	Tags       []string `json:"tags"`
	Visibility *string  `json:"visibility"`
}

// Response is the wire shape of a bookmark; moderation reuses it for the
// status endpoint.
type Response struct {
	ID         uuid.UUID `json:"id"`
	URL        string    `json:"url"`
	Title      string    `json:"title"`
	Notes      *string   `json:"notes,omitempty"`
	Tags       []string  `json:"tags"`
	Visibility string    `json:"visibility"`
	Status     string    `json:"status"`
	Owner      string    `json:"owner"`
	CreatedAt  time.Time `json:"createdAt"`
	UpdatedAt  time.Time `json:"updatedAt"`
}

func ToResponse(b Bookmark) Response {
	tags := make([]string, len(b.Tags))
	copy(tags, b.Tags)
	sort.Strings(tags)
	return Response{
		ID: b.ID, URL: b.URL, Title: b.Title, Notes: b.Notes, Tags: tags,
		Visibility: b.Visibility, Status: b.Status, Owner: b.Owner,
		CreatedAt: b.CreatedAt, UpdatedAt: b.UpdatedAt,
	}
}

type cursorPageResponse struct {
	Items      []Response `json:"items"`
	NextCursor *string    `json:"nextCursor,omitempty"`
}

type API struct {
	store     *Store
	localizer web.Localizer
	logger    *slog.Logger
}

func NewAPI(store *Store, localizer web.Localizer, logger *slog.Logger) *API {
	return &API{store: store, localizer: localizer, logger: logger}
}

func (a *API) fail(w http.ResponseWriter, r *http.Request, err error) {
	web.Error(w, r, a.localizer, a.logger, err)
}

// parseListQuery validates the shared listing parameters and enforces rule 2:
// only `visibility=public` works anonymously.
func (a *API) parseListQuery(r *http.Request) (listQuery, *web.Problem) {
	values := r.URL.Query()
	q := values.Get("q")
	if problem := web.MaxLength(q, 200, "q"); problem != nil {
		return listQuery{}, problem
	}
	visibility := values.Get("visibility")
	if visibility != "" && visibility != VisibilityPublic && visibility != VisibilityPrivate {
		return listQuery{}, web.BadRequest("visibility must be one of: private, public")
	}
	tags, problem := validateQueryTags(values["tag"])
	if problem != nil {
		return listQuery{}, problem
	}
	query := listQuery{visibility: visibility, tags: tags, q: q}
	if visibility != VisibilityPublic {
		identity := auth.FromContext(r.Context())
		if identity == nil {
			return listQuery{}, web.Unauthorized("Authentication is required.")
		}
		query.caller = identity.Username
	}
	return query, nil
}

func (a *API) ListV1(w http.ResponseWriter, r *http.Request) {
	page, size, problem := web.Paging(r)
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	query, problem := a.parseListQuery(r)
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	items, total, err := a.store.listOffset(r.Context(), query, page, size)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	responses := make([]Response, len(items))
	for i, item := range items {
		responses[i] = ToResponse(item)
	}
	web.WriteJSON(w, http.StatusOK, web.NewPage(responses, page, size, total))
}

func (a *API) ListV2(w http.ResponseWriter, r *http.Request) {
	size, problem := web.PageSize(r)
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	query, problem := a.parseListQuery(r)
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	var cursor *Cursor
	if raw := r.URL.Query().Get("cursor"); raw != "" {
		decoded, problem := DecodeCursor(raw)
		if problem != nil {
			a.fail(w, r, problem)
			return
		}
		cursor = &decoded
	}
	items, next, err := a.store.listKeyset(r.Context(), query, cursor, size)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	page := cursorPageResponse{Items: make([]Response, len(items))}
	for i, item := range items {
		page.Items[i] = ToResponse(item)
	}
	if next != nil {
		encoded := next.Encode()
		page.NextCursor = &encoded
	}
	web.WriteJSON(w, http.StatusOK, page)
}

func (a *API) Create(w http.ResponseWriter, r *http.Request) {
	caller := auth.FromContext(r.Context()).Username
	var body request
	if problem := web.DecodeJSON(r, &body); problem != nil {
		a.fail(w, r, problem)
		return
	}
	input, problem := validate(body)
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	now := store.NowUTC()
	bookmark := Bookmark{
		ID: uuid.New(), Owner: caller, URL: input.url, Title: input.title, Notes: input.notes,
		Tags: input.tags, Visibility: input.visibility, Status: StatusActive,
		CreatedAt: now, UpdatedAt: now,
	}
	if err := a.store.insert(r.Context(), bookmark); err != nil {
		a.fail(w, r, err)
		return
	}
	w.Header().Set("Location", "/api/v1/bookmarks/"+bookmark.ID.String())
	web.WriteJSON(w, http.StatusCreated, ToResponse(bookmark))
}

func (a *API) Get(w http.ResponseWriter, r *http.Request) {
	id, problem := pathID(r)
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	bookmark, err := a.store.ByID(r.Context(), id)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	caller := ""
	if identity := auth.FromContext(r.Context()); identity != nil {
		caller = identity.Username
	}
	if !bookmark.VisibleTo(caller) {
		a.fail(w, r, web.NotFound())
		return
	}
	web.WriteJSON(w, http.StatusOK, ToResponse(bookmark))
}

func (a *API) Update(w http.ResponseWriter, r *http.Request) {
	caller := auth.FromContext(r.Context()).Username
	id, problem := pathID(r)
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	var body request
	if problem := web.DecodeJSON(r, &body); problem != nil {
		a.fail(w, r, problem)
		return
	}
	input, problem := validate(body)
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	// Lock the row and do the read → hidden-publish check → write in one
	// transaction: the moderator status endpoint takes the same lock, so a
	// concurrent hide cannot slip between the check and the write (SPEC rule 15).
	var updated Bookmark
	err := a.store.WithTx(r.Context(), func(tx pgx.Tx) error {
		bookmark, err := a.store.LockByID(r.Context(), tx, id)
		if err != nil {
			return err
		}
		// rule 1: a non-owner never learns the bookmark exists — 404, not 403
		if bookmark.Owner != caller {
			return web.NotFound()
		}
		// rule 15: a moderation-hidden bookmark cannot be (re)published by its owner
		if bookmark.Status == StatusHidden && input.visibility == VisibilityPublic {
			return web.ConflictKey("error.bookmark.hidden-publish")
		}
		bookmark.URL, bookmark.Title, bookmark.Notes = input.url, input.title, input.notes
		bookmark.Tags, bookmark.Visibility = input.tags, input.visibility
		bookmark.UpdatedAt = store.NowUTC()
		if err := a.store.updateTx(r.Context(), tx, bookmark); err != nil {
			return err
		}
		updated = bookmark
		return nil
	})
	if err != nil {
		a.fail(w, r, err)
		return
	}
	web.WriteJSON(w, http.StatusOK, ToResponse(updated))
}

func (a *API) Delete(w http.ResponseWriter, r *http.Request) {
	caller := auth.FromContext(r.Context()).Username
	id, problem := pathID(r)
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	bookmark, err := a.ownedByCaller(r, caller, id)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	if err := a.store.delete(r.Context(), bookmark.ID); err != nil {
		a.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *API) Tags(w http.ResponseWriter, r *http.Request) {
	caller := auth.FromContext(r.Context()).Username
	counts, err := a.store.TagCounts(r.Context(), caller)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	if counts == nil {
		counts = []TagCount{}
	}
	web.WriteJSON(w, http.StatusOK, map[string]any{"tags": counts})
}

// ownedByCaller implements rule 1: a non-owner never learns the bookmark
// exists — 404, not 403.
func (a *API) ownedByCaller(r *http.Request, caller string, id uuid.UUID) (Bookmark, error) {
	bookmark, err := a.store.ByID(r.Context(), id)
	if err != nil {
		return Bookmark{}, err
	}
	if bookmark.Owner != caller {
		return Bookmark{}, web.NotFound()
	}
	return bookmark, nil
}

type validated struct {
	url        string
	title      string
	notes      *string
	tags       []string
	visibility string
}

func validateQueryTags(raw []string) ([]string, *web.Problem) {
	tags := make([]string, len(raw))
	for i, tag := range raw {
		tags[i] = strings.ToLower(strings.TrimSpace(tag))
	}

	validator := &web.Validator{}
	for _, tag := range tags {
		if !tagPattern.MatchString(tag) {
			validator.Reject("tag", "validation.tag.invalid")
			break
		}
	}
	if problem := validator.Problem(); problem != nil {
		return nil, problem
	}
	return tags, nil
}

func validate(body request) (validated, *web.Problem) {
	validator := &web.Validator{}

	trimmedURL := strings.TrimSpace(body.URL)
	if trimmedURL == "" {
		validator.Reject("url", "validation.url.required")
	} else {
		validator.Check(utf8.RuneCountInString(trimmedURL) <= 2000 && isHTTPURL(trimmedURL), "url", "validation.url.invalid")
	}

	title := strings.TrimSpace(body.Title)
	validator.Check(title != "", "title", "validation.title.required")
	validator.Check(utf8.RuneCountInString(title) <= 200, "title", "validation.title.too-long")

	validator.Check(web.RuneLen(body.Notes) <= 4000, "notes", "validation.notes.too-long")

	// normalized before validation: " Kotlin " and "kotlin" are the same tag
	tags := make([]string, 0, len(body.Tags))
	seen := map[string]bool{}
	for _, tag := range body.Tags {
		normalized := strings.ToLower(strings.TrimSpace(tag))
		if !seen[normalized] {
			seen[normalized] = true
			tags = append(tags, normalized)
		}
	}
	validator.Check(len(tags) <= 10, "tags", "validation.tags.too-many")
	for _, tag := range tags {
		if !tagPattern.MatchString(tag) {
			validator.Reject("tags", "validation.tag.invalid")
			break
		}
	}

	visibility := VisibilityPrivate
	if body.Visibility != nil {
		visibility = *body.Visibility
		if visibility != VisibilityPrivate && visibility != VisibilityPublic {
			return validated{}, web.BadRequest("visibility must be one of: private, public")
		}
	}

	if problem := validator.Problem(); problem != nil {
		return validated{}, problem
	}
	return validated{url: trimmedURL, title: title, notes: body.Notes, tags: tags, visibility: visibility}, nil
}

func isHTTPURL(raw string) bool {
	parsed, err := url.Parse(raw)
	if err != nil {
		return false
	}
	return (parsed.Scheme == "http" || parsed.Scheme == "https") && parsed.Host != ""
}

func pathID(r *http.Request) (uuid.UUID, *web.Problem) {
	id, err := uuid.Parse(chi.URLParam(r, "id"))
	if err != nil {
		return uuid.Nil, web.BadRequest("id must be a UUID")
	}
	return id, nil
}
