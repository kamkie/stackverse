<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { api, unwrap } from "../api/client";
import { fieldErrorsOf } from "../api/problem";
import Dialog from "../components/Dialog.vue";
import Field from "../components/Field.vue";
import Pagination from "../components/Pagination.vue";
import { formatDateTime, toFieldErrorMap, type FieldErrorMap } from "../forms";
import { t, resolvedLanguage } from "../i18n/i18n";
import { unmarkReported } from "../reportedStore";
import { showToast } from "../toast";
import type { Report, ReportInput, ReportStatus } from "../types";

const reports = ref<Report[]>([]);
const bookmarkTitles = ref<Record<string, string>>({});
const status = ref<"" | ReportStatus>("");
const page = ref(0);
const totalPages = ref(1);
const loading = ref(false);
const error = ref<string | null>(null);

const editing = ref<Report | null>(null);
const withdrawing = ref<Report | null>(null);
const fieldErrors = ref<FieldErrorMap>({});
const form = ref({
  reason: "spam" as ReportInput["reason"],
  comment: "",
});

function statusLabel(value: ReportStatus): string {
  return t(`ui.report.status.${value}`);
}

function reasonLabel(value: ReportInput["reason"]): string {
  return t(`ui.report.reason.${value}`);
}

function openEdit(report: Report): void {
  editing.value = report;
  fieldErrors.value = {};
  form.value = {
    reason: report.reason,
    comment: report.comment ?? "",
  };
}

