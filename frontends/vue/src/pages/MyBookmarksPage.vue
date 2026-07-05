<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { api, unwrap } from "../api/client";
import { fieldErrorsOf } from "../api/problem";
import BookmarkCard from "../components/BookmarkCard.vue";
import Dialog from "../components/Dialog.vue";
import Field from "../components/Field.vue";
import { toFieldErrorMap, type FieldErrorMap, tagsFromInput, tagsToInput } from "../forms";
import { t } from "../i18n/i18n";
import { showToast } from "../toast";
import type { Bookmark, BookmarkInput, TagCount } from "../types";

const bookmarks = ref<Bookmark[]>([]);
const tags = ref<TagCount[]>([]);
const q = ref("");
const selectedTag = ref("");
const nextCursor = ref<string | undefined>();
const loading = ref(false);
const error = ref<string | null>(null);

const editing = ref<Bookmark | null>(null);
const deleting = ref<Bookmark | null>(null);
const formOpen = ref(false);
const fieldErrors = ref<FieldErrorMap>({});
const saving = ref(false);
const form = ref({
  url: "",
  title: "",
  notes: "",
  tags: "",
  visibility: "private" as "private" | "public",
});

let reloadTimer: number | undefined;

function resetForm(bookmark?: Bookmark): void {
  editing.value = bookmark ?? null;
  fieldErrors.value = {};
  form.value = {
    url: bookmark?.url ?? "",
    title: bookmark?.title ?? "",
    notes: bookmark?.notes ?? "",
    tags: tagsToInput(bookmark?.tags),
    visibility: bookmark?.visibility ?? "private",
  };
  formOpen.value = true;
}

function bodyFromForm(): BookmarkInput {
  const body: BookmarkInput = {
    url: form.value.url,
    title: form.value.title,
    tags: tagsFromInput(form.value.tags),
    visibility: form.value.visibility,
  };
  if (form.value.notes.trim()) body.notes = form.value.notes;
  return body;
}

async function loadTags(): Promise<void> {
  tags.value = unwrap(await api.GET("/api/v1/tags")).tags;
}

async function loadBookmarks(reset = true): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const query = {
      size: 20,
      ...(q.value ? { q: q.value } : {}),
      ...(selectedTag.value ? { tag: [selectedTag.value] } : {}),
      ...(!reset && nextCursor.value ? { cursor: nextCursor.value } : {}),
    };
    const page = unwrap(await api.GET("/api/v2/bookmarks", { params: { query } }));
    bookmarks.value = reset ? page.items : [...bookmarks.value, ...page.items];
    nextCursor.value = page.nextCursor;
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "Failed to load bookmarks";
  } finally {
    loading.value = false;
  }
}

function scheduleReload(): void {
  if (reloadTimer !== undefined) window.clearTimeout(reloadTimer);
  reloadTimer = window.setTimeout(() => void loadBookmarks(true), 250);
}

async function saveBookmark(): Promise<void> {
  saving.value = true;
  fieldErrors.value = {};
  try {
    if (editing.value) {
      const updated = unwrap(
        await api.PUT("/api/v1/bookmarks/{id}", {
          params: { path: { id: editing.value.id } },
          body: bodyFromForm(),
        }),
      );
      bookmarks.value = bookmarks.value.map((item) =>
        item.id === updated.id ? updated : item,
      );
    } else {
      const created = unwrap(await api.POST("/api/v1/bookmarks", { body: bodyFromForm() }));
      bookmarks.value = [created, ...bookmarks.value];
    }
    formOpen.value = false;
    await loadTags();
  } catch (caught) {
    fieldErrors.value = toFieldErrorMap(fieldErrorsOf(caught));
    if (Object.keys(fieldErrors.value).length === 0) {
      error.value = caught instanceof Error ? caught.message : "Unable to save bookmark";
    }
  } finally {
    saving.value = false;
  }
}

async function deleteBookmark(): Promise<void> {
  if (!deleting.value) return;
  const target = deleting.value;
  unwrap(await api.DELETE("/api/v1/bookmarks/{id}", { params: { path: { id: target.id } } }));
  bookmarks.value = bookmarks.value.filter((item) => item.id !== target.id);
  deleting.value = null;
  await loadTags();
  showToast(t("ui.toast.bookmark-deleted"));
}

watch([q, selectedTag], scheduleReload);

onMounted(async () => {
  await Promise.all([loadBookmarks(true), loadTags()]);
});
</script>

