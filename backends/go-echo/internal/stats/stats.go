// Package stats serves the moderator dashboard (SPEC rule 19): totals, a
// 30-day zero-filled daily series, and the top tags. ETag revalidation comes
// from web.ETagMiddleware, as for message reads.
package stats

import (
	"context"
	"log/slog"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/kamkie/stackverse/backends/go-echo/internal/bookmarks"
	"github.com/kamkie/stackverse/backends/go-echo/internal/web"
)

const (
	days    = 30
	topTags = 10
)

type totals struct {
	Users           int64 `json:"users"`
	Bookmarks       int64 `json:"bookmarks"`
	PublicBookmarks int64 `json:"publicBookmarks"`
	HiddenBookmarks int64 `json:"hiddenBookmarks"`
	OpenReports     int64 `json:"openReports"`
}

type dailyStat struct {
	Date             string `json:"date"`
	BookmarksCreated int64  `json:"bookmarksCreated"`
	ActiveUsers      int64  `json:"activeUsers"`
}

type response struct {
	Totals  totals               `json:"totals"`
	Daily   []dailyStat          `json:"daily"`
	TopTags []bookmarks.TagCount `json:"topTags"`
}

type API struct {
	pool      *pgxpool.Pool
	bookmarks *bookmarks.Store
	localizer web.Localizer
	logger    *slog.Logger
}

func NewAPI(pool *pgxpool.Pool, bookmarkStore *bookmarks.Store, localizer web.Localizer, logger *slog.Logger) *API {
	return &API{pool: pool, bookmarks: bookmarkStore, localizer: localizer, logger: logger}
}

func (a *API) Get(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	body := response{}

	err := a.pool.QueryRow(ctx, `select
			(select count(*) from user_accounts),
			(select count(*) from bookmarks),
			(select count(*) from bookmarks where visibility = 'public'),
			(select count(*) from bookmarks where status = 'hidden'),
			(select count(*) from reports where status = 'open')`,
	).Scan(&body.Totals.Users, &body.Totals.Bookmarks, &body.Totals.PublicBookmarks,
		&body.Totals.HiddenBookmarks, &body.Totals.OpenReports)
	if err != nil {
		web.Error(w, r, a.localizer, a.logger, err)
		return
	}

	today := time.Now().UTC().Truncate(24 * time.Hour)
	from := today.AddDate(0, 0, -(days - 1))
	bookmarksCreated, err := a.countPerDay(ctx, "bookmarks", "created_at", from)
	if err != nil {
		web.Error(w, r, a.localizer, a.logger, err)
		return
	}
	activeUsers, err := a.countPerDay(ctx, "user_accounts", "last_seen", from)
	if err != nil {
		web.Error(w, r, a.localizer, a.logger, err)
		return
	}
	// SPEC rule 19: last 30 days including today, oldest first, zero-filled
	body.Daily = make([]dailyStat, days)
	for offset := range days {
		date := from.AddDate(0, 0, offset).Format(time.DateOnly)
		body.Daily[offset] = dailyStat{Date: date, BookmarksCreated: bookmarksCreated[date], ActiveUsers: activeUsers[date]}
	}

	tags, err := a.bookmarks.TopTags(ctx, topTags)
	if err != nil {
		web.Error(w, r, a.localizer, a.logger, err)
		return
	}
	if tags == nil {
		tags = []bookmarks.TagCount{}
	}
	body.TopTags = tags

	w.Header().Set("Cache-Control", "no-cache")
	web.WriteJSON(w, http.StatusOK, body)
}

// countPerDay groups rows by their UTC calendar day. The column name is one of
// two compile-time constants, never user input.
func (a *API) countPerDay(ctx context.Context, table, column string, from time.Time) (map[string]int64, error) {
	rows, err := a.pool.Query(ctx,
		"select ("+column+" at time zone 'UTC')::date as day, count(*) from "+table+
			" where "+column+" >= $1 group by day", from)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	counts := map[string]int64{}
	for rows.Next() {
		var day time.Time
		var count int64
		if err := rows.Scan(&day, &count); err != nil {
			return nil, err
		}
		counts[day.Format(time.DateOnly)] = count
	}
	return counts, rows.Err()
}
