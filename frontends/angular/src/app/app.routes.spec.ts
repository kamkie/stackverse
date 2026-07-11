import type { Route, Routes } from '@angular/router';
import { AdminLayout } from './admin/admin-layout';
import { AuditLogPage } from './admin/audit-log-page';
import { DashboardPage } from './admin/dashboard-page';
import { MessagesPage } from './admin/messages-page';
import { ReportsPage } from './admin/reports-page';
import { UsersPage } from './admin/users-page';
import { routes } from './app.routes';
import { MyBookmarksPage } from './pages/my-bookmarks-page';
import { MyReportsPage } from './pages/my-reports-page';
import { PublicFeedPage } from './pages/public-feed-page';

const routeAt = (entries: Routes, path: string): Route => {
  const route = entries.find((candidate) => candidate.path === path);
  if (!route) throw new Error(`Missing route ${path}`);
  return route;
};

const loadComponent = async (route: Route): Promise<unknown> => {
  if (!route.loadComponent) throw new Error(`Route ${route.path} is not lazy`);
  return await (route.loadComponent as () => Promise<unknown>)();
};

describe('Angular route boundaries', () => {
  it('keeps each public route lazy and mapped to its contract screen', async () => {
    expect(routeAt(routes, '')).toMatchObject({ pathMatch: 'full', redirectTo: 'bookmarks' });
    await expect(loadComponent(routeAt(routes, 'bookmarks'))).resolves.toBe(MyBookmarksPage);
    await expect(loadComponent(routeAt(routes, 'reports'))).resolves.toBe(MyReportsPage);
    await expect(loadComponent(routeAt(routes, 'feed'))).resolves.toBe(PublicFeedPage);
  });

  it('loads the nested admin route table under the role-gated layout', async () => {
    const admin = routeAt(routes, 'admin');
    if (!admin.loadChildren) throw new Error('Admin route is not lazy');
    const loaded = await (admin.loadChildren as () => Promise<Routes>)();
    const shell = routeAt(loaded, '');

    expect(shell.component).toBe(AdminLayout);
    expect(shell.children?.map((route) => route.path)).toEqual([
      '',
      'reports',
      'users',
      'audit',
      'messages',
    ]);
    await expect(loadComponent(routeAt(shell.children ?? [], ''))).resolves.toBe(DashboardPage);
    await expect(loadComponent(routeAt(shell.children ?? [], 'reports'))).resolves.toBe(
      ReportsPage,
    );
    await expect(loadComponent(routeAt(shell.children ?? [], 'users'))).resolves.toBe(UsersPage);
    await expect(loadComponent(routeAt(shell.children ?? [], 'audit'))).resolves.toBe(AuditLogPage);
    await expect(loadComponent(routeAt(shell.children ?? [], 'messages'))).resolves.toBe(
      MessagesPage,
    );
  });
});
