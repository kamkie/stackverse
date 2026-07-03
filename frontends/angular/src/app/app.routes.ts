import type { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'bookmarks' },
  {
    path: 'bookmarks',
    loadComponent: () => import('./pages/my-bookmarks-page').then((m) => m.MyBookmarksPage),
  },
  {
    path: 'reports',
    loadComponent: () => import('./pages/my-reports-page').then((m) => m.MyReportsPage),
  },
  {
    path: 'feed',
    loadComponent: () => import('./pages/public-feed-page').then((m) => m.PublicFeedPage),
  },
  {
    path: 'admin',
    loadChildren: () => import('./admin/admin.routes').then((m) => m.ADMIN_ROUTES),
  },
];
