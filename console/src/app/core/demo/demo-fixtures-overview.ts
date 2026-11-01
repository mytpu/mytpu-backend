import { OverviewView, SeriesPoint } from '../../features/mcp/overview/overview.model';
import { INITIAL_CATALOG } from './demo-fixtures-catalog';

export function getDemoOverview(hours: number): OverviewView {
  const is7d = hours === 168;
  const is30d = hours === 720;
  const mult = is30d ? 28 : is7d ? 6.5 : 1;

  const raspCalls = Math.round(1840 * mult);
  const docsCalls = Math.round(720 * mult);
  const totalCalls = raspCalls + docsCalls;
  const totalErrors = Math.round(28 * mult);
  const totalCacheHits = Math.round(960 * mult);

  const series = generateDemoSeries(hours, totalCalls, totalErrors);

  return {
    mode: 'normal',
    integrations: INITIAL_CATALOG.map((c) => c.integration),
    stats: {
      from: new Date(Date.now() - hours * 3600000).toISOString(),
      to: new Date().toISOString(),
      totals: {
        calls: totalCalls,
        errors: totalErrors,
        cacheHits: totalCacheHits,
      },
      tools: [
        {
          tool: 'rasp_find',
          calls: raspCalls,
          errors: Math.round(totalErrors * 0.4),
          cacheHits: Math.round(totalCacheHits * 0.7),
          avgMs: 42,
          p95Ms: 145,
        },
        {
          tool: 'docs_search',
          calls: docsCalls,
          errors: Math.round(totalErrors * 0.6),
          cacheHits: Math.round(totalCacheHits * 0.3),
          avgMs: 310,
          p95Ms: 620,
        },
      ],
      topClients: [
        {
          clientId: 'k-ai-assistant',
          owner: 'Мобильный ассистент Мой ТПУ',
          calls: Math.round(1120 * mult),
          errors: Math.round(12 * mult),
        },
        {
          clientId: 'k-dekanat-bot',
          owner: 'Деканат ИШИТР · бот расписания',
          calls: Math.round(540 * mult),
          errors: Math.round(4 * mult),
        },
        {
          clientId: 'k-lib-search',
          owner: 'НТБ ТПУ · поисковый агент',
          calls: Math.round(410 * mult),
          errors: Math.round(7 * mult),
        },
        {
          clientId: 'k-dorm-kiosk',
          owner: 'Общежитие №8 · инфомат',
          calls: Math.round(290 * mult),
          errors: Math.round(3 * mult),
        },
        {
          clientId: 'k-prof-petrov',
          owner: 'Петров В. М. · научная группа LLM',
          calls: Math.round(140 * mult),
          errors: Math.round(2 * mult),
        },
      ],
      series,
    },
  };
}

function generateDemoSeries(hours: number, totalCalls: number, totalErrors: number): SeriesPoint[] {
  const now = Date.now();
  const tomskOffsetMs = 7 * 3600000;

  if (hours <= 24) {
    const currentHourMs = Math.floor(now / 3600000) * 3600000;
    const weights: number[] = [];
    const dates: string[] = [];

    for (let i = 23; i >= 0; i--) {
      const atMs = currentHourMs - i * 3600000;
      dates.push(new Date(atMs).toISOString());
      const tomskHour = new Date(atMs + tomskOffsetMs).getUTCHours();
      let w: number;
      if (tomskHour < 6) {
        w = 0.12 + (tomskHour / 6) * 0.08;
      } else if (tomskHour < 9) {
        w = 0.25 + ((tomskHour - 6) / 3) * 0.55;
      } else if (tomskHour < 18) {
        w = 1.1 + 0.3 * Math.sin(((tomskHour - 9) / 9) * Math.PI);
      } else {
        w = 0.85 - ((tomskHour - 18) / 6) * 0.65;
      }
      weights.push(Math.max(0.1, w));
    }

    const weightSum = weights.reduce((a, b) => a + b, 0);
    return dates.map((at, idx) => {
      const calls = Math.max(1, Math.round((weights[idx] / weightSum) * totalCalls));
      const errors = Math.round((calls / totalCalls) * totalErrors);
      return { at, calls, errors };
    });
  }

  const points = hours <= 168 ? 7 : Math.ceil(hours / 24);
  const tomskNow = new Date(now + tomskOffsetMs);
  const tomskMidnightUtc =
    Date.UTC(tomskNow.getUTCFullYear(), tomskNow.getUTCMonth(), tomskNow.getUTCDate()) -
    tomskOffsetMs;

  const weights: number[] = [];
  const dates: string[] = [];

  for (let i = points - 1; i >= 0; i--) {
    const atMs = tomskMidnightUtc - i * 86400000;
    dates.push(new Date(atMs).toISOString());
    const dayOfWeek = new Date(atMs + tomskOffsetMs).getUTCDay();
    const isWeekend = dayOfWeek === 0 || dayOfWeek === 6;
    const baseW = isWeekend ? 0.25 : 1.2;
    const jitter = 0.9 + 0.2 * (((i * 3 + dayOfWeek) % 5) / 4);
    weights.push(baseW * jitter);
  }

  const weightSum = weights.reduce((a, b) => a + b, 0);
  return dates.map((at, idx) => {
    const calls = Math.max(5, Math.round((weights[idx] / weightSum) * totalCalls));
    const errors = Math.round((calls / totalCalls) * totalErrors);
    return { at, calls, errors };
  });
}
