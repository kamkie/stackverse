package accounts

import (
	"testing"
	"time"
)

func TestToResponseIncludesDerivedBookmarkCountAndBlockedReason(t *testing.T) {
	reason := "policy violation"
	firstSeen := time.Date(2026, 7, 1, 8, 0, 0, 0, time.UTC)
	lastSeen := time.Date(2026, 7, 3, 9, 30, 0, 0, time.UTC)

	response := toResponse(accountRow{
		Account: Account{
			Username:      "demo",
			FirstSeen:     firstSeen,
			LastSeen:      lastSeen,
			Status:        StatusBlocked,
			BlockedReason: &reason,
		},
		BookmarkCount: 42,
	})

	if response.Username != "demo" ||
		!response.FirstSeen.Equal(firstSeen) ||
		!response.LastSeen.Equal(lastSeen) ||
		response.Status != StatusBlocked ||
		response.BlockedReason == nil ||
		*response.BlockedReason != reason ||
		response.BookmarkCount != 42 {
		t.Fatalf("unexpected account response: %+v", response)
	}
}
