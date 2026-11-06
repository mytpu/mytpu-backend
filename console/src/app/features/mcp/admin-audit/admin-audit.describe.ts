import { BadgeKind } from '../../../ui/badge/badge.component';
import { flatten, Json, parse, text } from './admin-audit.fields';
import { AdminRow } from './admin-audit.model';

type Translate = (key: string, params?: Record<string, string | number>) => string;

export interface AuditSummary {
  /** Главная строка: что произошло */
  title: string;
  /** Вторая строка: причина, кому, ошибка */
  note: string | null;
  failed: boolean;
}

const K = 'mcp.adminAudit';

export function actionLabel(action: string, t: Translate): string {
  const key = `${K}.actions.${action.replaceAll('.', '_')}`;
  const label = t(key);
  return label === key ? action : label;
}

export function objectLabel(row: AdminRow, t: Translate): string {
  const key = `${K}.objects.${row.objectType}`;
  const label = t(key);
  return label === key ? row.objectType : label;
}

export function actionKind(row: AdminRow, failed: boolean): BadgeKind {
  if (failed) return 'danger';
  const a = row.action;
  if (a === 'key.revoke' || a.endsWith('.delete') || a === 'rag.delete' || a === 'key_request.reject') return 'danger';
  if (a.startsWith('mode.')) return 'graphite';
  if (a.endsWith('.state')) return 'warn';
  if (a.startsWith('connector.') || a.startsWith('rag.')) return 'info';
  if (a === 'key.create' || a === 'key.resume' || a === 'key_request.approve' || a === 'connector.publish') return 'ok';
  return 'neutral';
}

export function summarize(row: AdminRow, t: Translate): AuditSummary {
  const before = parse(row.before);
  const after = parse(row.after);
  const status = typeof after?.['status'] === 'number' ? (after['status'] as number) : null;
  const failed = status !== null && status >= 400;
  const s = (key: string, params?: Record<string, string | number>) => t(`${K}.sum.${key}`, params);
  const reason = (o: Json | null) => text(o?.['reason']);
  let title: string;
  let note: string | null = null;

  switch (row.action) {
    case 'mode.set': {
      const from = modeName(text(before?.['mode']), t);
      const to = modeName(text(after?.['mode']), t) ?? '—';
      title = from && from !== to ? s('modeChanged', { from, to }) : s('modeSet', { to });
      note = quoted(reason(after), t);
      break;
    }
    case 'tool.state':
    case 'integration.state':
      title = after?.['enabled'] ? s('enabled') : s('disabled');
      note = after?.['enabled'] ? null : quoted(reason(after), t);
      break;
    case 'key.create':
      title = s('keyCreated', { owner: text(after?.['owner']) ?? row.objectId });
      note = list(after?.['scopes']);
      break;
    case 'key.revoke':
      title = s('keyRevoked');
      break;
    case 'key.resume':
      title = s('keyResumed');
      break;
    case 'key.reissue':
      title = s('keyReissued');
      break;
    case 'key.decoy_group': {
      const members = Array.isArray(after?.['members']) ? (after['members'] as unknown[]).length : 0;
      title = s('decoyGroup', { count: members });
      break;
    }
    case 'key_request.create':
      title = s('requestCreated', { requester: text(after?.['requester']) ?? '—' });
      note = list(after?.['scopes']);
      break;
    case 'key_request.approve':
      title = s('requestApproved');
      note = text(after?.['clientId']);
      break;
    case 'key_request.reject':
      title = s('requestRejected');
      note = quoted(text(after?.['rejectReason']), t);
      break;
    case 'connector.secret.set':
      title = s('secretSet', { name: text(after?.['secret']) ?? '—' });
      break;
    case 'connector.secret.delete':
      title = s('secretDeleted', { name: text(before?.['secret']) ?? '—' });
      break;
    case 'connector.test':
      title = s('connectorTest', { tool: text(after?.['tool']) ?? '—', version: text(after?.['version']) ?? '—' });
      break;
    case 'connector.version':
      title = s('connectorVersion', { version: text(after?.['version']) ?? '—' });
      break;
    case 'connector.publish':
      title = s('connectorPublished', { version: text(after?.['version']) ?? '—' });
      note = publishedBefore(before, t);
      break;
    case 'connector.unpublish':
      title = s('connectorUnpublished');
      note = publishedBefore(before, t);
      break;
    default:
      if (row.action.startsWith('rag.')) {
        title = ragTitle(row, before, s);
        if (row.objectId === '/playground') note = quoted(text(before?.['query']), t);
        break;
      }
      title = changedSummary(before, after, s);
  }

  if (failed) {
    note = s('failed', { status: status! }) + (note ? ` · ${note}` : '');
  }
  return { title, note, failed };
}

