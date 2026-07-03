// Package bookmarks implements the bookmark domain: CRUD with ownership
// masking (SPEC rule 1), the anonymous public surface (rule 2), the v1/v2
// listing exhibit (offset vs keyset pagination), and the caller's tag counts.
package bookmarks

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/kamkie/stackverse/backends/go/internal/web"
)

// Wire values, stored as-is (the schema has no enum mapping layer).
const (
	VisibilityPrivate = "private"
	VisibilityPublic  = "public"
	StatusActive      = "active"
	StatusHidden      = "hidden"
)

type Bookmark struct {
	ID         uuid.UUID
	Owner      string
	URL        string
	Title      string
	Notes      *string
	Tags       []string
	Visibility string
	Status     string
	CreatedAt  time.Time
	UpdatedAt  time.Time
}

// VisibleTo implements rule 1: owners always see their bookmarks, others only
// public, non-hidden ones.
func (b Bookmark) VisibleTo(caller string) bool {
	return b.Owner == caller || (b.Visibility == VisibilityPublic && b.Status == StatusActive)
}

type Store struct {
	pool *pgxpool.Pool
}

func NewStore(pool *pgxpool.Pool) *Store {
	return &Store{pool: pool}
}

// Querier runs read queries within either the pool or a transaction; both
// *pgxpool.Pool and pgx.Tx satisfy it.
type Querier interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

const bookmarkColumns = "id, owner, url, title, notes, tags, visibility, status, created_at, updated_at"

func scanBookmark(row pgx.Row) (Bookmark, error) {
	var b Bookmark
	err := row.Scan(&b.ID, &b.Owner, &b.URL, &b.Title, &b.Notes, &b.Tags, &b.Visibility, &b.Status, &b.CreatedAt, &b.UpdatedAt)
	return b, err
}

// ByID returns the row or a 404 problem; visibility masking is the caller's job.
func (s *Store) ByID(ctx context.Context, id uuid.UUID) (Bookmark, error) {
	bookmark, err := scanBookmark(s.pool.QueryRow(ctx,
		"select "+bookmarkColumns+" from bookmarks where id = $1", id))
	if errors.Is(err, pgx.ErrNoRows) {
		return Bookmark{}, web.NotFound()
	}
	return bookmark, err
}

// LockByID reads a bookmark row under FOR UPDATE using q (a pool or a
// transaction). The owner update and the moderator status endpoint both take
// this lock, so a hidden-publish check (SPEC rule 15) and a concurrent hide
// serialize on the row instead of racing.
func (s *Store) LockByID(ctx context.Context, q Querier, id uuid.UUID) (Bookmark, error) {
	bookmark, err := scanBookmark(q.QueryRow(ctx,
		"select "+bookmarkColumns+" from bookmarks where id = $1 for update", id))
	if errors.Is(err, pgx.ErrNoRows) {
		return Bookmark{}, web.NotFound()
	}
	return bookmark, err
}

// WithTx runs fn inside a transaction, rolling back on error and committing on
// success.
func (s *Store) WithTx(ctx context.Context, fn func(tx pgx.Tx) error) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := fn(tx); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (s *Store) insert(ctx context.Context, b Bookmark) error {
	_, err := s.pool.Exec(ctx,
		"insert into bookmarks ("+bookmarkColumns+") values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)",
		b.ID, b.Owner, b.URL, b.Title, b.Notes, b.Tags, b.Visibility, b.Status, b.CreatedAt, b.UpdatedAt)
	return err
}

// updateTx writes the mutable bookmark fields within tx; the owner update runs
// it under the row lock taken by LockByID.
func (s *Store) updateTx(ctx context.Context, tx pgx.Tx, b Bookmark) error {
	_, err := tx.Exec(ctx,
		"update bookmarks set url = $2, title = $3, notes = $4, tags = $5, visibility = $6, updated_at = $7 where id = $1",
		b.ID, b.URL, b.Title, b.Notes, b.Tags, b.Visibility, b.UpdatedAt)
	return err
}

func (s *Store) delete(ctx context.Context, id uuid.UUID) error {
	_, err := s.pool.Exec(ctx, "delete from bookmarks where id = $1", id)
	return err
}

// listQuery is the filter set shared by the v1 and v2 listings — both versions
// are representations of the same logic (SPEC "API versioning").
type listQuery struct {
	// caller is empty for anonymous requests; the handler has already
	// guaranteed that anonymous callers only reach the public scope.
	caller     string
	visibility string
	tags       []string
	q          string
}

