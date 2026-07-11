<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { api, unwrap } from "../../api/client";
import Pagination from "../../components/Pagination.vue";
import { formatDateTime } from "../../forms";
import { t, resolvedLanguage } from "../../i18n/i18n";
import type { Report, ReportInput, ReportStatus } from "../../types";

const reports = ref<Report[]>([]);
const bookmarkTitles = ref<Record<string, string>>({});
const status = ref<ReportStatus>("open");
const page = ref(0);
const totalPages = ref(1);
const loading = ref(false);
const error = ref<string | null>(null);

function reasonLabel(value: ReportInput["reason"]): string {
    return t(`ui.report.reason.${value}`);
}

function statusLabel(value: ReportStatus): string {
    return t(`ui.report.status.${value}`);
}

async function loadBookmarkTitle(report: Report): Promise<void> {
    if (bookmarkTitles.value[report.bookmarkId]) return;
    try {
        const bookmark = unwrap(
            await api.GET("/api/v1/bookmarks/{id}", {
                params: { path: { id: report.bookmarkId } },
            }),
        );
        bookmarkTitles.value = {
            ...bookmarkTitles.value,
            [report.bookmarkId]: bookmark.title,
        };
    } catch {
        bookmarkTitles.value = {
            ...bookmarkTitles.value,
            [report.bookmarkId]: t("ui.reports.bookmark-unavailable"),
        };
    }
}

async function loadReports(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
        const result = unwrap(
            await api.GET("/api/v1/admin/reports", {
                params: {
                    query: { status: status.value, page: page.value, size: 20 },
                },
            }),
        );
        reports.value = result.items;
        totalPages.value = result.totalPages;
        await Promise.all(result.items.map(loadBookmarkTitle));
    } catch (caught) {
        error.value = caught instanceof Error ? caught.message : "Failed to load reports";
    } finally {
        loading.value = false;
    }
}

async function resolveReport(report: Report, resolution: ReportStatus): Promise<void> {
    try {
        unwrap(
            await api.PUT("/api/v1/admin/reports/{id}", {
                params: { path: { id: report.id } },
                body: { resolution },
            }),
        );
        await loadReports();
    } catch (caught) {
        error.value = caught instanceof Error ? caught.message : "Unable to update report";
    }
}

watch(status, () => {
    page.value = 0;
    void loadReports();
});
watch(page, () => void loadReports());
onMounted(() => void loadReports());
</script>

<template>
    <h1 class="sv-page-title">{{ t("ui.admin.reports") }}</h1>
    <div class="sv-toolbar">
        <select v-model="status" class="sv-select">
            <option value="open">{{ t("ui.report.status.open") }}</option>
            <option value="dismissed">
                {{ t("ui.report.status.dismissed") }}
            </option>
            <option value="actioned">
                {{ t("ui.report.status.actioned") }}
            </option>
        </select>
    </div>
    <div v-if="error" class="sv-alert sv-alert--danger" role="alert">
        {{ error }}
    </div>
    <div v-if="loading" class="sv-loading"><span class="sv-spinner" /></div>
    <p v-else-if="reports.length === 0" class="sv-empty">
        {{ t("ui.reports.empty") }}
    </p>
    <template v-else>
        <div class="sv-table-wrap">
            <table class="sv-table">
                <thead>
                    <tr>
                        <th scope="col">{{ t("ui.field.created-at") }}</th>
                        <th scope="col">{{ t("ui.field.bookmark") }}</th>
                        <th scope="col">{{ t("ui.field.reporter") }}</th>
                        <th scope="col">{{ t("ui.field.reason") }}</th>
                        <th scope="col">{{ t("ui.field.comment") }}</th>
                        <th scope="col">{{ t("ui.field.status") }}</th>
                        <th scope="col">{{ t("ui.field.actions") }}</th>
                    </tr>
                </thead>
                <tbody>
                    <tr
                        v-for="report in reports"
                        :key="report.id"
                        :data-ctx="`report:${report.id}`"
                    >
                        <td>
                            <time :datetime="report.createdAt">
                                {{ formatDateTime(report.createdAt, resolvedLanguage) }}
                            </time>
                        </td>
                        <td>
                            {{ bookmarkTitles[report.bookmarkId] ?? report.bookmarkId }}
                        </td>
                        <td>{{ report.reporter }}</td>
                        <td>{{ reasonLabel(report.reason) }}</td>
                        <td>{{ report.comment }}</td>
                        <td>
                            <span class="sv-badge">{{ statusLabel(report.status) }}</span>
                        </td>
                        <td class="sv-cell-actions">
                            <template v-if="report.status === 'open'">
                                <button
                                    type="button"
                                    class="sv-button sv-button--ghost sv-button--sm"
                                    @click="resolveReport(report, 'dismissed')"
                                >
                                    {{ t("ui.action.dismiss") }}
                                </button>
                                <button
                                    type="button"
                                    class="sv-button sv-button--danger sv-button--sm"
                                    @click="resolveReport(report, 'actioned')"
                                >
                                    {{ t("ui.action.action") }}
                                </button>
                            </template>
                            <template v-else>
                                <button
                                    v-if="report.status === 'dismissed'"
                                    type="button"
                                    class="sv-button sv-button--danger sv-button--sm"
                                    @click="resolveReport(report, 'actioned')"
                                >
                                    {{ t("ui.action.action") }}
                                </button>
                                <button
                                    v-if="report.status === 'actioned'"
                                    type="button"
                                    class="sv-button sv-button--ghost sv-button--sm"
                                    @click="resolveReport(report, 'dismissed')"
                                >
                                    {{ t("ui.action.dismiss") }}
                                </button>
                                <button
                                    type="button"
                                    class="sv-button sv-button--ghost sv-button--sm"
                                    @click="resolveReport(report, 'open')"
                                >
                                    {{ t("ui.action.reopen") }}
                                </button>
                            </template>
                        </td>
                    </tr>
                </tbody>
            </table>
        </div>
        <Pagination :page="page" :total-pages="totalPages" @page="page = $event" />
    </template>
</template>
