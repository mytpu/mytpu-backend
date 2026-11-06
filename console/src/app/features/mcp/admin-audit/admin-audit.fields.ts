import { AdminRow } from './admin-audit.model';

export type Json = Record<string, unknown>;

export interface AuditField {
  path: string;
  before: string | null;
  after: string | null;
}

export interface AuditDetails {
  fields: AuditField[];
  hidden: number;
}

const MAX_VALUE = 200;
const MAX_FIELDS = 40;
/** Служебные поля, которые меняются сами и не говорят о сути действия */
const NOISE = new Set(['updatedAt', 'lastUsedAt', 'calls30d', 'at']);

/** Поля «было → стало». Для изменения — только изменившиеся; ответ rag и служебные поля не показываются */
export function details(row: AdminRow): AuditDetails {
  const before = flatten(withoutRagAnswer(row, parse(row.before)));
  const after = flatten(withoutRagAnswer(row, parse(row.after)));
  const paths = [...new Set([...before.keys(), ...after.keys()])];
  const both = before.size > 0 && after.size > 0;
  const fields = paths
    .map((path) => ({ path, before: before.get(path) ?? null, after: after.get(path) ?? null }))
    .filter((f) => !both || f.before !== f.after);
  return { fields: fields.slice(0, MAX_FIELDS), hidden: Math.max(0, fields.length - MAX_FIELDS) };
}

/** Ответ rag — служебный, в нём бывает до 16 КБ кандидатов поиска; оставляется только код ответа */
function withoutRagAnswer(row: AdminRow, value: Json | null): Json | null {
  if (!value || !row.action.startsWith('rag.') || !('answer' in value)) return value;
  const { answer: _answer, ...rest } = value;
  return rest;
}

export function text(value: unknown): string | null {
  if (value === null || value === undefined || value === '') return null;
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

export function parse(raw: string | null | undefined): Json | null {
  if (!raw) return null;
  try {
    const value = JSON.parse(raw);
    return value && typeof value === 'object' && !Array.isArray(value) ? (value as Json) : { value };
  } catch {
    return { value: raw };
  }
}

export function flatten(value: Json | null, prefix = '', out = new Map<string, string>()): Map<string, string> {
  if (!value) return out;
  for (const [key, v] of Object.entries(value)) {
    if (NOISE.has(key)) continue;
    const path = prefix ? `${prefix}.${key}` : key;
    if (v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length > 0) {
      flatten(v as Json, path, out);
    } else {
      out.set(path, short(v));
    }
  }
  return out;
}

function short(v: unknown): string {
  let s: string;
  if (v === null || v === undefined) s = '—';
  else if (Array.isArray(v)) s = v.every((x) => typeof x !== 'object' || x === null) ? (v.length ? v.join(', ') : '—') : JSON.stringify(v);
  else if (typeof v === 'object') s = Object.keys(v).length ? JSON.stringify(v) : '—';
  else s = String(v);
  return s.length > MAX_VALUE ? s.slice(0, MAX_VALUE) + '…' : s;
}
