<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { RouterLink, RouterView, useRouter } from "vue-router";
import { isModerator, loadSession, LOGIN_URL, logout, me, session } from "./auth";
import ToastRegion from "./components/ToastRegion.vue";
import { SUPPORTED_LANGUAGES } from "./i18n/languages";
import {
    bundle,
    loadBundle,
    resolvedLanguage,
    selectedLanguage,
    setLanguage,
    t,
} from "./i18n/i18n";

const router = useRouter();
const booted = ref(false);
const theme = ref(readStoredTheme());
const currentLanguage = computed(() => selectedLanguage.value ?? resolvedLanguage.value);
const themeOptions = ["auto", "light", "dark"] as const;

type ThemeOption = (typeof themeOptions)[number];

function readStoredTheme(): ThemeOption {
    try {
        const stored = localStorage.getItem("stackverse.theme");
        return stored === "light" || stored === "dark" ? stored : "auto";
    } catch {
        return "auto";
    }
}

function applyTheme(next: ThemeOption): void {
    theme.value = next;
    if (next === "auto") document.documentElement.removeAttribute("data-theme");
    else document.documentElement.setAttribute("data-theme", next);
    try {
        if (next === "auto") localStorage.removeItem("stackverse.theme");
        else localStorage.setItem("stackverse.theme", next);
    } catch {
        // Storage unavailable - the choice just won't survive a reload.
    }
}

async function doLogout(): Promise<void> {
    await logout();
    await router.push("/feed");
}

onMounted(async () => {
    await Promise.all([loadBundle(), loadSession()]);
    booted.value = true;
});
</script>

<template>
    <div v-if="!booted || bundle === null" class="sv-loading">
        <span class="sv-spinner" />
    </div>
    <div v-else class="sv-app">
        <header class="sv-header">
            <RouterLink to="/" class="sv-brand">{{ t("ui.app.title") }}</RouterLink>
            <nav class="sv-nav">
                <template v-if="session?.authenticated">
                    <RouterLink to="/bookmarks" class="sv-nav-link" active-class="is-active">
                        {{ t("ui.nav.my-bookmarks") }}
                    </RouterLink>
                    <RouterLink to="/reports" class="sv-nav-link" active-class="is-active">
                        {{ t("ui.nav.my-reports") }}
                    </RouterLink>
                </template>
                <RouterLink to="/feed" class="sv-nav-link" active-class="is-active">
                    {{ t("ui.nav.public-feed") }}
                </RouterLink>
                <RouterLink
                    v-if="isModerator(me)"
                    to="/admin"
                    class="sv-nav-link"
                    active-class="is-active"
                >
                    {{ t("ui.nav.admin") }}
                </RouterLink>
            </nav>
            <div class="sv-header-actions">
                <div class="sv-theme-switch" role="group" :aria-label="t('ui.theme.label')">
                    <button
                        v-for="option in themeOptions"
                        :key="option"
                        type="button"
                        class="sv-theme-option"
                        :class="{ 'is-active': theme === option }"
                        @click="applyTheme(option)"
                    >
                        {{ t(`ui.theme.${option}`) }}
                    </button>
                </div>
                <div class="sv-lang-switch" role="group" aria-label="language">
                    <button
                        v-for="code in SUPPORTED_LANGUAGES"
                        :key="code"
                        type="button"
                        :lang="code"
                        class="sv-lang-option"
                        :class="{ 'is-active': currentLanguage === code }"
                        @click="setLanguage(code)"
                    >
                        {{ code.toUpperCase() }}
                    </button>
                </div>
                <template v-if="session?.authenticated">
                    <span class="sv-username">{{ session.username }}</span>
                    <button
                        type="button"
                        class="sv-button sv-button--ghost sv-button--sm"
                        @click="doLogout"
                    >
                        {{ t("ui.action.logout") }}
                    </button>
                </template>
                <a v-else class="sv-button sv-button--primary sv-button--sm" :href="LOGIN_URL">
                    {{ t("ui.action.login") }}
                </a>
            </div>
        </header>
        <main class="sv-main">
            <RouterView />
        </main>
        <ToastRegion />
    </div>
</template>