function reportBody(): ReportInput {
  const body: ReportInput = { reason: form.value.reason };
  if (form.value.comment.trim()) body.comment = form.value.comment;
  return body;
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
      await api.GET("/api/v1/reports", {
        params: {
          query: {
            page: page.value,
            size: 20,
            ...(status.value ? { status: status.value } : {}),
          },
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

async function saveReport(): Promise<void> {
  if (!editing.value) return;
  try {
    const updated = unwrap(
      await api.PUT("/api/v1/reports/{id}", {
        params: { path: { id: editing.value.id } },
        body: reportBody(),
      }),
    );
    reports.value = reports.value.map((item) => (item.id === updated.id ? updated : item));
    editing.value = null;
    showToast(t("ui.toast.report-updated"));
  } catch (caught) {
    fieldErrors.value = toFieldErrorMap(fieldErrorsOf(caught));
    if (Object.keys(fieldErrors.value).length === 0) {
      error.value = caught instanceof Error ? caught.message : "Unable to update report";
    }
  }
}

async function withdrawReport(): Promise<void> {
  if (!withdrawing.value) return;
  const target = withdrawing.value;
  try {
    unwrap(
      await api.DELETE("/api/v1/reports/{id}", { params: { path: { id: target.id } } }),
    );
    reports.value = reports.value.filter((item) => item.id !== target.id);
    unmarkReported(target.bookmarkId);
    withdrawing.value = null;
    showToast(t("ui.toast.report-withdrawn"));
  } catch (caught) {
    const message = caught instanceof Error ? caught.message : "Unable to withdraw report";
    error.value = message;
    withdrawing.value = null;
    showToast(message, "danger");
  }
}

watch(status, () => {
  if (page.value === 0) {
    void loadReports();
  } else {
    page.value = 0;
  }
});
watch(page, () => void loadReports());
onMounted(() => void loadReports());
</script>

<template>
  <section class="sv-content">
    <h1 class="sv-page-title">{{ t("ui.nav.my-reports") }}</h1>
    <div class="sv-toolbar">
      <select v-model="status" class="sv-select">
        <option value="">{{ t("ui.my-reports.filter.all-statuses") }}</option>
        <option value="open">{{ t("ui.report.status.open") }}</option>
        <option value="dismissed">{{ t("ui.report.status.dismissed") }}</option>
        <option value="actioned">{{ t("ui.report.status.actioned") }}</option>
      </select>
    </div>
    <div v-if="error" class="sv-alert sv-alert--danger" role="alert">{{ error }}</div>
    <div v-if="loading" class="sv-loading"><span class="sv-spinner" /></div>
    <p v-else-if="reports.length === 0" class="sv-empty">{{ t("ui.my-reports.empty") }}</p>
    <template v-else>
      <div class="sv-table-wrap">
        <table class="sv-table">
          <thead>
            <tr>
              <th scope="col">{{ t("ui.field.created-at") }}</th>
              <th scope="col">{{ t("ui.field.bookmark") }}</th>
              <th scope="col">{{ t("ui.field.reason") }}</th>
              <th scope="col">{{ t("ui.field.comment") }}</th>
              <th scope="col">{{ t("ui.field.status") }}</th>
              <th scope="col">{{ t("ui.field.actions") }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="report in reports" :key="report.id" :data-ctx="`report:${report.id}`">
              <td>
                <time :datetime="report.createdAt">
                  {{ formatDateTime(report.createdAt, resolvedLanguage) }}
                </time>
              </td>
              <td>{{ bookmarkTitles[report.bookmarkId] ?? report.bookmarkId }}</td>
              <td>{{ reasonLabel(report.reason) }}</td>
              <td>
                <div>{{ report.comment }}</div>
                <div v-if="report.resolutionNote" class="sv-field-hint">
                  {{ report.resolutionNote }}
                </div>
              </td>
              <td>
                <span class="sv-badge">{{ statusLabel(report.status) }}</span>
              </td>
              <td class="sv-cell-actions">
                <template v-if="report.status === 'open'">
                  <button
                    type="button"
                    class="sv-button sv-button--ghost sv-button--sm"
                    @click="openEdit(report)"
                  >
                    {{ t("ui.action.edit") }}
                  </button>
                  <button
                    type="button"
                    class="sv-button sv-button--danger sv-button--sm"
                    @click="withdrawing = report"
                  >
                    {{ t("ui.action.withdraw") }}
                  </button>
                </template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Pagination :page="page" :total-pages="totalPages" @page="page = $event" />
    </template>
  </section>

  <Dialog
    v-if="editing"
    :title="t('ui.my-reports.dialog.edit')"
    :ctx="`report:${editing.id}`"
    @close="editing = null"
  >
    <form class="sv-form" @submit.prevent="saveReport">
      <Field
        v-slot="{ inputId, describedBy, invalid }"
        :label="t('ui.field.reason')"
        :error="fieldErrors.reason"
      >
        <select
          :id="inputId"
          v-model="form.reason"
          class="sv-select"
          :aria-describedby="describedBy"
          :aria-invalid="invalid || undefined"
        >
          <option value="spam">{{ t("ui.report.reason.spam") }}</option>
          <option value="offensive">{{ t("ui.report.reason.offensive") }}</option>
          <option value="broken-link">{{ t("ui.report.reason.broken-link") }}</option>
          <option value="other">{{ t("ui.report.reason.other") }}</option>
        </select>
      </Field>
      <Field
        v-slot="{ inputId, describedBy, invalid }"
        :label="t('ui.field.comment')"
        :error="fieldErrors.comment"
      >
        <textarea
          :id="inputId"
          v-model="form.comment"
          class="sv-textarea"
          :aria-describedby="describedBy"
          :aria-invalid="invalid || undefined"
        />
      </Field>
      <div class="sv-form-actions">
        <button type="button" class="sv-button sv-button--ghost" @click="editing = null">
          {{ t("ui.action.cancel") }}
        </button>
        <button type="submit" class="sv-button sv-button--primary">
          {{ t("ui.action.save") }}
        </button>
      </div>
    </form>
  </Dialog>

  <Dialog
    v-if="withdrawing"
    :title="t('ui.action.withdraw')"
    :ctx="`report:${withdrawing.id}`"
    @close="withdrawing = null"
  >
    <form class="sv-form" @submit.prevent="withdrawReport">
      <p>{{ t("ui.confirm.withdraw-report") }}</p>
      <div class="sv-form-actions">
        <button type="button" class="sv-button sv-button--ghost" @click="withdrawing = null">
          {{ t("ui.action.cancel") }}
        </button>
        <button type="submit" class="sv-button sv-button--danger">
          {{ t("ui.action.withdraw") }}
        </button>
      </div>
    </form>
  </Dialog>
</template>
