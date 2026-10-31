import { Routes } from '@angular/router';
import { authGuard } from './core/session/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () =>
      import('./features/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./layout/shell/shell.component').then((m) => m.ShellComponent),
    children: [
      {
        path: '',
        pathMatch: 'full',
        redirectTo: 'mcp/overview',
      },
      {
        path: 'mcp/overview',
        loadComponent: () =>
          import('./layout/placeholder/placeholder.component').then(
            (m) => m.PlaceholderComponent,
          ),
        data: { step: 8, title: 'nav.overview', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/keys',
        loadComponent: () =>
          import('./layout/placeholder/placeholder.component').then(
            (m) => m.PlaceholderComponent,
          ),
        data: { step: 8, title: 'nav.keys', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/key-requests',
        loadComponent: () =>
          import('./layout/placeholder/placeholder.component').then(
            (m) => m.PlaceholderComponent,
          ),
        data: { step: 8, title: 'nav.keyRequests', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/key-events',
        loadComponent: () =>
          import('./layout/placeholder/placeholder.component').then(
            (m) => m.PlaceholderComponent,
          ),
        data: { step: 8, title: 'nav.keyEvents', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/integrations',
        loadComponent: () =>
          import('./layout/placeholder/placeholder.component').then(
            (m) => m.PlaceholderComponent,
          ),
        data: { step: 9, title: 'nav.integrations', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/connectors',
        loadComponent: () =>
          import('./layout/placeholder/placeholder.component').then(
            (m) => m.PlaceholderComponent,
          ),
        data: { step: 10, title: 'nav.connectors', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/mode',
        loadComponent: () =>
          import('./layout/placeholder/placeholder.component').then(
            (m) => m.PlaceholderComponent,
          ),
        data: { step: 9, title: 'nav.mode', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/audit',
        loadComponent: () =>
          import('./layout/placeholder/placeholder.component').then(
            (m) => m.PlaceholderComponent,
          ),
        data: { step: 9, title: 'nav.audit', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/admin-audit',
        loadComponent: () =>
          import('./layout/placeholder/placeholder.component').then(
            (m) => m.PlaceholderComponent,
          ),
        data: { step: 9, title: 'nav.adminAudit', meta: 'nav.groupMcp' },
      },
      {
        path: 'rag/overview',
        loadComponent: () =>
          import('./layout/placeholder/placeholder.component').then(
            (m) => m.PlaceholderComponent,
          ),
        data: { step: 11, title: 'nav.ragOverview', meta: 'nav.groupRag' },
      },
      {
        path: 'rag/sources',
        loadComponent: () =>
          import('./layout/placeholder/placeholder.component').then(
            (m) => m.PlaceholderComponent,
          ),
        data: { step: 11, title: 'nav.ragSources', meta: 'nav.groupRag' },
      },
      {
        path: 'rag/documents',
        loadComponent: () =>
          import('./layout/placeholder/placeholder.component').then(
            (m) => m.PlaceholderComponent,
          ),
        data: { step: 11, title: 'nav.ragDocuments', meta: 'nav.groupRag' },
      },
      {
        path: 'rag/playground',
        loadComponent: () =>
          import('./layout/placeholder/placeholder.component').then(
            (m) => m.PlaceholderComponent,
          ),
        data: { step: 11, title: 'nav.ragPlayground', meta: 'nav.groupRag' },
      },
      {
        path: 'rag/eval',
        loadComponent: () =>
          import('./layout/placeholder/placeholder.component').then(
            (m) => m.PlaceholderComponent,
          ),
        data: { step: 11, title: 'nav.ragEval', meta: 'nav.groupRag' },
      },
      {
        path: 'rag/misses',
        loadComponent: () =>
          import('./layout/placeholder/placeholder.component').then(
            (m) => m.PlaceholderComponent,
          ),
        data: { step: 11, title: 'nav.ragMisses', meta: 'nav.groupRag' },
      },
    ],
  },
  {
    path: '**',
    redirectTo: 'login',
  },
];
