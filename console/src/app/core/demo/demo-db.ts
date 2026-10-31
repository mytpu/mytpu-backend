import { IssuedKey, KeyView, ResumeRequest } from '../../features/mcp/keys/keys.model';
import { IntegrationCatalogView, NewKeyBody, PermissionsChangeBody } from '../../features/mcp/key-wizard/key-wizard.model';
import { ApproveBody, CreateKeyRequestBody, KeyRequestView } from '../../features/mcp/key-requests/key-requests.model';
import { DecoyGroupView, ExpiringKeyView, KeyEventView } from '../../features/mcp/key-events/key-events.model';
import { OverviewView } from '../../features/mcp/overview/overview.model';
import { INITIAL_DECOY_GROUPS, INITIAL_KEYS } from './demo-fixtures-keys';
import { INITIAL_REQUESTS } from './demo-fixtures-requests';
import { INITIAL_EVENTS } from './demo-fixtures-events';
import { INITIAL_CATALOG } from './demo-fixtures-catalog';
import { getDemoOverview } from './demo-fixtures-overview';

export class DemoDb {
  private keys: KeyView[] = INITIAL_KEYS.map((k) => ({ ...k }));
  private decoyGroups = new Map<string, string[]>(Object.entries(INITIAL_DECOY_GROUPS));
  private requests: KeyRequestView[] = INITIAL_REQUESTS.map((r) => ({ ...r }));
  private events: KeyEventView[] = INITIAL_EVENTS.map((e) => ({ ...e }));
  private nextEventId = 61;

  getKeys(): KeyView[] { return this.keys.map((k) => ({ ...k })); }

  getKey(id: string): KeyView | undefined {
    const k = this.keys.find((x) => x.id === id);
    return k ? { ...k } : undefined;
  }

  createKey(body: NewKeyBody): IssuedKey {
    const id = 'k-' + Math.random().toString(36).substring(2, 9);
    const rawKey = `mtpu_demo_${Math.random().toString(36).substring(2, 12)}${Math.random().toString(36).substring(2, 6)}`;
    const expiresAt = new Date(Date.now() + (body.days || 30) * 86400000).toISOString();
    const created: KeyView = {
      id, owner: body.owner, keyPrefix: rawKey.substring(0, 9), kind: body.kind || 'person',
      scopes: body.scopes || [], roles: body.roles || [], deniedTools: body.deniedTools || [],
      rateLimits: body.rateLimits || {}, readOnly: body.write === false, enabled: true,
      expiresAt, networks: body.networks || [], suspended: false, decoy: Boolean(body.decoy),
      createdAt: new Date().toISOString(), lastUsedAt: null, calls30d: 0, previousKeyUntil: null,
      geo: body.geo || 'ru_only', geoReason: body.geoReason || null,
    };
    this.keys.unshift(created);
    this.recordEvent(id, 'key.new_network', created.networks[0] || null, 'new key created');
    return { clientId: id, key: rawKey, command: `claude mcp add mytpu --url https://mcp.tpu.ru/mcp --header "Authorization: Bearer ${rawKey}"` };
  }

  reissueKey(id: string, graceHours = 24): IssuedKey {
    const k = this.keys.find((x) => x.id === id);
    const rawKey = `mtpu_demo_${Math.random().toString(36).substring(2, 12)}${Math.random().toString(36).substring(2, 6)}`;
    if (k) {
      k.keyPrefix = rawKey.substring(0, 9);
      k.previousKeyUntil = new Date(Date.now() + graceHours * 3600000).toISOString();
      this.recordEvent(id, 'key.self_rotated', k.networks[0] || null, `grace_hours=${graceHours}`);
    }
    return { clientId: id, key: rawKey, command: `claude mcp add mytpu --url https://mcp.tpu.ru/mcp --header "Authorization: Bearer ${rawKey}"` };
  }

  revokeKey(id: string): KeyView {
    const k = this.keys.find((x) => x.id === id);
    if (!k) throw new Error('key_not_found');
    k.enabled = false;
    this.recordEvent(id, 'key.unused_revoked', null, 'admin revoked');
    return { ...k };
  }

  resumeKey(id: string, req?: ResumeRequest): KeyView {
    const k = this.keys.find((x) => x.id === id);
    if (!k) throw new Error('key_not_found');
    k.suspended = false;
    if (req?.geo) k.geo = req.geo;
    if (req?.geoReason) k.geoReason = req.geoReason;
    this.recordEvent(id, 'key.resumed', null, req?.geo ? `geo=${req.geo}` : null);
    return { ...k };
  }

