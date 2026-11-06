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
          import('./features/mcp/overview/overview.page').then(
            (m) => m.OverviewPage,
          ),
        data: { title: 'nav.overview', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/keys',
        loadComponent: () =>
          import('./features/mcp/keys/keys.page').then((m) => m.KeysPage),
        data: { title: 'nav.keys', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/keys/new',
        loadComponent: () =>
          import('./features/mcp/key-wizard/key-wizard.page').then(
            (m) => m.KeyWizardPage,
          ),
        data: { title: 'mcp.wizard.title', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/key-requests',
        loadComponent: () =>
          import('./features/mcp/key-requests/key-requests.page').then(
            (m) => m.KeyRequestsPage,
          ),
        data: { title: 'nav.keyRequests', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/key-events',
        loadComponent: () =>
          import('./features/mcp/key-events/key-events.page').then(
            (m) => m.KeyEventsPage,
          ),
        data: { title: 'nav.keyEvents', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/integrations',
        loadComponent: () =>
          import('./features/mcp/integrations/integrations.page').then(
            (m) => m.IntegrationsPage,
          ),
        data: { title: 'nav.integrations', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/connectors',
        loadComponent: () =>
          import('./features/mcp/connectors/list/connectors-list.page').then(
            (m) => m.ConnectorsListPage,
          ),
        data: { title: 'nav.connectors', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/connectors/new',
        loadComponent: () =>
          import('./features/mcp/connectors/editor/connector-editor.page').then(
            (m) => m.ConnectorEditorPage,
          ),
        data: { title: 'connectors.editor.newTitle', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/connectors/:id/edit',
        loadComponent: () =>
          import('./features/mcp/connectors/editor/connector-editor.page').then(
            (m) => m.ConnectorEditorPage,
          ),
        data: { title: 'connectors.editor.editTitle', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/connectors/:id',
        loadComponent: () =>
          import('./features/mcp/connectors/versions/connector-versions.page').then(
            (m) => m.ConnectorVersionsPage,
          ),
        data: { title: 'nav.connectors', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/mode',
        loadComponent: () =>
          import('./features/mcp/mode/mode.page').then(
            (m) => m.ModePage,
          ),
        data: { title: 'nav.mode', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/audit',
        loadComponent: () =>
          import('./features/mcp/audit/audit.page').then(
            (m) => m.AuditPage,
          ),
        data: { title: 'nav.audit', meta: 'nav.groupMcp' },
      },
      {
        path: 'mcp/admin-audit',
        loadComponent: () =>
          import('./features/mcp/admin-audit/admin-audit.page').then(
            (m) => m.AdminAuditPage,
          ),
        data: { title: 'nav.adminAudit', meta: 'nav.groupMcp' },
      },
      {
        path: 'rag/overview',
        loadComponent: () =>
          import('./features/rag/overview/overview.page').then(
            (m) => m.RagOverviewPage,
          ),
        data: { title: 'nav.ragOverview', meta: 'nav.groupRag' },
      },
      {
        path: 'rag/sources',
        loadComponent: () =>
          import('./features/rag/sources/sources.page').then(
            (m) => m.SourcesPage,
          ),
        data: { title: 'nav.ragSources', meta: 'nav.groupRag' },
      },
      {
        path: 'rag/documents',
        loadComponent: () =>
          import('./features/rag/documents/documents.page').then(
            (m) => m.DocumentsPage,
          ),
        data: { title: 'nav.ragDocuments', meta: 'nav.groupRag' },
      },
      {
        path: 'rag/documents/:id',
        loadComponent: () =>
          import('./features/rag/document/document.page').then(
            (m) => m.DocumentPage,
          ),
        data: { title: 'rag.document.title', meta: 'nav.groupRag' },
      },
      {
        path: 'rag/playground',
        loadComponent: () =>
          import('./features/rag/playground/playground.page').then(
            (m) => m.PlaygroundPage,
          ),
        data: { title: 'nav.ragPlayground', meta: 'nav.groupRag' },
      },
      {
        path: 'rag/eval',
        loadComponent: () =>
          import('./features/rag/eval/eval.page').then(
            (m) => m.EvalPage,
          ),
        data: { title: 'nav.ragEval', meta: 'nav.groupRag' },
      },
      {
        path: 'rag/misses',
        loadComponent: () =>
          import('./features/rag/misses/misses.page').then(
            (m) => m.MissesPage,
          ),
        data: { title: 'nav.ragMisses', meta: 'nav.groupRag' },
      },
    ],
  },
  {
    path: '**',
    redirectTo: 'login',
  },
];
