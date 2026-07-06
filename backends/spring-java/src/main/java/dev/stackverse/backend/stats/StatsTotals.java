package dev.stackverse.backend.stats;

public record StatsTotals(
    long users,
    long bookmarks,
    long publicBookmarks,
    long hiddenBookmarks,
    long openReports
) {
}
