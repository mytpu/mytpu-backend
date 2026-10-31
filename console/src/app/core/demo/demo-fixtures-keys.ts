import { KeyView } from '../../features/mcp/keys/keys.model';

export const INITIAL_DECOY_GROUPS: Record<string, string[]> = {
  'k-decoy-kiber': ['k-stud-bot', 'k-dekanat-bot'],
  'k-decoy-infra': ['k-dorm-kiosk', 'k-lib-search'],
};

function key(
  id: string,
  owner: string,
  keyPrefix: string,
  kind: 'person' | 'server' | 'demo',
  scopes: string[],
  opts: Partial<KeyView> = {},
): KeyView {
  return {
    id,
    owner,
    keyPrefix,
    kind,
    scopes,
    roles: [],
    deniedTools: [],
    rateLimits: {},
    readOnly: true,
    enabled: true,
    suspended: false,
    decoy: false,
    networks: [],
    createdAt: '2026-09-01T10:00:00Z',
    lastUsedAt: '2026-10-31T20:00:00Z',
    calls30d: 120,
    previousKeyUntil: null,
    geo: 'ru_only',
    geoReason: null,
    expiresAt: '2027-10-01T00:00:00Z',
    ...opts,
  };
}

export const INITIAL_KEYS: KeyView[] = [
  key('k-decoy-kiber', 'Киберполигон (ловушка git)', 'mtpu_dec1', 'server', ['rasp.read'], {
    decoy: true, networks: ['10.2.0.0/16'], lastUsedAt: '2026-11-01T00:45:12Z', calls30d: 1,
  }),
  key('k-decoy-infra', 'Инфраструктура Caddy (ловушка)', 'mtpu_dec2', 'server', ['docs.read', 'rasp.read'], {
    decoy: true, networks: ['172.16.0.0/12'], lastUsedAt: null, calls30d: 0,
  }),
  key('k-stud-bot', 'Студсовет · Telegram-бот', 'mtpu_stbt', 'server', ['rasp.read'], {
    networks: ['10.2.14.0/24'], suspended: true, expiresAt: '2027-04-15T00:00:00Z', calls30d: 342,
  }),
  key('k-us-proxy', 'Смирнов А. В. · мобильный клиент', 'mtpu_smrn', 'person', ['rasp.read', 'docs.read'], {
    readOnly: false, suspended: true, expiresAt: '2026-11-20T00:00:00Z', calls30d: 89,
  }),
  key('k-revoked-dev', 'Кузнецов П. С. · старый ноутбук', 'mtpu_kznt', 'person', ['rasp.read'], {
    enabled: false, expiresAt: '2026-09-01T00:00:00Z', calls30d: 0,
  }),
  key('k-exp-grad', 'Выпускной проект · ИШПР', 'mtpu_vypk', 'demo', ['docs.read'], {
    expiresAt: '2026-11-06T12:00:00Z', calls30d: 45,
  }),
  key('k-exp-olymp', 'Олимпиадный портал ТПУ', 'mtpu_olmp', 'server', ['rasp.read'], {
    networks: ['176.108.244.0/24'], expiresAt: '2026-11-12T18:00:00Z', calls30d: 890,
  }),
  key('k-admin-console', 'Демидов А. В. · консоль администратора', 'mtpu_admn', 'person', ['docs.read', 'rasp.read'], {
    roles: ['console.admin'], readOnly: false, calls30d: 54,
  }),
  key('k-viewer-console', 'Аудитор ИБ · консоль наблюдателя', 'mtpu_view', 'person', [], {
    roles: ['console.viewer'], calls30d: 12,
  }),
  key('k-dekanat-bot', 'Деканат ИШИТР · бот расписания', 'mtpu_dkit', 'server', ['rasp.read'], {
    networks: ['10.2.0.0/16'], expiresAt: '2027-05-01T00:00:00Z', calls30d: 1240,
  }),
  key('k-lib-search', 'НТБ ТПУ · поисковый агент', 'mtpu_ntbl', 'server', ['docs.read'], {
    networks: ['176.108.244.0/24'], expiresAt: '2027-08-10T00:00:00Z', calls30d: 940,
  }),
  key('k-dorm-kiosk', 'Общежитие №8 · инфомат', 'mtpu_kios', 'server', ['rasp.read'], {
    networks: ['192.168.88.0/24'], expiresAt: '2027-03-20T00:00:00Z', calls30d: 680,
  }),
  key('k-prof-petrov', 'Петров В. М. · научная группа LLM', 'mtpu_ptrv', 'person', ['docs.read', 'rasp.read'], {
    expiresAt: '2026-12-31T00:00:00Z', geo: 'any', geoReason: 'Командировка в Казахстан (научная конференция)', calls30d: 310,
  }),
  key('k-demo-hackathon', 'Хакатон Томск 2026 · команда Альфа', 'mtpu_hck1', 'demo', ['rasp.read'], {
    expiresAt: '2026-11-15T00:00:00Z', calls30d: 120,
  }),
  key('k-ai-assistant', 'Мобильный ассистент Мой ТПУ', 'mtpu_asst', 'server', ['rasp.read', 'docs.read'], {
    networks: ['10.0.0.0/8'], readOnly: false, calls30d: 2600,
  }),
];
