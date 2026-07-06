package dev.stackverse.backend.stats;

import dev.stackverse.backend.bookmark.TagCountResponse;
import java.util.List;

public record AdminStatsResponse(StatsTotals totals, List<DailyStat> daily, List<TagCountResponse> topTags) {
}
