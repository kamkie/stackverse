package messages

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/google/uuid"

	"github.com/kamkie/stackverse/backends/go-echo/internal/logx"
	"github.com/kamkie/stackverse/backends/go-echo/internal/store"
)

// Seed implements SPEC rule 12: import the JSON files from spec/messages
// (language = filename), inserting only (key, language) pairs that don't exist
// yet — runtime edits by admins survive restarts. Seed inserts are not
// moderator actions, so they are deliberately not audited.
func (s *Store) Seed(ctx context.Context, dir string, logger *slog.Logger) error {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return fmt.Errorf("message seed directory not found: %s — set SEED_MESSAGES_DIR to the spec/messages directory: %w", dir, err)
	}
	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasSuffix(entry.Name(), ".json") {
			names = append(names, entry.Name())
		}
	}
	sort.Strings(names)
	for _, name := range names {
		if err := s.seedLanguage(ctx, dir, name, logger); err != nil {
			return err
		}
	}
	return nil
}

func (s *Store) seedLanguage(ctx context.Context, dir, name string, logger *slog.Logger) error {
	language := strings.TrimSuffix(name, ".json")
	raw, err := os.ReadFile(filepath.Join(dir, name))
	if err != nil {
		return err
	}
	var texts map[string]string
	if err := json.Unmarshal(raw, &texts); err != nil {
		return fmt.Errorf("message seed %s: %w", name, err)
	}
	keys := make([]string, 0, len(texts))
	for key := range texts {
		keys = append(keys, key)
	}
	sort.Strings(keys)

	transaction, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = transaction.Rollback(ctx) }()

	now := store.NowUTC()
	inserted := 0
	for _, key := range keys {
		tag, err := transaction.Exec(ctx,
			`insert into messages (id, key, language, text, description, created_at, updated_at)
			 values ($1, $2, $3, $4, null, $5, $5)
			 on conflict (key, language) do nothing`,
			uuid.New(), key, language, texts[key], now)
		if err != nil {
			return err
		}
		inserted += int(tag.RowsAffected())
	}
	if err := transaction.Commit(ctx); err != nil {
		return err
	}
	logx.Event(ctx, logger, slog.LevelInfo, "message_seed_imported", "success",
		fmt.Sprintf("Message seed '%s': %d inserted, %d already present", language, inserted, len(keys)-inserted),
		slog.String("language", language),
		slog.Int("inserted", inserted),
		slog.Int("skipped", len(keys)-inserted),
	)
	return nil
}
