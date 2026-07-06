package dev.stackverse.backend.stats;

import java.time.LocalDate;

public record DailyStat(LocalDate date, long bookmarksCreated, long activeUsers) {
}
