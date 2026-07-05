package audit

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
)

type fakeExecutor struct {
	args []any
}

func (f *fakeExecutor) Exec(_ context.Context, _ string, arguments ...any) (pgconn.CommandTag, error) {
	f.args = arguments
	return pgconn.CommandTag{}, nil
}

func TestRecordSerializesDetailJSON(t *testing.T) {
	exec := &fakeExecutor{}
	err := record(context.Background(), exec, "moderator", "report.resolved", "report", "r1", map[string]any{
		"resolution": "actioned",
		"auto":       true,
	})
	if err != nil {
		t.Fatalf("record returned %v", err)
	}
	if len(exec.args) != 7 {
		t.Fatalf("expected 7 insert args, got %d", len(exec.args))
	}

	detail, ok := exec.args[5].([]byte)
	if !ok || len(detail) == 0 {
		t.Fatalf("detail arg must be JSON bytes, got %#v", exec.args[5])
	}
	var decoded map[string]any
	if err := json.Unmarshal(detail, &decoded); err != nil {
		t.Fatalf("detail JSON is invalid: %v", err)
	}
	if decoded["resolution"] != "actioned" || decoded["auto"] != true {
		t.Fatalf("unexpected detail JSON: %v", decoded)
	}
	if _, ok := exec.args[6].(time.Time); !ok {
		t.Fatalf("created_at arg must be a timestamp, got %T", exec.args[6])
	}
}

func TestRecordAllowsNilDetail(t *testing.T) {
	exec := &fakeExecutor{}
	if err := record(context.Background(), exec, "admin", "user.unblocked", "user", "demo", nil); err != nil {
		t.Fatalf("record returned %v", err)
	}
	detail, ok := exec.args[5].([]byte)
	if !ok {
		t.Fatalf("nil detail should still be passed as []byte, got %T", exec.args[5])
	}
	if detail != nil {
		t.Fatalf("nil detail encoded as %q", string(detail))
	}
}

func TestRecordRejectsUnserializableDetail(t *testing.T) {
	exec := &fakeExecutor{}
	err := record(context.Background(), exec, "admin", "message.created", "message", "m1", map[string]any{
		"bad": make(chan int),
	})
	if err == nil {
		t.Fatal("expected unserializable detail to fail")
	}
	if len(exec.args) != 0 {
		t.Fatal("record must not insert when detail cannot be serialized")
	}
}

func TestTimeParam(t *testing.T) {
	if parsed, problem := timeParam("", "from"); parsed != nil || problem != nil {
		t.Fatalf("empty time filter must be absent, got parsed=%v problem=%v", parsed, problem)
	}

	raw := "2026-07-03T10:11:12.123456Z"
	parsed, problem := timeParam(raw, "from")
	if problem != nil {
		t.Fatalf("valid RFC3339 time rejected: %v", problem)
	}
	if parsed.Format(time.RFC3339Nano) != raw {
		t.Fatalf("parsed time = %s, want %s", parsed.Format(time.RFC3339Nano), raw)
	}

	_, problem = timeParam("2026-07-03", "to")
	if problem == nil || problem.Status != 400 {
		t.Fatalf("invalid time must return a 400 problem, got %v", problem)
	}
}
