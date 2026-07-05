import { createRouter, createWebHistory } from "vue-router";
import MyBookmarksPage from "./pages/MyBookmarksPage.vue";
import MyReportsPage from "./pages/MyReportsPage.vue";
import PublicFeedPage from "./pages/PublicFeedPage.vue";
import AdminLayout from "./pages/admin/AdminLayout.vue";
import AuditLogPage from "./pages/admin/AuditLogPage.vue";
import DashboardPage from "./pages/admin/DashboardPage.vue";
import MessagesPage from "./pages/admin/MessagesPage.vue";
import ReportsPage from "./pages/admin/ReportsPage.vue";
import UsersPage from "./pages/admin/UsersPage.vue";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/feed" },
    { path: "/feed", component: PublicFeedPage },
    { path: "/bookmarks", component: MyBookmarksPage },
    { path: "/reports", component: MyReportsPage },
    {
      path: "/admin",
      component: AdminLayout,
      children: [
        { path: "", component: DashboardPage },
        { path: "reports", component: ReportsPage },
        { path: "users", component: UsersPage },
        { path: "audit", component: AuditLogPage },
        { path: "messages", component: MessagesPage },
      ],
    },
  ],
});
