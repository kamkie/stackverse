<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { api, unwrap } from "../../api/client";
import { fieldErrorsOf } from "../../api/problem";
import Dialog from "../../components/Dialog.vue";
import Field from "../../components/Field.vue";
import Pagination from "../../components/Pagination.vue";
import { formatDateTime, toFieldErrorMap, type FieldErrorMap } from "../../forms";
import { loadBundle, resolvedLanguage, t } from "../../i18n/i18n";
import { SUPPORTED_LANGUAGES } from "../../i18n/languages";
import { showToast } from "../../toast";
import type { Message, MessageInput } from "../../types";

const messages = ref<Message[]>([]);
const q = ref("");
const language = ref("");
const page = ref(0);
const totalPages = ref(1);
const loading = ref(false);
const error = ref<string | null>(null);

const editing = ref<Message | null>(null);
const deleting = ref<Message | null>(null);
const formOpen = ref(false);
const fieldErrors = ref<FieldErrorMap>({});
const form = ref({
  key: "",
  language: "en",
  text: "",
  description: "",
});

let reloadTimer: number | undefined;

function openForm(message?: Message): void {
  editing.value = message ?? null;
  fieldErrors.value = {};
  form.value = {
    key: message?.key ?? "",
    language: message?.language ?? "en",
    text: message?.text ?? "",
    description: message?.description ?? "",
  };
  formOpen.value = true;
}

function bodyFromForm(): MessageInput {
  const body: MessageInput = {
    key: form.value.key,
    language: form.value.language,
    text: form.value.text,
  };
  if (form.value.description.trim()) body.description = form.value.description;
  return body;
}

async function loadMessages(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const result = unwrap(
      await api.GET("/api/v1/messages", {
        params: {
          query: {
            page: page.value,
            size: 20,
            ...(q.value ? { q: q.value } : {}),
            ...(language.value ? { language: language.value } : {}),
          },
        },
      }),
    );
    messages.value = result.items;
    totalPages.value = result.totalPages;
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "Failed to load messages";
  } finally {
    loading.value = false;
  }
}

function scheduleReload(): void {
  if (reloadTimer !== undefined) window.clearTimeout(reloadTimer);
  reloadTimer = window.setTimeout(() => {
    page.value = 0;
    void loadMessages();
  }, 250);
}

async function saveMessage(): Promise<void> {
  fieldErrors.value = {};
  try {
    if (editing.value) {
      const updated = unwrap(
        await api.PUT("/api/v1/messages/{id}", {
          params: { path: { id: editing.value.id } },
          body: bodyFromForm(),
        }),
      );
      messages.value = messages.value.map((message) =>
        message.id === updated.id ? updated : message,
      );
      showToast(t("ui.toast.message-updated"));
    } else {
      const created = unwrap(await api.POST("/api/v1/messages", { body: bodyFromForm() }));
      messages.value = [created, ...messages.value];
      showToast(t("ui.toast.message-created"));
    }
    formOpen.value = false;
    await loadBundle();
  } catch (caught) {
    fieldErrors.value = toFieldErrorMap(fieldErrorsOf(caught));
    if (Object.keys(fieldErrors.value).length === 0) {
      error.value = caught instanceof Error ? caught.message : "Unable to save message";
    }
  }
}

async function deleteMessage(): Promise<void> {
  if (!deleting.value) return;
  const target = deleting.value;
  try {
    unwrap(
      await api.DELETE("/api/v1/messages/{id}", { params: { path: { id: target.id } } }),
    );
    messages.value = messages.value.filter((message) => message.id !== target.id);
    deleting.value = null;
    showToast(t("ui.toast.message-deleted"));
    await loadBundle();
  } catch (caught) {
    const message = caught instanceof Error ? caught.message : "Unable to delete message";
    error.value = message;
    deleting.value = null;
    showToast(message, "danger");
  }
}

watch([q, language], scheduleReload);
watch(page, () => void loadMessages());
onMounted(() => void loadMessages());
</script>