<template>
  <div class="sv-layout">
    <aside class="sv-sidebar">
      <h2 class="sv-sidebar-title">{{ t("ui.nav.tags") }}</h2>
      <ul class="sv-tag-list">
        <li>
          <button
            type="button"
            class="sv-tag"
            :class="{ 'is-active': selectedTag === '' }"
            @click="selectedTag = ''"
          >
            {{ t("ui.action.clear-filters") }}
          </button>
        </li>
        <li v-for="tag in tags" :key="tag.tag">
          <button
            type="button"
            class="sv-tag"
            :class="{ 'is-active': selectedTag === tag.tag }"
            @click="selectedTag = tag.tag"
          >
            {{ tag.tag }}
            <span class="sv-tag-count">{{ tag.count }}</span>
          </button>
        </li>
      </ul>
    </aside>
    <section class="sv-content">
      <h1 class="sv-page-title">{{ t("ui.nav.my-bookmarks") }}</h1>
      <div class="sv-toolbar">
        <input
          v-model="q"
          class="sv-input"
          type="search"
          :placeholder="t('ui.bookmarks.search.placeholder')"
        />
        <span class="sv-spacer" />
        <button type="button" class="sv-button sv-button--primary" @click="resetForm()">
          {{ t("ui.action.add") }}
        </button>
      </div>
      <div v-if="error" class="sv-alert sv-alert--danger" role="alert">{{ error }}</div>
      <div v-if="loading && bookmarks.length === 0" class="sv-loading">
        <span class="sv-spinner" />
      </div>
      <p v-else-if="bookmarks.length === 0" class="sv-empty">
        {{ q || selectedTag ? t("ui.bookmarks.no-matches") : t("ui.bookmarks.empty") }}
      </p>
      <div v-else class="sv-card-list">
        <BookmarkCard
          v-for="bookmark in bookmarks"
          :key="bookmark.id"
          :bookmark="bookmark"
          mode="mine"
          @edit="resetForm"
          @delete="deleting = $event"
        />
      </div>
      <div v-if="nextCursor" class="sv-load-more">
        <button
          type="button"
          class="sv-button"
          :disabled="loading"
          @click="loadBookmarks(false)"
        >
          {{ t("ui.action.load-more") }}
        </button>
      </div>
    </section>
  </div>

  <Dialog
    v-if="formOpen"
    :title="editing ? t('ui.bookmarks.dialog.edit') : t('ui.bookmarks.dialog.add')"
    :ctx="editing ? `bookmark:${editing.id}` : undefined"
    @close="formOpen = false"
  >
    <form class="sv-form" @submit.prevent="saveBookmark">
      <Field :label="t('ui.field.url')" :error="fieldErrors.url">
        <input v-model="form.url" class="sv-input" />
      </Field>
      <Field :label="t('ui.field.title')" :error="fieldErrors.title">
        <input v-model="form.title" class="sv-input" />
      </Field>
      <Field :label="t('ui.field.notes')" :error="fieldErrors.notes">
        <textarea v-model="form.notes" class="sv-textarea" />
      </Field>
      <Field
        :label="t('ui.field.tags')"
        :error="fieldErrors.tags"
        :hint="t('ui.field.tags.hint')"
      >
        <input v-model="form.tags" class="sv-input" />
      </Field>
      <Field :label="t('ui.field.visibility')" :error="fieldErrors.visibility">
        <select v-model="form.visibility" class="sv-select">
          <option value="private">{{ t("ui.visibility.private") }}</option>
          <option value="public">{{ t("ui.visibility.public") }}</option>
        </select>
      </Field>
      <div class="sv-form-actions">
        <button type="button" class="sv-button sv-button--ghost" @click="formOpen = false">
          {{ t("ui.action.cancel") }}
        </button>
        <button type="submit" class="sv-button sv-button--primary" :disabled="saving">
          {{ t("ui.action.save") }}
        </button>
      </div>
    </form>
  </Dialog>

  <Dialog
    v-if="deleting"
    :title="t('ui.action.delete')"
    :ctx="`bookmark:${deleting.id}`"
    @close="deleting = null"
  >
    <form class="sv-form" @submit.prevent="deleteBookmark">
      <p>{{ t("ui.confirm.delete-bookmark") }}</p>
      <div class="sv-form-actions">
        <button type="button" class="sv-button sv-button--ghost" @click="deleting = null">
          {{ t("ui.action.cancel") }}
        </button>
        <button type="submit" class="sv-button sv-button--danger">
          {{ t("ui.action.delete") }}
        </button>
      </div>
    </form>
  </Dialog>
</template>
