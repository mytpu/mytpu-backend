import { IntegrationCatalogView } from '../../features/mcp/key-wizard/key-wizard.model';

export const INITIAL_CATALOG: IntegrationCatalogView[] = [
  {
    integration: {
      id: 'rasp',
      title: 'Расписание ТПУ',
      scopes: [
        {
          name: 'rasp.read',
          title: 'Расписание (чтение)',
          description: 'Поиск занятий, сессий, расписания аудиторий и преподавателей',
        },
      ],
      enabled: true,
      health: { status: 'UP' },
      missingSecrets: [],
      tools: ['rasp_find'],
    },
    tools: [
      {
        name: 'rasp_find',
        scope: 'rasp.read',
        kind: 'read',
        sensitivity: 'public',
        description: 'Поиск расписания пар и сессий по группам и преподавателям',
        enabled: true,
        cacheTtlSeconds: 300,
        timeoutMs: 3000,
        ratePerMinute: 60,
      },
    ],
  },
  {
    integration: {
      id: 'docs',
      title: 'База документов и регламентов ТПУ',
      scopes: [
        {
          name: 'docs.read',
          title: 'Документы ТПУ (чтение)',
          description: 'Семантический поиск по нормативным документам и регламентам ТПУ',
        },
      ],
      enabled: true,
      health: { status: 'UP' },
      missingSecrets: [],
      tools: ['docs_search'],
    },
    tools: [
      {
        name: 'docs_search',
        scope: 'docs.read',
        kind: 'read',
        sensitivity: 'public',
        description: 'Гибридный семантический поиск по базе знаний ТПУ',
        enabled: true,
        cacheTtlSeconds: 120,
        timeoutMs: 4000,
        ratePerMinute: 40,
      },
    ],
  },
];
