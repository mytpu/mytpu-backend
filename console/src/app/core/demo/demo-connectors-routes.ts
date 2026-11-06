import { HttpRequest } from '@angular/common/http';
import { RouteResult } from './demo-routes';
import { DemoConnectorRecord, INITIAL_DEMO_CONNECTORS } from './demo-fixtures-connectors';
import {
  ConnectorDetails,
  ConnectorListItem,
  ConnectorSpec,
  ConnectorVersion,
} from '../../features/mcp/connectors/connectors.model';

export class DemoConnectorsRoutes {
  private connectors: DemoConnectorRecord[] = INITIAL_DEMO_CONNECTORS.map((c) => ({
    ...c,
    secretsSet: [...c.secretsSet],
    secretsNeeded: [...c.secretsNeeded],
    versions: c.versions.map((v) => ({ ...v })),
  }));

  handle(
    req: HttpRequest<unknown>,
    path: string,
    method: string,
    getParam: (k: string) => string | null,
  ): RouteResult | null {
    if (path === '/admin/api/connectors' && method === 'GET') {
      const list: ConnectorListItem[] = this.connectors.map((c) => {
        const latestSpec = c.versions[0]?.spec;
        const latestVersion = c.versions.reduce((max, v) => Math.max(max, v.version), 0);
        return {
          connector: {
            id: c.id,
            title: latestSpec?.title || c.id,
            publishedVersion: c.publishedVersion,
            latestVersion,
            updatedAt: c.updatedAt,
          },
          secretsSet: [...c.secretsSet],
          secretsNeeded: [...c.secretsNeeded],
        };
      });
      return { status: 200, body: list };
    }

    const matchVersions = path.match(/^\/admin\/api\/connectors\/([^/]+)\/versions$/);
    if (matchVersions && method === 'POST') {
      const id = decodeURIComponent(matchVersions[1]);
      const spec = req.body as ConnectorSpec;
      if (spec?.baseUrl && spec.baseUrl.includes('10.0.0.1')) {
        return {
          status: 400,
          body: {
            error: 'connector_invalid',
            message: 'Коннектор содержит ошибки',
            problems: ['Адрес 10.0.0.1 во внутренней сети'],
          },
        };
      }
      let conn = this.connectors.find((c) => c.id === id);
      const nextVer = conn ? conn.versions.reduce((max, v) => Math.max(max, v.version), 0) + 1 : 1;
      const newVersion: ConnectorVersion = {
        version: nextVer,
        createdAt: new Date().toISOString(),
        createdBy: 'admin',
        published: false,
        spec,
      };
      if (!conn) {
        conn = {
          id,
          publishedVersion: null,
          secretsSet: [],
          secretsNeeded: spec?.auth?.type === 'none' ? [] : ['api_key'],
          updatedAt: new Date().toISOString(),
          versions: [newVersion],
        };
        this.connectors.unshift(conn);
      } else {
        conn.updatedAt = new Date().toISOString();
        conn.versions.unshift(newVersion);
      }
      return { status: 201, body: { id, version: nextVer } };
    }

    const matchPublish = path.match(/^\/admin\/api\/connectors\/([^/]+)\/versions\/(\d+)\/publish$/);
    if (matchPublish && method === 'POST') {
      const id = decodeURIComponent(matchPublish[1]);
      const version = Number(matchPublish[2]);
      const conn = this.connectors.find((c) => c.id === id);
      if (!conn) {
        return { status: 404, body: { error: 'connector_not_found' } };
      }
      const targetVersion = conn.versions.find((v) => v.version === version);
      if (!targetVersion) {
        return { status: 404, body: { error: 'connector_version_not_found' } };
      }
      conn.publishedVersion = version;
      conn.updatedAt = new Date().toISOString();
      for (const v of conn.versions) {
        v.published = v.version === version;
      }
      return { status: 200, body: { id, publishedVersion: version } };
    }

    const matchUnpublish = path.match(/^\/admin\/api\/connectors\/([^/]+)\/unpublish$/);
    if (matchUnpublish && method === 'POST') {
      const id = decodeURIComponent(matchUnpublish[1]);
      const conn = this.connectors.find((c) => c.id === id);
      if (!conn) {
        return { status: 404, body: { error: 'connector_not_found' } };
      }
      conn.publishedVersion = null;
      conn.updatedAt = new Date().toISOString();
      for (const v of conn.versions) {
        v.published = false;
      }
      return { status: 200, body: { id, published: false } };
    }

    const matchDetails = path.match(/^\/admin\/api\/connectors\/([^/]+)$/);
    if (matchDetails && method === 'GET') {
      const id = decodeURIComponent(matchDetails[1]);
      const conn = this.connectors.find((c) => c.id === id);
      if (!conn) {
        return { status: 404, body: { error: 'connector_not_found' } };
      }
      const details: ConnectorDetails = {
        id: conn.id,
        publishedVersion: conn.publishedVersion,
        secretsSet: [...conn.secretsSet],
        versions: conn.versions.map((v) => ({ ...v })),
      };
      return { status: 200, body: details };
    }

    const matchTest = path.match(/^\/admin\/api\/connectors\/([^/]+)\/versions\/(\d+)\/test$/);
    if (matchTest && method === 'POST') {
      const id = decodeURIComponent(matchTest[1]);
      const conn = this.connectors.find((c) => c.id === id);
      if (!conn) return { status: 404, body: { ok: false, error: 'connector_not_found' } };
      const body = (req.body || {}) as { tool?: string; arguments?: Record<string, unknown> };
      const { tool, arguments: args = {} } = body;

      if (id === 'tpu_news' && !conn.secretsSet.includes('api_key')) {
        return { status: 200, body: { ok: false, error: 'secret_missing', message: 'Не задан секрет api_key' } };
      }
      if (id === 'nager_holidays' && tool === 'holidays_year') {
        const year = args['year'];
        if (year === undefined || year === null || year === '') {
          return { status: 200, body: { ok: false, error: 'invalid_arguments', problems: ['Параметр year обязателен'] } };
        }
        const y = Number(year);
        const holidays = [
          { date: `${y}-01-01`, localName: 'Новый год', name: "New Year's Day" },
          { date: `${y}-01-07`, localName: 'Рождество Христово', name: 'Orthodox Christmas Day' },
          { date: `${y}-02-23`, localName: 'День защитника Отечества', name: 'Defender of the Fatherland Day' },
        ];
        return {
          status: 200,
          body: {
            ok: true, status: 200, raw: JSON.stringify(holidays, null, 2), rawTruncated: false,
            summary: `Праздников в ${y} году: 3 (показаны первые 3)`, data: holidays,
          },
        };
      }
      return { status: 200, body: { ok: true, status: 200, raw: '{\n  "status": "ok"\n}', rawTruncated: false, summary: 'Успешно', data: { status: 'ok' } } };
    }

    const matchSecret = path.match(/^\/admin\/api\/connectors\/([^/]+)\/secrets\/([^/]+)$/);
    if (matchSecret && (method === 'PUT' || method === 'DELETE')) {
      const id = decodeURIComponent(matchSecret[1]);
      const name = decodeURIComponent(matchSecret[2]);
      const conn = this.connectors.find((c) => c.id === id);
      if (!conn) return { status: 404, body: { error: 'connector_not_found' } };
      const set = method === 'PUT';
      if (set && !conn.secretsSet.includes(name)) conn.secretsSet.push(name);
      if (!set) conn.secretsSet = conn.secretsSet.filter((s) => s !== name);
      return { status: 200, body: { id, secret: name, set } };
    }

    return null;
  }
}
