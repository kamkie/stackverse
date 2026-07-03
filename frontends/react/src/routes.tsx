import { Navigate, type RouteObject } from "react-router";
import { Layout } from "./components/Layout";
import { MyBookmarksPage } from "./pages/MyBookmarksPage";
import { MyReportsPage } from "./pages/MyReportsPage";
import { PublicFeedPage } from "./pages/PublicFeedPage";
import { AdminLayout } from "./pages/admin/AdminLayout";
import { AuditLogPage } from "./pages/admin/AuditLogPage";
import { DashboardPage } from "./pages/admin/DashboardPage";
import { MessagesPage } from "./pages/admin/MessagesPage";
import { ReportsPage } from "./pages/admin/ReportsPage";
import { UsersPage } from "./pages/admin/UsersPage";

export const routes: RouteObject[] = [
  {
    path: "/",
    element: <Layout />,
    children: [
      // Land on the public feed — the only screen an anonymous visitor can use.
      { index: true, element: <Navigate to="/feed" replace /> },
      { path: "bookmarks", element: <MyBookmarksPage /> },
      { path: "reports", element: <MyReportsPage /> },
      { path: "feed", element: <PublicFeedPage /> },
      {
        path: "admin",
        element: <AdminLayout />,
        children: [
          { index: true, element: <DashboardPage /> },
          { path: "reports", element: <ReportsPage /> },
          { path: "users", element: <UsersPage /> },
          { path: "audit", element: <AuditLogPage /> },
          { path: "messages", element: <MessagesPage /> },
        ],
      },
    ],
  },
];
