import { HttpRequest } from '@angular/common/http';
import { RouteResult } from './demo-routes';
import { DemoMcpStore } from './demo-mcp-store';

export class DemoMcpRoutes {
  constructor(private readonly store: DemoMcpStore) {}

  handle(req: HttpRequest<unknown>, path: string, method: string, getParam: (k: string) => string | null): RouteResult | null {
    if (path === '/admin/api/catalog' && method === 'GET') {
      return { status: 200, body: this.store.getCatalog() };
    }

    const matchIntegState = path.match(/^\/admin\/api\/integrations\/([^/]+)\/state$/);
    if (matchIntegState && method === 'PUT') {
      const b = req.body as any;
      return { status: 200, body: this.store.updateIntegrationState(matchIntegState[1], b?.enabled, b?.reason) };
    }

    const matchToolState = path.match(/^\/admin\/api\/tools\/([^/]+)\/state$/);
    if (matchToolState && method === 'PUT') {
      const b = req.body as any;
      return { status: 200, body: this.store.updateToolState(matchToolState[1], b?.enabled, b?.reason) };
    }

    const matchToolLimits = path.match(/^\/admin\/api\/tools\/([^/]+)\/limits$/);
    if (matchToolLimits && method === 'PUT') {
      return { status: 200, body: this.store.updateToolLimits(matchToolLimits[1], req.body as any) };
    }

    if (path === '/admin/api/mode') {
      if (method === 'GET') return { status: 200, body: this.store.getMode() };
      if (method === 'PUT') return { status: 200, body: this.store.changeMode(req.body as any) };
    }

    if (path === '/admin/api/audit' && method === 'GET') {
      const client = getParam('client') || undefined;
      const tool = getParam('tool') || undefined;
      const outcome = getParam('outcome') || undefined;
      const limit = Number(getParam('limit')) || 50;
      const before = getParam('before') ? Number(getParam('before')) : undefined;
      return { status: 200, body: this.store.getCalls({ client, tool, outcome, limit, before }) };
    }

    if (path === '/admin/api/audit.csv' && method === 'GET') {
      const calls = this.store.getCalls({ limit: 1000 });
      const header = 'id;at;request_id;client_id;channel;tool;args_hash;outcome;error_code;cache_hit;duration_ms\n';
      const lines = calls.map((c) =>
        `${c.id};${c.at};${c.requestId};"${c.clientId}";${c.channel};"${c.tool}";${c.argsHash};${c.outcome};"${c.errorCode}";${c.cacheHit};${c.durationMs}`
      ).join('\n');
      return { status: 200, body: '\uFEFF' + header + lines };
    }

    if (path === '/admin/api/admin-audit' && method === 'GET') {
      const from = getParam('from') || undefined;
      const actor = getParam('actor') || undefined;
      const type = getParam('type') || undefined;
      const object = getParam('object') || undefined;
      const limit = Number(getParam('limit')) || 50;
      const before = getParam('before') ? Number(getParam('before')) : undefined;
      return { status: 200, body: this.store.getAdminAudit({ from, actor, type, object, limit, before }) };
    }

    return null;
  }
}
