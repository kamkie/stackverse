<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import { api, unwrap } from "../../api/client";
import { t, tCount } from "../../i18n/i18n";
import type { AdminStats } from "../../types";

const stats = ref<AdminStats | null>(null);
const error = ref<string | null>(null);

const maxValue = computed(() => {
  const values = stats.value?.daily.flatMap((day) => [
    day.bookmarksCreated,
    day.activeUsers,
  ]) ?? [1];
  return Math.max(1, ...values);
});

async function loadStats(): Promise<void> {
  try {
    stats.value = unwrap(await api.GET("/api/v1/admin/stats"));
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "Failed to load stats";
  }
}

function barHeight(value: number): number {
  return Math.round((value / maxValue.value) * 120);
}

onMounted(() => void loadStats());
</script>

<template>
  <h1 class="sv-page-title">{{ t("ui.admin.dashboard") }}</h1>
  <div v-if="error" class="sv-alert sv-alert--danger" role="alert">{{ error }}</div>
  <div v-else-if="!stats" class="sv-loading"><span class="sv-spinner" /></div>
  <template v-else>
    <div class="sv-stats-grid">
      <div class="sv-stat">
        <div class="sv-stat-value">{{ stats.totals.users }}</div>
        <div class="sv-stat-label">{{ t("ui.admin.stats.users") }}</div>
      </div>
      <div class="sv-stat">
        <div class="sv-stat-value">{{ stats.totals.bookmarks }}</div>
        <div class="sv-stat-label">{{ t("ui.admin.stats.bookmarks") }}</div>
      </div>
      <div class="sv-stat">
        <div class="sv-stat-value">{{ stats.totals.publicBookmarks }}</div>
        <div class="sv-stat-label">{{ t("ui.admin.stats.public-bookmarks") }}</div>
      </div>
      <div class="sv-stat">
        <div class="sv-stat-value">{{ stats.totals.hiddenBookmarks }}</div>
        <div class="sv-stat-label">{{ t("ui.admin.stats.hidden-bookmarks") }}</div>
      </div>
      <RouterLink to="/admin/reports" class="sv-stat sv-stat--link">
        <div class="sv-stat-value">{{ stats.totals.openReports }}</div>
        <div class="sv-stat-label">
          {{ tCount("ui.admin.stats.open-reports", stats.totals.openReports) }}
        </div>
      </RouterLink>
    </div>

    <section class="sv-card" :aria-label="t('ui.admin.chart.label')">
      <svg class="sv-chart" viewBox="0 0 600 180" role="img">
        <line class="sv-chart-axis" x1="20" y1="150" x2="580" y2="150" />
        <g v-for="(day, index) in stats.daily" :key="day.date">
          <rect
            class="sv-chart-bar"
            :x="24 + index * 18"
            :y="150 - barHeight(day.bookmarksCreated)"
            width="7"
            :height="barHeight(day.bookmarksCreated)"
          />
          <rect
            class="sv-chart-bar sv-chart-bar--secondary"
            :x="32 + index * 18"
            :y="150 - barHeight(day.activeUsers)"
            width="7"
            :height="barHeight(day.activeUsers)"
          />
        </g>
      </svg>
      <div class="sv-legend">
        <span><i class="sv-legend-swatch" />{{ t("ui.admin.stats.bookmarks-created") }}</span>
        <span>
          <i class="sv-legend-swatch sv-legend-swatch--secondary" />
          {{ t("ui.admin.stats.active-users") }}
        </span>
      </div>
    </section>
  </template>
</template>
