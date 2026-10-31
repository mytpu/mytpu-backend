import { BadgeKind } from '../../../ui/badge/badge.component';
import { KeyView } from '../keys/keys.model';

export interface KeyEventView {
  id: number;
  at: string;
  clientId: string;
  owner: string;
  event: string;
  network: string | null;
  detail: string | null;
}

export interface ExpiringKeyView {
  id: string;
  owner: string;
  keyPrefix: string;
  kind: string;
  expiresAt: string;
  lastUsedAt: string | null;
  suspended: boolean;
}

export interface DecoyGroupView {
  decoyId: string;
  members: string[];
}

export interface EventBadgeInfo {
  labelKey: string;
  kind: BadgeKind;
}

export const EVENT_BADGES: Record<string, EventBadgeInfo> = {
  'key.decoy_used': { labelKey: 'mcp.events.labelDecoyUsed', kind: 'danger' },
  'key.group_suspended': { labelKey: 'mcp.events.labelGroupSuspended', kind: 'danger' },
  'key.inactive_suspended': { labelKey: 'mcp.events.labelInactiveSuspended', kind: 'warn' },
  'key.expiring': { labelKey: 'mcp.events.labelExpiring', kind: 'warn' },
  'key.network_burst': { labelKey: 'mcp.events.labelNetworkBurst', kind: 'warn' },
  'key.self_rotated': { labelKey: 'mcp.events.labelSelfRotated', kind: 'info' },
  'key.new_network': { labelKey: 'mcp.events.labelNewNetwork', kind: 'neutral' },
  'key.suspended': { labelKey: 'mcp.events.labelSuspended', kind: 'danger' },
  'key.resumed': { labelKey: 'mcp.events.labelResumed', kind: 'ok' },
  'key.network_denied': { labelKey: 'mcp.events.labelNetworkDenied', kind: 'danger' },
  'key.unused_revoked': { labelKey: 'mcp.events.labelUnusedRevoked', kind: 'neutral' },
  'key.foreign_country': { labelKey: 'mcp.events.labelForeignCountry', kind: 'warn' },
  'key.geo_unknown': { labelKey: 'mcp.events.labelGeoUnknown', kind: 'warn' },
};

export function getEventBadge(event: string): EventBadgeInfo {
  return EVENT_BADGES[event] ?? { labelKey: event, kind: 'neutral' };
}

export function mergeDecoyKeys(allKeys: KeyView[], events: KeyEventView[]): KeyView[] {
  const fromKeys = allKeys.filter((k) => k.decoy);
  const ids = new Set(fromKeys.map((k) => k.id));
  const merged = [...fromKeys];
  for (const e of events) {
    if (e.event === 'key.decoy_used' && !ids.has(e.clientId)) {
      ids.add(e.clientId);
      merged.push({
        id: e.clientId,
        owner: e.owner || e.clientId,
        keyPrefix: 'mtpu_…',
        kind: 'server',
        scopes: [],
        roles: [],
        deniedTools: [],
        rateLimits: {},
        readOnly: true,
        enabled: true,
        expiresAt: '',
        networks: [],
        suspended: false,
        decoy: true,
        createdAt: '',
        lastUsedAt: e.at,
        calls30d: 0,
        previousKeyUntil: null,
        geo: 'ru_only',
        geoReason: null,
      });
    }
  }
  return merged;
}
