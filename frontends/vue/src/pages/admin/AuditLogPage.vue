<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { api, unwrap } from "../../api/client";
import Pagination from "../../components/Pagination.vue";
import { formatDateTime } from "../../forms";
import { t, resolvedLanguage } from "../../i18n/i18n";
import type { AuditEntry } from "../../types";

const knownActions = [
  "message.created",
  "message.updated",
  "message.deleted",
  "report.resolved",
  "bookmark.status-changed",
  "user.blocked",
  "user.unblocked",
];

const entries = ref<AuditEntry[]>([]);
const actor = ref("");
const action = ref("");
const from = ref("");
const to = ref("");
const page = ref(0);
const totalPages = ref(1);
const loading = ref(false);
const error = ref<string | null>(null);

let reloadTimer: number | undefined;

function endOfDayIso(day: string): string {
  return new Date(`${day}T23:59:59.999`).toISOString().replace(".999Z", ".999999Z");
}

async function loadAudit(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const result = unwrap(
      await api.GET("/api/v1/admin/audit-log", {
        params: {
          query: {
            page: page.value,
            size: 20,
            ...(actor.value ? { actor: actor.value } : {}),
            ...(action.value ? { action: action.value } : {}),
            ...(from.value ? { from: new Date(`${from.value}T00:00:00`).toISOString() } : {}),
            ...(to.value ? { to: endOfDayIso(to.value) } : {}),
          },
        },
      }),
    );
    entries.value = result.items;
    totalPages.value = result.totalPages;
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "Failed to load audit log";
  } finally {
    loading.value = false;
  }
}

function scheduleReload(): void {
  if (reloadTimer !== undefined) window.clearTimeout(reloadTimer);
  reloadTimer = window.setTimeout(() => {
    page.value = 0;
    void loadAudit();
  }, 250);
}

function clearFilters(): void {
  actor.value = "";
  action.value = "";
  from.value = "";
  to.value = "";
  page.value = 0;
  void loadAudit();
}

watch([actor, action, from, to], scheduleReload);
watch(page, () => void loadAudit());
onMounted(() => void loadAudit());
</script>

<template>
  <h1 class="sv-page-title">{{ t("ui.admin.audit") }}</h1>
  <div class="sv-toolbar">
    <input v-model="actor" class="sv-input" :placeholder="t('ui.field.actor')" />
    <input
      v-model="action"
      class="sv-input"
      :placeholder="t('ui.audit.action.placeholder')"
      :aria-label="t('ui.audit.action.placeholder')"
      list="audit-log-known-actions"
    />
    <datalist id="audit-log-known-actions">
      <option v-for="known in knownActions" :key="known" :value="known" />
    </datalist>
    <label class="sv-toolbar-field">
      <span class="sv-label">{{ t("ui.field.from") }}</span>
      <input v-model="from" type="date" class="sv-input" />
    </label>
    <label class="sv-toolbar-field">
      <span class="sv-label">{{ t("ui.field.to") }}</span>
      <input v-model="to" type="date" class="sv-input" />
    </label>
    <button type="button" class="sv-button sv-button--ghost" @click="clearFilters">
      {{ t("ui.action.clear-filters") }}
    </button>
  </div>
  <div v-if="error" class="sv-alert sv-alert--danger" role="alert">{{ error }}</div>
  <div v-if="loading" class="sv-loading"><span class="sv-spinner" /></div>
  <template v-else>
    <div class="sv-table-wrap">
      <table class="sv-table">
        <thead>
          <tr>
            <th scope="col">{{ t("ui.field.created-at") }}</th>
            <th scope="col">{{ t("ui.field.actor") }}</th>
            <th scope="col">{{ t("ui.field.action") }}</th>
            <th scope="col">{{ t("ui.field.target") }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="entry in entries" :key="entry.id">
            <td>
              <time :datetime="entry.createdAt">
                {{ formatDateTime(entry.createdAt, resolvedLanguage) }}
              </time>
            </td>
            <td>{{ entry.actor }}</td>
            <td><span class="sv-badge">{{ entry.action }}</span></td>
            <td class="sv-cell-mono">
              {{ entry.targetType }}/{{ entry.targetId.slice(0, 8) }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <Pagination :page="page" :total-pages="totalPages" @page="page = $event" />
  </template>
</template>
