import type { Routes } from '@angular/router';
import { AdminLayout } from './admin-layout';

export const ADMIN_ROUTES: Routes = [
  {
    path: '',
    component: AdminLayout,
    children: [
      {
        path: '',
        pathMatch: 'full',
        loadComponent: () => import('./dashboard-page').then((m) => m.DashboardPage),
      },
      {
        path: 'reports',
        loadComponent: () => import('./reports-page').then((m) => m.ReportsPage),
      },
      {
        path: 'users',
        loadComponent: () => import('./users-page').then((m) => m.UsersPage),
      },
      {
        path: 'audit',
        loadComponent: () => import('./audit-log-page').then((m) => m.AuditLogPage),
      },
      {
        path: 'messages',
        loadComponent: () => import('./messages-page').then((m) => m.MessagesPage),
      },
    ],
  },
];
