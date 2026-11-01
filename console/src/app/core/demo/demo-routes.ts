import { HttpRequest } from '@angular/common/http';
import { DemoDb } from './demo-db';
import { DemoMcpStore } from './demo-mcp-store';
import { DemoMcpRoutes } from './demo-mcp-routes';

export interface RouteResult {
  status: number;
  body: unknown;
}

export class DemoRoutes {
  private readonly mcpStore = new DemoMcpStore();
  private readonly mcpRoutes = new DemoMcpRoutes(this.mcpStore);

  constructor(private readonly db: DemoDb) {}

  handle(req: HttpRequest<unknown>): RouteResult {
    const url = new URL(req.urlWithParams || req.url, 'http://localhost');
    const path = url.pathname;
    const method = req.method.toUpperCase();
    const getParam = (name: string): string | null => url.searchParams.get(name) ?? req.params?.get(name) ?? null;

    const mcpRes = this.mcpRoutes.handle(req, path, method, getParam);
    if (mcpRes) {
      return mcpRes;
    }

    if (path === '/admin/api/me' && method === 'GET') {
      return this.handleMe(req);
    }
    if (path === '/admin/api/overview' && method === 'GET') {
      const hours = Number(getParam('hours')) || 24;
      return { status: 200, body: this.db.getOverview(hours) };
    }
    if (path === '/admin/api/catalog' && method === 'GET') {
      return { status: 200, body: this.mcpStore.getCatalog() };
    }
    if (path === '/admin/api/keys/expiring' && method === 'GET') {
      const days = Number(getParam('days')) || 14;
      return { status: 200, body: this.db.getExpiringKeys(days) };
    }
    if (path === '/admin/api/key-events' && method === 'GET') {
      const event = getParam('event') || undefined;
      const client = getParam('client') || undefined;
      const limit = Number(getParam('limit')) || 100;
      return { status: 200, body: this.db.getKeyEvents(event, client, limit) };
    }
    if (path === '/admin/api/key-requests' && method === 'GET') {
      const status = getParam('status') || undefined;
      return { status: 200, body: this.db.getKeyRequests(status) };
    }
    if (path === '/admin/api/key-requests' && method === 'POST') {
      return { status: 201, body: this.db.createKeyRequest(req.body as any) };
    }
    const matchReqApprove = path.match(/^\/admin\/api\/key-requests\/([^/]+)\/approve$/);
    if (matchReqApprove && method === 'POST') {
      return { status: 200, body: this.db.approveKeyRequest(matchReqApprove[1], req.body as any) };
    }
    const matchReqReject = path.match(/^\/admin\/api\/key-requests\/([^/]+)\/reject$/);
    if (matchReqReject && method === 'POST') {
      const reason = (req.body as any)?.reason || 'Отклонено администратором';
      return { status: 200, body: this.db.rejectKeyRequest(matchReqReject[1], reason) };
    }
    if (path === '/admin/api/keys' && method === 'GET') {
      return { status: 200, body: this.db.getKeys() };
    }
    if (path === '/admin/api/keys' && method === 'POST') {
      return { status: 201, body: this.db.createKey(req.body as any) };
    }
    const matchDecoyGroup = path.match(/^\/admin\/api\/keys\/([^/]+)\/decoy-group$/);
    if (matchDecoyGroup) {
      if (method === 'GET') {
        return { status: 200, body: this.db.getDecoyGroup(matchDecoyGroup[1]) };
      }
      if (method === 'PUT') {
        const members = (req.body as any)?.members || [];
        return { status: 200, body: this.db.updateDecoyGroup(matchDecoyGroup[1], members) };
      }
    }
    const matchReissue = path.match(/^\/admin\/api\/keys\/([^/]+)\/reissue$/);
    if (matchReissue && method === 'POST') {
      const grace = (req.body as any)?.graceHours;
      return { status: 200, body: this.db.reissueKey(matchReissue[1], grace) };
    }
    const matchRevoke = path.match(/^\/admin\/api\/keys\/([^/]+)\/revoke$/);
    if (matchRevoke && method === 'POST') {
      return { status: 200, body: this.db.revokeKey(matchRevoke[1]) };
    }
    const matchResume = path.match(/^\/admin\/api\/keys\/([^/]+)\/resume$/);
    if (matchResume && method === 'POST') {
      return { status: 200, body: this.db.resumeKey(matchResume[1], req.body as any) };
    }
    const matchPermissions = path.match(/^\/admin\/api\/keys\/([^/]+)\/permissions$/);
    if (matchPermissions && method === 'PUT') {
      return { status: 200, body: this.db.updatePermissions(matchPermissions[1], req.body as any) };
    }

    console.warn('[DemoDb] Unhandled /admin/api endpoint:', method, req.urlWithParams || req.url);
    return { status: 404, body: { error: 'not_found' } };
  }

  private handleMe(req: HttpRequest<unknown>): RouteResult {
    const authHeader = req.headers.get('Authorization') || '';
    const match = authHeader.match(/^Bearer\s+(.+)$/i);
    const token = (match ? match[1] : '').trim();

    if (!token || !token.startsWith('demo')) {
      return { status: 401, body: { error: 'invalid_key', message: 'Ключ не подходит' } };
    }

    if (token === 'demo-viewer' || token.startsWith('demo-viewer')) {
      return {
        status: 200,
        body: {
          clientId: 'demo-viewer-client',
          owner: 'Александр Демидов',
          roles: ['console.viewer'],
          canChange: false,
        },
      };
    }

    return {
      status: 200,
      body: {
        clientId: 'demo-admin-client',
        owner: 'Александр Демидов',
        roles: ['console.admin'],
        canChange: true,
      },
    };
  }
}
