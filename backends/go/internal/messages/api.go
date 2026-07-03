package messages

import (
	"log/slog"
	"net/http"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"github.com/kamkie/stackverse/backends/go/internal/audit"
	"github.com/kamkie/stackverse/backends/go/internal/auth"
	"github.com/kamkie/stackverse/backends/go/internal/logx"
	"github.com/kamkie/stackverse/backends/go/internal/store"
	"github.com/kamkie/stackverse/backends/go/internal/web"
)

var (
	keyPattern      = regexp.MustCompile(`^[a-z0-9-]+(\.[a-z0-9-]+)*$`)
	languagePattern = regexp.MustCompile(`^[a-z]{2}$`)
)

type request struct {
	Key         string  `json:"key"`
	Language    string  `json:"language"`
	Text        string  `json:"text"`
	Description *string `json:"description"`
}

type response struct {
	ID          uuid.UUID `json:"id"`
	Key         string    `json:"key"`
	Language    string    `json:"language"`
	Text        string    `json:"text"`
	Description *string   `json:"description,omitempty"`
	CreatedAt   time.Time `json:"createdAt"`
	UpdatedAt   time.Time `json:"updatedAt"`
}

func toResponse(m Message) response {
	return response{
		ID: m.ID, Key: m.Key, Language: m.Language, Text: m.Text,
		Description: m.Description, CreatedAt: m.CreatedAt, UpdatedAt: m.UpdatedAt,
	}
}

type bundleResponse struct {
	Language string            `json:"language"`
	Messages map[string]string `json:"messages"`
}

// API serves the /api/v1/messages surface. ETag / If-None-Match / 304 comes
// from web.ETagMiddleware; handlers add Cache-Control: no-cache (SPEC rule 10).
type API struct {
	store  *Store
	audit  *audit.Service
	logger *slog.Logger
}

func NewAPI(store *Store, auditService *audit.Service, logger *slog.Logger) *API {
	return &API{store: store, audit: auditService, logger: logger}
}

func (a *API) fail(w http.ResponseWriter, r *http.Request, err error) {
	web.Error(w, r, a.store, a.logger, err)
}

func (a *API) List(w http.ResponseWriter, r *http.Request) {
	page, size, problem := web.Paging(r)
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	query := r.URL.Query()
	if problem := web.MaxLength(query.Get("q"), 200, "q"); problem != nil {
		a.fail(w, r, problem)
		return
	}
	items, total, err := a.store.search(r.Context(), query.Get("key"), query.Get("q"), query.Get("language"), page, size)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	responses := make([]response, len(items))
	for i, item := range items {
		responses[i] = toResponse(item)
	}
	w.Header().Set("Cache-Control", "no-cache")
	web.WriteJSON(w, http.StatusOK, web.NewPage(responses, page, size, total))
}

func (a *API) Bundle(w http.ResponseWriter, r *http.Request) {
	language := a.store.resolveRequest(r)
	texts, err := a.store.bundle(r.Context(), language)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Content-Language", language)
	web.WriteJSON(w, http.StatusOK, bundleResponse{Language: language, Messages: texts})
}

func (a *API) Get(w http.ResponseWriter, r *http.Request) {
	id, problem := pathID(r)
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	message, err := a.store.byID(r.Context(), id)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	w.Header().Set("Cache-Control", "no-cache")
	web.WriteJSON(w, http.StatusOK, toResponse(message))
}

func (a *API) Create(w http.ResponseWriter, r *http.Request) {
	actor := auth.FromContext(r.Context()).Username
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
	conflicting, err := a.store.existsConflicting(r.Context(), input.Key, input.Language, uuid.Nil)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	if conflicting {
		a.fail(w, r, conflictProblem(input.Key, input.Language))
		return
	}
	now := store.NowUTC()
	message := Message{
		ID: uuid.New(), Key: input.Key, Language: input.Language, Text: input.Text,
		Description: input.Description, CreatedAt: now, UpdatedAt: now,
	}
	if err := a.store.insert(r.Context(), message); err != nil {
		a.fail(w, r, err)
		return
	}
	if err := a.recordAndLog(r, "message.created", "message_created", "Message created", actor, message); err != nil {
		a.fail(w, r, err)
		return
	}
	w.Header().Set("Location", "/api/v1/messages/"+message.ID.String())
	web.WriteJSON(w, http.StatusCreated, toResponse(message))
}

