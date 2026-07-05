<script setup lang="ts">
import { RouterLink, RouterView } from "vue-router";
import { isAdmin, isModerator, me } from "../../auth";
import { t } from "../../i18n/i18n";
</script>

<template>
  <div v-if="!isModerator(me)" class="sv-alert sv-alert--danger" role="alert">403</div>
  <div v-else class="sv-layout">
    <aside class="sv-sidebar">
      <h2 class="sv-sidebar-title">{{ t("ui.nav.admin") }}</h2>
      <nav class="sv-nav sv-nav--vertical" :aria-label="t('ui.nav.admin')">
        <RouterLink to="/admin" class="sv-nav-link" active-class="is-active">
          {{ t("ui.admin.dashboard") }}
        </RouterLink>
        <RouterLink to="/admin/reports" class="sv-nav-link" active-class="is-active">
          {{ t("ui.admin.reports") }}
        </RouterLink>
        <RouterLink
          v-if="isAdmin(me)"
          to="/admin/users"
          class="sv-nav-link"
          active-class="is-active"
        >
          {{ t("ui.admin.users") }}
        </RouterLink>
        <RouterLink
          v-if="isAdmin(me)"
          to="/admin/audit"
          class="sv-nav-link"
          active-class="is-active"
        >
          {{ t("ui.admin.audit") }}
        </RouterLink>
        <RouterLink
          v-if="isAdmin(me)"
          to="/admin/messages"
          class="sv-nav-link"
          active-class="is-active"
        >
          {{ t("ui.admin.messages") }}
        </RouterLink>
      </nav>
    </aside>
    <section class="sv-content">
      <RouterView />
    </section>
  </div>
</template>
