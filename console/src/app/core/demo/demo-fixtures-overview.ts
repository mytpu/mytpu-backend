import { OverviewView } from '../../features/mcp/overview/overview.model';
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
    },
  };
}
