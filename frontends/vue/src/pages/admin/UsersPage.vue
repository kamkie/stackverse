<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { api, unwrap } from "../../api/client";
import { fieldErrorsOf } from "../../api/problem";
import Dialog from "../../components/Dialog.vue";
import Field from "../../components/Field.vue";
import Pagination from "../../components/Pagination.vue";
import {
  formatDateTime,
  toFieldErrorMap,
  type FieldErrorMap,
} from "../../forms";
import { t, resolvedLanguage } from "../../i18n/i18n";
import type { UserAccount } from "../../types";

const users = ref<UserAccount[]>([]);
const q = ref("");
const page = ref(0);
const totalPages = ref(1);
const loading = ref(false);
const error = ref<string | null>(null);
const blocking = ref<UserAccount | null>(null);
const reason = ref("");
const fieldErrors = ref<FieldErrorMap>({});

let reloadTimer: number | undefined;

function statusText(user: UserAccount): string {
  return t(`ui.user.status.${user.status}`);
}

async function loadUsers(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const result = unwrap(
      await api.GET("/api/v1/admin/users", {
        params: {
          query: {
            page: page.value,
            size: 20,
            ...(q.value ? { q: q.value } : {}),
          },
        },
      }),
    );
    users.value = result.items;
    totalPages.value = result.totalPages;
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "Failed to load users";
  } finally {
    loading.value = false;
  }
}

function scheduleReload(): void {
  if (reloadTimer !== undefined) window.clearTimeout(reloadTimer);
  reloadTimer = window.setTimeout(() => {
    page.value = 0;
    void loadUsers();
  }, 250);
}

function openBlock(user: UserAccount): void {
  blocking.value = user;
  reason.value = "";
  fieldErrors.value = {};
}

async function blockUser(): Promise<void> {
  if (!blocking.value) return;
  fieldErrors.value = {};
  try {
    const updated = unwrap(
      await api.PUT("/api/v1/admin/users/{username}/status", {
        params: { path: { username: blocking.value.username } },
        body: { status: "blocked", reason: reason.value },
      }),
    );
    users.value = users.value.map((user) =>
      user.username === updated.username ? updated : user,
    );
    blocking.value = null;
  } catch (caught) {
    fieldErrors.value = toFieldErrorMap(fieldErrorsOf(caught));
    if (Object.keys(fieldErrors.value).length === 0) {
      error.value = caught instanceof Error ? caught.message : "Unable to block user";
    }
  }
}

async function unblockUser(user: UserAccount): Promise<void> {
  try {
    const updated = unwrap(
      await api.PUT("/api/v1/admin/users/{username}/status", {
        params: { path: { username: user.username } },
        body: { status: "active" },
      }),
    );
    users.value = users.value.map((item) =>
      item.username === updated.username ? updated : item,
    );
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "Unable to unblock user";
  }
}

watch(q, scheduleReload);
watch(page, () => void loadUsers());
onMounted(() => void loadUsers());
</script>

<template>
  <h1 class="sv-page-title">{{ t("ui.admin.users") }}</h1>
  <div class="sv-toolbar">
    <input
      v-model="q"
      class="sv-input"
      type="search"
      :placeholder="t('ui.users.search.placeholder')"
    />
  </div>
  <div v-if="error" class="sv-alert sv-alert--danger" role="alert">{{ error }}</div>
  <div v-if="loading" class="sv-loading"><span class="sv-spinner" /></div>
  <template v-else>
    <div class="sv-table-wrap">
      <table class="sv-table">
        <thead>
          <tr>
            <th scope="col">{{ t("ui.field.username") }}</th>
            <th scope="col">{{ t("ui.field.last-seen") }}</th>
            <th scope="col">{{ t("ui.field.bookmarks") }}</th>
            <th scope="col">{{ t("ui.field.status") }}</th>
            <th scope="col">{{ t("ui.field.actions") }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in users" :key="user.username" :data-ctx="`user:${user.username}`">
            <td>{{ user.username }}</td>
            <td>{{ formatDateTime(user.lastSeen, resolvedLanguage) }}</td>
            <td>{{ user.bookmarkCount }}</td>
            <td>
              <span
                class="sv-badge"
                :class="user.status === 'blocked' ? 'sv-badge--danger' : 'sv-badge--success'"
              >
                {{ statusText(user) }}
              </span>
            </td>
            <td class="sv-cell-actions">
              <button
                v-if="user.status === 'active'"
                type="button"
                class="sv-button sv-button--danger sv-button--sm"
                @click="openBlock(user)"
              >
                {{ t("ui.action.block") }}
              </button>
              <button
                v-else
                type="button"
                class="sv-button sv-button--ghost sv-button--sm"
                @click="unblockUser(user)"
              >
                {{ t("ui.action.unblock") }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <Pagination :page="page" :total-pages="totalPages" @page="page = $event" />
  </template>

  <Dialog
    v-if="blocking"
    :title="t('ui.action.block')"
    :ctx="`user:${blocking.username}`"
    @close="blocking = null"
  >
    <form class="sv-form" @submit.prevent="blockUser">
      <Field
        v-slot="{ inputId, describedBy, invalid }"
        :label="t('ui.field.reason')"
        :error="fieldErrors.reason"
      >
        <textarea
          :id="inputId"
          v-model="reason"
          class="sv-textarea"
          :aria-describedby="describedBy"
          :aria-invalid="invalid || undefined"
        />
      </Field>
      <div class="sv-form-actions">
        <button type="button" class="sv-button sv-button--ghost" @click="blocking = null">
          {{ t("ui.action.cancel") }}
        </button>
        <button type="submit" class="sv-button sv-button--danger">
          {{ t("ui.action.block") }}
        </button>
      </div>
    </form>
  </Dialog>
</template>