function ragTitle(row: AdminRow, before: Json | null, s: Translate): string {
  const method = row.action.slice('rag.'.length);
  const path = row.objectId ?? '';
  const rules: [RegExp, string, string][] = [
    [/^\/playground$/, 'post', 'ragPlayground'],
    [/^\/documents\/[^/]+$/, 'put', 'ragDocumentChanged'],
    [/^\/documents\/[^/]+$/, 'delete', 'ragDocumentDeleted'],
    [/^\/eval\/questions$/, 'post', 'ragQuestionAdded'],
    [/^\/eval\/questions\/import$/, 'post', 'ragQuestionsImported'],
    [/^\/eval\/questions\/[^/]+$/, 'put', 'ragQuestionChanged'],
    [/^\/eval\/questions\/[^/]+$/, 'delete', 'ragQuestionDeleted'],
    [/^\/eval\/runs$/, 'post', 'ragEvalRun'],
    [/^\/sources\/files$/, 'post', 'ragFileUploaded'],
    [/^\/sources\/page$/, 'post', 'ragPageAdded'],
    [/^\/sources\/[^/]+\/reindex$/, 'post', 'ragReindex'],
    [/^\/sources\/[^/]+\/state$/, 'put', 'ragSourceState'],
    [/^\/sources\/[^/]+$/, 'delete', 'ragSourceDeleted'],
  ];
  const rule = rules.find(([re, m]) => m === method && re.test(path));
  if (!rule) return s('ragOther', { method: method.toUpperCase(), path });
  if (rule[2] === 'ragSourceState' && typeof before?.['enabled'] === 'boolean') {
    return s(before['enabled'] ? 'ragSourceOn' : 'ragSourceOff');
  }
  if (rule[2] === 'ragPageAdded') return s('ragPageAdded', { url: text(before?.['url']) ?? '' });
  return s(rule[2]);
}

function changedSummary(before: Json | null, after: Json | null, s: Translate): string {
  const fields = flatten(before);
  const next = flatten(after);
  const changed = [...new Set([...fields.keys(), ...next.keys()])].filter((p) => fields.get(p) !== next.get(p));
  if (changed.length === 0) return s('nothingChanged');
  if (changed.length <= 2) {
    return changed.map((p) => `${p}: ${fields.get(p) ?? '—'} → ${next.get(p) ?? '—'}`).join('; ');
  }
  return s('fieldsChanged', { count: changed.length });
}

function publishedBefore(before: Json | null, t: Translate): string | null {
  const v = text(before?.['version']);
  return v && v !== 'null' ? t(`${K}.sum.wasVersion`, { version: v }) : null;
}

function modeName(mode: string | null, t: Translate): string | null {
  if (!mode) return null;
  const key = { normal: 'normalTitle', read_only: 'readOnlyTitle', internal_only: 'internalOnlyTitle' }[mode];
  return key ? t(`mcp.mode.${key}`) : mode;
}

function quoted(value: string | null, t: Translate): string | null {
  return value && value !== 'null' ? t(`${K}.sum.quoted`, { text: value }) : null;
}

function list(value: unknown): string | null {
  return Array.isArray(value) && value.length > 0 ? value.join(', ') : null;
}