// where builds the filter clause. Rules 2 + 3: `visibility=public` is the
// anonymous-capable public feed across all owners (hidden excluded); every
// other listing is the caller's own bookmarks.
func (q listQuery) where() (string, []any) {
	var conditions []string
	var args []any
	arg := func(value any) string {
		args = append(args, value)
		return fmt.Sprintf("$%d", len(args))
	}
	if q.visibility == VisibilityPublic {
		conditions = append(conditions, "visibility = 'public' and status = 'active'")
	} else {
		conditions = append(conditions, "owner = "+arg(q.caller))
		if q.visibility != "" {
			conditions = append(conditions, "visibility = "+arg(q.visibility))
		}
	}
	if len(q.tags) > 0 {
		// containment of the whole array = every tag present (AND semantics)
		conditions = append(conditions, "tags @> "+arg(q.tags))
	}
	if q.q != "" {
		pattern := "%" + web.EscapeLike(strings.ToLower(q.q)) + "%"
		placeholder := arg(pattern)
		conditions = append(conditions,
			`(lower(title) like `+placeholder+` escape '\' or lower(coalesce(notes, '')) like `+placeholder+` escape '\')`)
	}
	return "where " + strings.Join(conditions, " and "), args
}

// listOffset serves v1: offset pagination with totals, newest first.
func (s *Store) listOffset(ctx context.Context, query listQuery, page, size int) ([]Bookmark, int64, error) {
	where, args := query.where()
	var total int64
	if err := s.pool.QueryRow(ctx, "select count(*) from bookmarks "+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.pool.Query(ctx,
		fmt.Sprintf("select %s from bookmarks %s order by created_at desc, id desc limit $%d offset $%d",
			bookmarkColumns, where, len(args)+1, len(args)+2),
		append(args, size, page*size)...)
	if err != nil {
		return nil, 0, err
	}
	items, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (Bookmark, error) { return scanBookmark(row) })
	return items, total, err
}

// listKeyset serves v2: fetch one row past the page to know whether a next
// cursor exists. `(created_at, id)` strictly before the cursor position under
// the total descending order.
func (s *Store) listKeyset(ctx context.Context, query listQuery, cursor *Cursor, size int) ([]Bookmark, *Cursor, error) {
	where, args := query.where()
	if cursor != nil {
		where += fmt.Sprintf(" and (created_at, id) < ($%d, $%d)", len(args)+1, len(args)+2)
		args = append(args, cursor.CreatedAt, cursor.ID)
	}
	rows, err := s.pool.Query(ctx,
		fmt.Sprintf("select %s from bookmarks %s order by created_at desc, id desc limit $%d",
			bookmarkColumns, where, len(args)+1),
		append(args, size+1)...)
	if err != nil {
		return nil, nil, err
	}
	fetched, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (Bookmark, error) { return scanBookmark(row) })
	if err != nil {
		return nil, nil, err
	}
	if len(fetched) <= size {
		return fetched, nil, nil
	}
	items := fetched[:size]
	last := items[len(items)-1]
	return items, &Cursor{CreatedAt: last.CreatedAt, ID: last.ID}, nil
}

type TagCount struct {
	Tag   string `json:"tag"`
	Count int64  `json:"count"`
}

// TagCounts returns the caller's tags with usage counts, most used first
// (SPEC rule 4).
func (s *Store) TagCounts(ctx context.Context, owner string) ([]TagCount, error) {
	rows, err := s.pool.Query(ctx,
		`select t.tag, count(*) from bookmarks b cross join unnest(b.tags) as t(tag)
		 where b.owner = $1 group by t.tag order by count(*) desc, t.tag`, owner)
	if err != nil {
		return nil, err
	}
	return collectTagCounts(rows)
}

// TopTags returns the top tags by usage across all users (SPEC rule 19).
func (s *Store) TopTags(ctx context.Context, limit int) ([]TagCount, error) {
	rows, err := s.pool.Query(ctx,
		`select t.tag, count(*) from bookmarks b cross join unnest(b.tags) as t(tag)
		 group by t.tag order by count(*) desc, t.tag limit $1`, limit)
	if err != nil {
		return nil, err
	}
	return collectTagCounts(rows)
}

func collectTagCounts(rows pgx.Rows) ([]TagCount, error) {
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (TagCount, error) {
		var t TagCount
		err := row.Scan(&t.Tag, &t.Count)
		return t, err
	})
}
