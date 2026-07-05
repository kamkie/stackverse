<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { api, unwrap } from "../api/client";
import { ApiError, fieldErrorsOf } from "../api/problem";
import { session } from "../auth";
import BookmarkCard from "../components/BookmarkCard.vue";
import Dialog from "../components/Dialog.vue";
import Field from "../components/Field.vue";
import { toFieldErrorMap, type FieldErrorMap } from "../forms";
import { t } from "../i18n/i18n";
import { markReported } from "../reportedStore";
import { showToast } from "../toast";
import type { Bookmark, ReportInput } from "../types";

const bookmarks = ref<Bookmark[]>([]);
const q = ref("");
const nextCursor = ref<string | undefined>();
const loading = ref(false);
const error = ref<string | null>(null);
const reporting = ref<Bookmark | null>(null);
const fieldErrors = ref<FieldErrorMap>({});
const reportForm = ref({
  reason: "spam" as ReportInput["reason"],
  comment: "",
});

let reloadTimer: number | undefined;

async function loadFeed(reset = true): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const query = {
      visibility: "public" as const,
      size: 20,
      ...(q.value ? { q: q.value } : {}),
      ...(!reset && nextCursor.value ? { cursor: nextCursor.value } : {}),
    };
    const page = unwrap(await api.GET("/api/v2/bookmarks", { params: { query } }));
    bookmarks.value = reset ? page.items : [...bookmarks.value, ...page.items];
    nextCursor.value = page.nextCursor;
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "Failed to load feed";
  } finally {
    loading.value = false;
  }
}

function scheduleReload(): void {
  if (reloadTimer !== undefined) window.clearTimeout(reloadTimer);
  reloadTimer = window.setTimeout(() => void loadFeed(true), 250);
}

function openReport(bookmark: Bookmark): void {
  reporting.value = bookmark;
  fieldErrors.value = {};
  reportForm.value = { reason: "spam", comment: "" };
}

function reportBody(): ReportInput {
  const body: ReportInput = { reason: reportForm.value.reason };
  if (reportForm.value.comment.trim()) body.comment = reportForm.value.comment;
  return body;
}

async function submitReport(): Promise<void> {
  if (!reporting.value) return;
  try {
    unwrap(
      await api.POST("/api/v1/bookmarks/{id}/reports", {
        params: { path: { id: reporting.value.id } },
        body: reportBody(),
      }),
    );
    markReported(reporting.value.id);
    reporting.value = null;
    showToast(t("ui.toast.report-submitted"));
  } catch (caught) {
    if (caught instanceof ApiError && caught.status === 409 && reporting.value) {
      markReported(reporting.value.id);
      reporting.value = null;
      showToast(t("ui.toast.report-duplicate"));
      return;
    }
    fieldErrors.value = toFieldErrorMap(fieldErrorsOf(caught));
    if (Object.keys(fieldErrors.value).length === 0) {
      error.value = caught instanceof Error ? caught.message : "Unable to submit report";
    }
  }
}

watch(q, scheduleReload);
onMounted(() => void loadFeed(true));
</script>

<template>
  <section class="sv-content">
    <h1 class="sv-page-title">{{ t("ui.nav.public-feed") }}</h1>
    <div class="sv-toolbar">
      <input
        v-model="q"
        class="sv-input"
        type="search"
        :placeholder="t('ui.bookmarks.search.placeholder')"
      />
    </div>
    <div v-if="error" class="sv-alert sv-alert--danger" role="alert">{{ error }}</div>
    <div v-if="loading && bookmarks.length === 0" class="sv-loading">
      <span class="sv-spinner" />
    </div>
    <p v-else-if="bookmarks.length === 0" class="sv-empty">
      {{ t("ui.bookmarks.no-matches") }}
    </p>
    <div v-else class="sv-card-list">
      <BookmarkCard
        v-for="bookmark in bookmarks"
        :key="bookmark.id"
        :bookmark="bookmark"
        mode="feed"
        @report="openReport"
      />
    </div>
    <div v-if="nextCursor" class="sv-load-more">
      <button type="button" class="sv-button" :disabled="loading" @click="loadFeed(false)">
        {{ t("ui.action.load-more") }}
      </button>
    </div>
  </section>

  <Dialog
    v-if="reporting && session?.authenticated"
    :title="t('ui.action.report')"
    :ctx="`bookmark:${reporting.id}`"
    @close="reporting = null"
  >
    <form class="sv-form" @submit.prevent="submitReport">
      <Field
        v-slot="{ inputId, describedBy, invalid }"
        :label="t('ui.field.reason')"
        :error="fieldErrors.reason"
      >
        <select
          :id="inputId"
          v-model="reportForm.reason"
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
          v-model="reportForm.comment"
          class="sv-textarea"
          :aria-describedby="describedBy"
          :aria-invalid="invalid || undefined"
        />
      </Field>
      <div class="sv-form-actions">
        <button type="button" class="sv-button sv-button--ghost" @click="reporting = null">
          {{ t("ui.action.cancel") }}
        </button>
        <button type="submit" class="sv-button sv-button--primary">
          {{ t("ui.action.report") }}
        </button>
      </div>
    </form>
  </Dialog>
</template>