func (a *API) Update(w http.ResponseWriter, r *http.Request) {
	actor := auth.FromContext(r.Context()).Username
	id, problem := pathID(r)
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	message, err := a.store.byID(r.Context(), id)
	if err != nil {
		a.fail(w, r, err)
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
	conflicting, err := a.store.existsConflicting(r.Context(), input.Key, input.Language, message.ID)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	if conflicting {
		a.fail(w, r, conflictProblem(input.Key, input.Language))
		return
	}
	message.Key, message.Language, message.Text, message.Description = input.Key, input.Language, input.Text, input.Description
	message.UpdatedAt = store.NowUTC()
	if err := a.store.update(r.Context(), message); err != nil {
		a.fail(w, r, err)
		return
	}
	if err := a.recordAndLog(r, "message.updated", "message_updated", "Message updated", actor, message); err != nil {
		a.fail(w, r, err)
		return
	}
	web.WriteJSON(w, http.StatusOK, toResponse(message))
}

func (a *API) Delete(w http.ResponseWriter, r *http.Request) {
	actor := auth.FromContext(r.Context()).Username
	id, problem := pathID(r)
	if problem != nil {
		a.fail(w, r, problem)
		return
	}
	message, err := a.store.byID(r.Context(), id)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	if err := a.store.delete(r.Context(), message.ID); err != nil {
		a.fail(w, r, err)
		return
	}
	if err := a.recordAndLog(r, "message.deleted", "message_deleted", "Message deleted", actor, message); err != nil {
		a.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// recordAndLog writes the audit entry (the authoritative record) and the
// diagnostic log event (docs/LOGGING.md §5). The message key is safe to log:
// validated against keyPattern, so no free-form client text.
func (a *API) recordAndLog(r *http.Request, action, event, description, actor string, message Message) error {
	err := a.audit.Record(r.Context(), actor, action, "message", message.ID.String(), map[string]any{
		"key":         message.Key,
		"language":    message.Language,
		"text":        message.Text,
		"description": message.Description,
	})
	if err != nil {
		return err
	}
	logx.Event(r.Context(), a.logger, slog.LevelInfo, event, "success", description,
		slog.String("actor", actor),
		slog.String("resource_type", "message"),
		slog.String("resource_id", message.ID.String()),
		slog.String("message_key", message.Key),
		slog.String("language", message.Language),
	)
	return nil
}

type validated struct {
	Key         string
	Language    string
	Text        string
	Description *string
}

func validate(body request) (validated, *web.Problem) {
	validator := &web.Validator{}
	key := strings.TrimSpace(body.Key)
	validator.Check(keyPattern.MatchString(key) && utf8.RuneCountInString(key) <= 150, "key", "validation.message.key.invalid")
	language := strings.TrimSpace(body.Language)
	validator.Check(languagePattern.MatchString(language), "language", "validation.message.language.invalid")
	validator.Check(body.Text != "", "text", "validation.message.text.required")
	validator.Check(utf8.RuneCountInString(body.Text) <= 2000, "text", "validation.message.text.too-long")
	validator.Check(web.RuneLen(body.Description) <= 1000, "description", "validation.message.description.too-long")
	if problem := validator.Problem(); problem != nil {
		return validated{}, problem
	}
	return validated{Key: key, Language: language, Text: body.Text, Description: body.Description}, nil
}

func pathID(r *http.Request) (uuid.UUID, *web.Problem) {
	id, err := uuid.Parse(chi.URLParam(r, "id"))
	if err != nil {
		return uuid.Nil, web.BadRequest("id must be a UUID")
	}
	return id, nil
}