  updatePermissions(id: string, body: PermissionsChangeBody): KeyView {
    const k = this.keys.find((x) => x.id === id);
    if (!k) throw new Error('key_not_found');
    k.scopes = body.scopes;
    if (body.deniedTools) k.deniedTools = body.deniedTools;
    if (body.rateLimits) k.rateLimits = body.rateLimits;
    if (body.readOnly !== undefined) k.readOnly = body.readOnly;
    if (body.networks) k.networks = body.networks;
    if (body.geo) k.geo = body.geo;
    if (body.geoReason) k.geoReason = body.geoReason;
    return { ...k };
  }

  getExpiringKeys(days = 14): ExpiringKeyView[] {
    const now = Date.now();
    const limit = now + days * 86400000;
    return this.keys
      .filter((k) => k.enabled && !k.decoy && new Date(k.expiresAt).getTime() > now && new Date(k.expiresAt).getTime() <= limit)
      .map((k) => ({
        id: k.id, owner: k.owner, keyPrefix: k.keyPrefix, kind: k.kind,
        expiresAt: k.expiresAt, lastUsedAt: k.lastUsedAt, suspended: k.suspended,
      }));
  }

  getDecoyGroup(decoyId: string): DecoyGroupView {
    return { decoyId, members: [...(this.decoyGroups.get(decoyId) || [])] };
  }

  updateDecoyGroup(decoyId: string, members: string[]): DecoyGroupView {
    this.decoyGroups.set(decoyId, [...members]);
    return { decoyId, members: [...members] };
  }

  getKeyRequests(status?: string): KeyRequestView[] {
    return this.requests
      .filter((r) => (!status || status === 'all' ? true : r.status === status))
      .map((r) => ({ ...r }));
  }

  createKeyRequest(body: CreateKeyRequestBody): KeyRequestView {
    const newReq: KeyRequestView = {
      id: 'req-' + (100 + this.requests.length + 1),
      at: new Date().toISOString(), requester: body.requester, contact: body.contact,
      purpose: body.purpose, scopes: body.scopes || [], kind: body.kind || 'person',
      days: body.days || 30, networks: body.networks || [], status: 'new',
      decidedBy: null, decidedAt: null, rejectReason: null, clientId: null,
    };
    this.requests.unshift(newReq);
    return { ...newReq };
  }

  approveKeyRequest(id: string, body?: ApproveBody, decidedBy = 'Демидов А. В.'): IssuedKey {
    const r = this.requests.find((x) => x.id === id);
    if (!r) throw new Error('request_not_found');
    const issued = this.createKey({
      owner: body?.owner || r.requester, scopes: body?.scopes || r.scopes,
      kind: body?.kind || r.kind, days: body?.days || r.days, networks: body?.networks || r.networks,
      write: body?.write ?? true, geo: body?.geo, geoReason: body?.geoReason,
    });
    r.status = 'approved';
    r.decidedBy = decidedBy;
    r.decidedAt = new Date().toISOString();
    r.clientId = issued.clientId;
    return issued;
  }

  rejectKeyRequest(id: string, reason: string, decidedBy = 'Демидов А. В.'): KeyRequestView {
    const r = this.requests.find((x) => x.id === id);
    if (!r) throw new Error('request_not_found');
    r.status = 'rejected';
    r.decidedBy = decidedBy;
    r.decidedAt = new Date().toISOString();
    r.rejectReason = reason;
    return { ...r };
  }

  getKeyEvents(event?: string, client?: string, limit = 100): KeyEventView[] {
    return this.events
      .filter((e) => (!event || event === 'all' ? true : e.event === event))
      .filter((e) => (!client ? true : e.clientId.includes(client) || e.owner.toLowerCase().includes(client.toLowerCase())))
      .slice(0, limit)
      .map((e) => ({ ...e }));
  }

  recordEvent(clientId: string, event: string, network: string | null = null, detail: string | null = null): void {
    const k = this.keys.find((x) => x.id === clientId);
    this.events.unshift({
      id: this.nextEventId++, at: new Date().toISOString(), clientId,
      owner: k?.owner || clientId, event, network, detail,
    });
  }

  getCatalog(): IntegrationCatalogView[] { return INITIAL_CATALOG; }
  getOverview(hours = 24): OverviewView { return getDemoOverview(hours); }
}
