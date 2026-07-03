// Package messages implements the runtime-managed localized messages
// (SPEC rules 7–12): public reads with ETag revalidation, admin-only writes,
// language resolution, the startup seed, and the localizer the rest of the
// API uses for problem details.
package messages

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/kamkie/stackverse/backends/go/internal/store"
	"github.com/kamkie/stackverse/backends/go/internal/web"
)

type Message struct {
	ID          uuid.UUID
	Key         string
	Language    string
	Text        string
	Description *string
	CreatedAt   time.Time
	UpdatedAt   time.Time
}

type Store struct {
	pool *pgxpool.Pool
}

func NewStore(pool *pgxpool.Pool) *Store {
	return &Store{pool: pool}
}

const messageColumns = "id, key, language, text, description, created_at, updated_at"

func scanMessage(row pgx.Row) (Message, error) {
	var m Message
	err := row.Scan(&m.ID, &m.Key, &m.Language, &m.Text, &m.Description, &m.CreatedAt, &m.UpdatedAt)
	return m, err
}

func (s *Store) byID(ctx context.Context, id uuid.UUID) (Message, error) {
	message, err := scanMessage(s.pool.QueryRow(ctx,
		"select "+messageColumns+" from messages where id = $1", id))
	if errors.Is(err, pgx.ErrNoRows) {
		return Message{}, web.NotFound()
	}
	return message, err
}

// search filters by exact key, exact language, and a case-insensitive
// substring over key and text (SPEC rule 7), ordered by key then language.
func (s *Store) search(ctx context.Context, key, q, language string, page, size int) ([]Message, int64, error) {
	where := "where ($1 = '' or key = $1) and ($2 = '' or language = $2)" +
		` and ($3 = '' or lower(key) like $3 escape '\' or lower(text) like $3 escape '\')`
	qLike := ""
	if q != "" {
		qLike = "%" + web.EscapeLike(strings.ToLower(q)) + "%"
	}
	var total int64
	if err := s.pool.QueryRow(ctx,
		"select count(*) from messages "+where, key, language, qLike,
	).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.pool.Query(ctx,
		"select "+messageColumns+" from messages "+where+" order by key, language limit $4 offset $5",
		key, language, qLike, size, page*size)
	if err != nil {
		return nil, 0, err
	}
	items, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (Message, error) { return scanMessage(row) })
	return items, total, err
}

// bundle is the flat key → text map for one language (SPEC rule 9): every key
// of the resolved language plus `en` keys the language is missing.
func (s *Store) bundle(ctx context.Context, language string) (map[string]string, error) {
	rows, err := s.pool.Query(ctx,
		"select key, language, text from messages where language = any($1)",
		[]string{DefaultLanguage, language})
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	texts := map[string]string{}
	for rows.Next() {
		var key, lang, text string
		if err := rows.Scan(&key, &lang, &text); err != nil {
			return nil, err
		}
		if lang == language {
			texts[key] = text
		} else if _, present := texts[key]; !present {
			texts[key] = text
		}
	}
	return texts, rows.Err()
}

func (s *Store) distinctLanguages(ctx context.Context) (map[string]bool, error) {
	rows, err := s.pool.Query(ctx, "select distinct language from messages")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	supported := map[string]bool{}
	for rows.Next() {
		var language string
		if err := rows.Scan(&language); err != nil {
			return nil, err
		}
		supported[language] = true
	}
	return supported, rows.Err()
}

func (s *Store) textFor(ctx context.Context, key, language string) (string, bool) {
	var text string
	err := s.pool.QueryRow(ctx,
		"select text from messages where key = $1 and language = $2", key, language,
	).Scan(&text)
	return text, err == nil
}

func (s *Store) existsConflicting(ctx context.Context, key, language string, excluding uuid.UUID) (bool, error) {
	var exists bool
	err := s.pool.QueryRow(ctx,
		"select exists (select 1 from messages where key = $1 and language = $2 and id <> $3)",
		key, language, excluding,
	).Scan(&exists)
	return exists, err
}

// The write statements run on a caller-supplied Querier so the audit entry
// can share their transaction (SPEC rule 18: the mutation and its audit
// record commit or roll back together).

func insert(ctx context.Context, db store.Querier, m Message) error {
	_, err := db.Exec(ctx,
		"insert into messages ("+messageColumns+") values ($1, $2, $3, $4, $5, $6, $7)",
		m.ID, m.Key, m.Language, m.Text, m.Description, m.CreatedAt, m.UpdatedAt)
	return mapUniqueViolation(err, m)
}

func update(ctx context.Context, db store.Querier, m Message) error {
	_, err := db.Exec(ctx,
		"update messages set key = $2, language = $3, text = $4, description = $5, updated_at = $6 where id = $1",
		m.ID, m.Key, m.Language, m.Text, m.Description, m.UpdatedAt)
	return mapUniqueViolation(err, m)
}

func remove(ctx context.Context, db store.Querier, id uuid.UUID) error {
	_, err := db.Exec(ctx, "delete from messages where id = $1", id)
	return err
}

// InTx exposes the pool to the API layer for mutation+audit transactions.
func (s *Store) InTx(ctx context.Context, fn func(tx pgx.Tx) error) error {
	return store.InTx(ctx, s.pool, fn)
}

// mapUniqueViolation turns a lost race on uq_messages_key_language into the
// same 409 the pre-check produces — two concurrent creates of one
// (key, language) must not surface as a 500.
func mapUniqueViolation(err error, m Message) error {
	var pgError *pgconn.PgError
	if errors.As(err, &pgError) && pgError.Code == "23505" {
		return conflictProblem(m.Key, m.Language)
	}
	return err
}

func conflictProblem(key, language string) *web.Problem {
	return web.Conflict(fmt.Sprintf("A message with key '%s' and language '%s' already exists.", key, language))
}