<template>
  <h1 class="sv-page-title">{{ t("ui.admin.messages") }}</h1>
  <div class="sv-toolbar">
    <input
      v-model="q"
      class="sv-input"
      :placeholder="t('ui.messages.search.placeholder')"
    />
    <select v-model="language" class="sv-select">
      <option value="">{{ t("ui.messages.filter.all-languages") }}</option>
      <option v-for="code in SUPPORTED_LANGUAGES" :key="code" :value="code">
        {{ code }}
      </option>
    </select>
    <span class="sv-spacer" />
    <button type="button" class="sv-button sv-button--primary" @click="openForm()">
      {{ t("ui.action.add") }}
    </button>
  </div>
  <div v-if="error" class="sv-alert sv-alert--danger" role="alert">{{ error }}</div>
  <div v-if="loading" class="sv-loading"><span class="sv-spinner" /></div>
  <p v-else-if="messages.length === 0" class="sv-empty">{{ t("ui.messages.empty") }}</p>
  <template v-else>
    <div class="sv-table-wrap">
      <table class="sv-table">
        <thead>
          <tr>
            <th scope="col">{{ t("ui.field.key") }}</th>
            <th scope="col">{{ t("ui.field.language") }}</th>
            <th scope="col">{{ t("ui.field.text") }}</th>
            <th scope="col">{{ t("ui.field.created-at") }}</th>
            <th scope="col">{{ t("ui.field.actions") }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="message in messages" :key="message.id" :data-ctx="`message:${message.id}`">
            <td class="sv-cell-mono">{{ message.key }}</td>
            <td>{{ message.language }}</td>
            <td>{{ message.text }}</td>
            <td>{{ formatDateTime(message.createdAt, resolvedLanguage) }}</td>
            <td class="sv-cell-actions">
              <button
                type="button"
                class="sv-button sv-button--ghost sv-button--sm"
                @click="openForm(message)"
              >
                {{ t("ui.action.edit") }}
              </button>
              <button
                type="button"
                class="sv-button sv-button--danger sv-button--sm"
                @click="deleting = message"
              >
                {{ t("ui.action.delete") }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <Pagination :page="page" :total-pages="totalPages" @page="page = $event" />
  </template>

  <Dialog
    v-if="formOpen"
    :title="editing ? t('ui.messages.dialog.edit') : t('ui.messages.dialog.add')"
    :ctx="editing ? `message:${editing.id}` : undefined"
    @close="formOpen = false"
  >
    <form class="sv-form" @submit.prevent="saveMessage">
      <Field
        v-slot="{ inputId, describedBy, invalid }"
        :label="t('ui.field.key')"
        :error="fieldErrors.key"
      >
        <input
          :id="inputId"
          v-model="form.key"
          class="sv-input"
          :aria-describedby="describedBy"
          :aria-invalid="invalid || undefined"
        />
      </Field>
      <Field
        v-slot="{ inputId, describedBy, invalid }"
        :label="t('ui.field.language')"
        :error="fieldErrors.language"
      >
        <select
          :id="inputId"
          v-model="form.language"
          class="sv-select"
          :aria-describedby="describedBy"
          :aria-invalid="invalid || undefined"
        >
          <option v-for="code in SUPPORTED_LANGUAGES" :key="code" :value="code">
            {{ code }}
          </option>
        </select>
      </Field>
      <Field
        v-slot="{ inputId, describedBy, invalid }"
        :label="t('ui.field.text')"
        :error="fieldErrors.text"
      >
        <textarea
          :id="inputId"
          v-model="form.text"
          class="sv-textarea"
          :aria-describedby="describedBy"
          :aria-invalid="invalid || undefined"
        />
      </Field>
      <Field
        v-slot="{ inputId, describedBy, invalid }"
        :label="t('ui.field.description')"
        :error="fieldErrors.description"
      >
        <textarea
          :id="inputId"
          v-model="form.description"
          class="sv-textarea"
          :aria-describedby="describedBy"
          :aria-invalid="invalid || undefined"
        />
      </Field>
      <div class="sv-form-actions">
        <button type="button" class="sv-button sv-button--ghost" @click="formOpen = false">
          {{ t("ui.action.cancel") }}
        </button>
        <button type="submit" class="sv-button sv-button--primary">
          {{ t("ui.action.save") }}
        </button>
      </div>
    </form>
  </Dialog>

  <Dialog
    v-if="deleting"
    :title="t('ui.action.delete')"
    :ctx="`message:${deleting.id}`"
    @close="deleting = null"
  >
    <form class="sv-form" @submit.prevent="deleteMessage">
      <p>{{ t("ui.confirm.delete-message") }}</p>
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
