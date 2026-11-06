import { ConnectorVersion } from '../../features/mcp/connectors/connectors.model';

export interface DemoConnectorRecord {
  id: string;
  publishedVersion: number | null;
  secretsSet: string[];
  secretsNeeded: string[];
  updatedAt: string;
  versions: ConnectorVersion[];
}

export const INITIAL_DEMO_CONNECTORS: DemoConnectorRecord[] = [
  {
    id: 'nager_holidays',
    publishedVersion: 1,
    secretsSet: [],
    secretsNeeded: [],
    updatedAt: '2026-10-30T10:00:00Z',
    versions: [
      {
        version: 2,
        createdAt: '2026-10-30T10:00:00Z',
        createdBy: 'admin',
        published: false,
        spec: {
          title: 'Праздники РФ',
          baseUrl: 'https://date.nager.at/api/v3',
          auth: {
            type: 'none',
          },
          scope: {
            name: 'holidays.read',
            title: 'Праздники РФ',
            description: 'Официальные праздничные дни в России',
          },
          tools: [
            {
              name: 'holidays_year',
              description: 'Список официальных праздничных дней в России за указанный год',
              path: '/PublicHolidays/{year}/RU',
              params: [
                {
                  name: 'year',
                  in: 'path',
                  type: 'integer',
                  required: true,
                  default: 2026,
                  description: 'Календарный год (например, 2026)',
                },
              ],
              result: {
                pointer: '',
                mode: 'list',
                fields: [
                  { name: 'date', pointer: '/date', label: 'Дата праздника' },
                  { name: 'localName', pointer: '/localName', label: 'Название праздника' },
                ],
                maxItems: 30,
              },
              summary: '{date}: {localName}',
              cacheTtlSeconds: 86400,
              timeoutMs: 5000,
              ratePerMinute: 60,
            },
          ],
        },
      },
      {
        version: 1,
        createdAt: '2026-10-25T12:00:00Z',
        createdBy: 'admin',
        published: true,
        spec: {
          title: 'Праздники РФ',
          baseUrl: 'https://date.nager.at/api/v3',
          auth: {
            type: 'none',
          },
          scope: {
            name: 'holidays.read',
            title: 'Праздники РФ',
            description: 'Официальные праздничные дни в России',
          },
          tools: [
            {
              name: 'holidays_year',
              description: 'Список официальных праздников РФ',
              path: '/PublicHolidays/{year}/RU',
              params: [
                {
                  name: 'year',
                  in: 'path',
                  type: 'integer',
                  required: true,
                  default: 2026,
                  description: 'Год',
                },
              ],
              result: {
                pointer: '',
                mode: 'list',
                fields: [
                  { name: 'date', pointer: '/date', label: 'Дата' },
                  { name: 'localName', pointer: '/localName', label: 'Название' },
                ],
                maxItems: 30,
              },
              summary: '{date}: {localName}',
              cacheTtlSeconds: 3600,
              timeoutMs: 5000,
              ratePerMinute: 30,
            },
          ],
        },
      },
    ],
  },
  {
    id: 'tpu_news',
    publishedVersion: null,
    secretsSet: [],
    secretsNeeded: ['api_key'],
    updatedAt: '2026-11-01T09:30:00Z',
    versions: [
      {
        version: 1,
        createdAt: '2026-11-01T09:30:00Z',
        createdBy: 'admin',
        published: false,
        spec: {
          title: 'Новости ТПУ',
          baseUrl: 'https://news.tpu.ru/api',
          auth: {
            type: 'header',
            name: 'X-Api-Key',
          },
          scope: {
            name: 'news.read',
            title: 'Новости ТПУ',
            description: 'Лента новостей и событий университета',
          },
          tools: [
            {
              name: 'news_latest',
              description: 'Последние опубликованные новости университета',
              path: '/v1/news/latest',
              params: [
                {
                  name: 'limit',
                  in: 'query',
                  type: 'integer',
                  required: false,
                  default: 10,
                  description: 'Количество новостей',
                },
              ],
              result: {
                pointer: '/items',
                mode: 'list',
                fields: [
                  { name: 'title', pointer: '/title', label: 'Заголовок' },
                  { name: 'date', pointer: '/publishedAt', label: 'Дата публикации' },
                ],
                maxItems: 10,
              },
              summary: '{title} ({date})',
              cacheTtlSeconds: 300,
              timeoutMs: 3000,
              ratePerMinute: 60,
            },
          ],
        },
      },
    ],
  },
];
