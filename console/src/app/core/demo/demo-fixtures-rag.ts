export interface DemoSource {
  id: number; kind: string; title: string; address: string; enabled: boolean;
  documents: number; chunks: number; fileSize: number | null; lastJob: string | null; createdAt: string;
}
export interface DemoDoc {
  id: number; sourceId: number; title: string; url: string; access: string;
  status: string; sections: number; chunks: number; updatedAt: string; indexedAt: string; redactions: number;
}
export interface DemoChunk { ord: number; heading: string; text: string; tokens: number; }
export interface DemoSection { id: string; ord: number; path: string; text: string; tokens: number; chunks: DemoChunk[]; }
export interface DemoJob {
  id: number; sourceId: number; kind: string; status: string; log: string;
  redactions: Record<string, number>; chunks: number | null; createdBy: string | null;
  createdAt: string; startedAt: string | null; finishedAt: string | null; title?: string;
}
export interface DemoEvalQuestion { id: number; question: string; expectedUrls: string[]; hasAnswer: boolean; note?: string; }
export interface DemoEvalRun { id: number; at: string; questions: number; hit3: number; hit5: number; falseFound: number; }
export interface DemoMiss { query: string; count: number; examples: string[]; lastAt: string; }

export const INITIAL_SOURCES: DemoSource[] = [
  { id: 1, kind: 'site', title: 'Сайт tpu.ru', address: 'https://tpu.ru', enabled: true, documents: 92, chunks: 1362, fileSize: null, lastJob: 'done', createdAt: '2026-09-01T08:00:00Z' },
  { id: 2, kind: 'base', title: 'Базы ТПУ', address: 'upload/tpu-db-dump.zip', enabled: true, documents: 32, chunks: 410, fileSize: 18450000, lastJob: 'done', createdAt: '2026-09-15T10:00:00Z' },
  { id: 3, kind: 'console', title: 'Загрузки из консоли', address: 'upload/polozhenie-stipendii-2026.pdf', enabled: true, documents: 2, chunks: 40, fileSize: 2450000, lastJob: 'done', createdAt: '2026-10-29T14:05:00Z' },
];

const BASE_DOC_SPECS = [
  { title: 'Положение о стипендиях 2026', url: 'upload:3/polozhenie-stipendii-2026.pdf', sId: 3, sec: 6, ch: 38, red: 4 },
  { title: 'Государственная академическая стипендия', url: 'https://tpu.ru/university/stipendii/academic', sId: 1, sec: 3, ch: 9, red: 0 },
  { title: 'Повышенная стипендия за научную деятельность', url: 'https://tpu.ru/university/stipendii/science', sId: 1, sec: 5, ch: 17, red: 2 },
  { title: 'Социальная стипендия: документы', url: 'https://tpu.ru/university/stipendii/social', sId: 1, sec: 2, ch: 6, red: 0, off: true },
  { title: 'Правила проживания в общежитиях ТПУ', url: 'upload/dorm-rules.docx', sId: 2, sec: 8, ch: 24, red: 3 },
  { title: 'Оплата проживания в общежитии и льготы', url: 'https://tpu.ru/campus/dorm/payment', sId: 1, sec: 3, ch: 8, red: 0 },
  { title: 'Переселение между общежитиями ТПУ', url: 'https://tpu.ru/campus/dorm/relocation', sId: 1, sec: 2, ch: 5, red: 1 },
  { title: 'Заселение первокурсников в общежития', url: 'https://tpu.ru/campus/dorm/freshmen', sId: 1, sec: 4, ch: 12, red: 0 },
  { title: 'Справка об обучении: заказ и получение', url: 'https://tpu.ru/studies/certificates/study', sId: 1, sec: 2, ch: 4, red: 0 },
  { title: 'Справка в военный комиссариат (форма 26)', url: 'https://tpu.ru/studies/certificates/military', sId: 1, sec: 3, ch: 7, red: 1 },
  { title: 'Справка-вызов на экзаменационную сессию', url: 'https://tpu.ru/studies/certificates/exam-call', sId: 1, sec: 2, ch: 5, red: 0 },
  { title: 'Справка о доходах и стипендии за период', url: 'https://tpu.ru/studies/certificates/income', sId: 1, sec: 2, ch: 4, red: 0 },
  { title: 'Порядок перехода с платного обучения на бюджет', url: 'https://tpu.ru/studies/transfer/budget', sId: 1, sec: 5, ch: 15, red: 2 },
  { title: 'Перевод из другого университета в ТПУ', url: 'https://tpu.ru/studies/transfer/other-uni', sId: 1, sec: 4, ch: 11, red: 1 },
  { title: 'Восстановление в число студентов ТПУ', url: 'https://tpu.ru/studies/transfer/reinstate', sId: 1, sec: 3, ch: 8, red: 0 },
  { title: 'Смена образовательной программы внутри ТПУ', url: 'https://tpu.ru/studies/transfer/internal', sId: 1, sec: 3, ch: 7, red: 0 },
  { title: 'Миграционный учёт иностранных студентов', url: 'https://tpu.ru/international/migration', sId: 1, sec: 4, ch: 14, red: 3 },
  { title: 'Продление учебной визы для студентов ТПУ', url: 'https://tpu.ru/international/visa', sId: 1, sec: 3, ch: 9, red: 1 },
  { title: 'Медицинское страхование иностранных граждан', url: 'https://tpu.ru/international/insurance', sId: 1, sec: 2, ch: 6, red: 0 },
  { title: 'Признание иностранного образования (нострификация)', url: 'https://tpu.ru/international/nostrification', sId: 1, sec: 3, ch: 8, red: 0 },
  { title: 'Правила внутреннего распорядка обучающихся', url: 'upload:3/pravila-rasporyadka.pdf', sId: 3, sec: 4, ch: 14, red: 2 },
];

export function buildInitialDocs(): DemoDoc[] {
  const docs: DemoDoc[] = [];
  BASE_DOC_SPECS.forEach((b, i) => {
    docs.push({
      id: i + 1, sourceId: b.sId, title: b.title, url: b.url, access: 'public',
      status: (b as any).off ? 'disabled' : 'active', sections: b.sec, chunks: b.ch,
      updatedAt: '2026-10-29T14:05:00Z', indexedAt: '2026-10-29T14:05:00Z', redactions: b.red,
    });
  });
  const titles = [
    'Стипендия Правительства РФ: конкурс', 'Стипендия Президента РФ для аспирантов', 'Именные стипендии предприятий-партнеров',
    'Оформление банковской карты для стипендии', 'Материальная помощь студентам ТПУ', 'Памятка проживающему в кампусе ТПУ',
    'Порядок временного выезда из общежития', 'Смена комнаты в общежитии: заявление', 'Вход в общежитие по электронным пропускам',
    'Порядок заказа дубликата студенческого билета', 'Заказ справки о периоде обучения (академсправка)', 'Заказ копий документов из личного дела',
    'Перевод на индивидуальный учебный план', 'Предоставление академического отпуска', 'Выход из академического отпуска',
    'Памятка по прибытию в РФ для первокурсников', 'Постановка на миграционный учёт: документы', 'Дактилоскопия и медосвидетельствование иностранцев',
  ];
  for (let i = BASE_DOC_SPECS.length + 1; i <= 126; i++) {
    const t = titles[(i - 1) % titles.length] + ' · ч. ' + (Math.floor(i / titles.length) + 1);
    const sId = i % 4 === 0 ? 2 : 1;
    docs.push({
      id: i, sourceId: sId, title: t, url: `https://tpu.ru/page/${i}`, access: 'public',
      status: i % 15 === 0 ? 'disabled' : 'active', sections: 2 + (i % 4), chunks: 5 + (i % 12),
      updatedAt: '2026-10-28T09:00:00Z', indexedAt: '2026-10-28T09:00:00Z', redactions: i % 5 === 0 ? 2 : 0,
    });
  }
  return docs;
}

export const INITIAL_DOC_SECTIONS: DemoSection[] = [
  {
    id: 's-1', ord: 1, path: '1. Общие положения', text: 'Настоящее Положение определяет порядок назначения и выплаты стипендий студентам и аспирантам ТПУ.', tokens: 180,
    chunks: [{ ord: 1, heading: '1. Общие положения', text: 'Настоящее Положение определяет порядок назначения и выплаты государственных стипендий.', tokens: 180 }],
  },
  {
    id: 's-2', ord: 2, path: '2. Виды стипендий', text: 'В университете назначаются государственные академические, социальные и именные стипендии.', tokens: 320,
    chunks: [{ ord: 2, heading: '2.1 Академическая и социальная', text: 'Стипендии подразделяются на академические, повышенные и социальные выплаты.', tokens: 320 }],
  },
  {
    id: 's-3', ord: 3, path: '3. Академическая стипендия', text: 'Академическая стипендия назначается студентам без академических задолженностей.', tokens: 842,
    chunks: [
      { ord: 14, heading: '3.1 Условия назначения', text: 'Государственная академическая стипендия назначается студентам очной формы обучения за счёт бюджета без задолженностей и с оценками не ниже «хорошо»…', tokens: 312 },
      { ord: 15, heading: '3.2 Ответственные лица', text: 'Вопросы по назначению — в учебный офис школы: ответственный [ФИО], моб. [ТЕЛЕФОН], каб. 215.', tokens: 286 },
      { ord: 16, heading: '3.3 Пересдача экзаменов', text: 'При пересдаче экзамена стипендия не назначается до следующей промежуточной аттестации, кроме случаев уважительной причины из п. 3.7.', tokens: 244 },
    ],
  },
  {
    id: 's-4', ord: 4, path: '4. Повышенная стипендия', text: 'Назначается за достижения в учебной, научно-исследовательской и спортивной деятельности.', tokens: 620,
    chunks: [{ ord: 21, heading: '4.1 Научные достижения', text: 'Повышенная стипендия за научную деятельность назначается при наличии публикаций и патентов.', tokens: 310 }],
  },
  {
    id: 's-5', ord: 5, path: '5. Социальная стипендия', text: 'Назначается студентам льготных категорий при представлении подтверждающих документов.', tokens: 410,
    chunks: [{ ord: 28, heading: '5.1 Категории получателей', text: 'Государственная социальная стипендия назначается студентам, получившим государственную социальную помощь.', tokens: 410 }],
  },
  {
    id: 's-6', ord: 6, path: '6. Порядок выплаты', text: 'Выплата стипендий производится ежемесячно на банковские карты платежной системы МИР.', tokens: 290,
    chunks: [{ ord: 35, heading: '6.1 Сроки выплат', text: 'Стипендия выплачивается 25 числа каждого месяца за текущий месяц.', tokens: 290 }],
  },
];

export const INITIAL_JOBS: DemoJob[] = [
  {
    id: 104, sourceId: 3, kind: 'upload', status: 'done', title: 'Приказ о расписании сессии.pdf',
    log: 'разбор PDF · 31 стр.\nRedactor · 12 замен (ФИО 9, моб. 3)\nкуски · 960\nвекторы · 960 из 960 готовы',
    redactions: { ФИО: 9, ТЕЛЕФОН: 3 }, chunks: 960, createdBy: 'admin',
    createdAt: '2026-10-31T14:10:00Z', startedAt: '2026-10-31T14:10:02Z', finishedAt: '2026-10-31T14:11:45Z',
  },
  {
    id: 103, sourceId: 3, kind: 'upload', status: 'done', title: 'Положение о стипендиях 2026.pdf',
    log: 'разбор PDF · 31 стр.\nRedactor · 4 замены (ФИО 3, моб. 1)\nкуски · 38\nвекторы · 38 кусков готовы',
    redactions: { ФИО: 3, ТЕЛЕФОН: 1 }, chunks: 38, createdBy: 'admin',
    createdAt: '2026-10-29T14:03:00Z', startedAt: '2026-10-29T14:03:02Z', finishedAt: '2026-10-29T14:05:00Z',
  },
  {
    id: 102, sourceId: 2, kind: 'reindex', status: 'done', title: 'Правила общежития.docx',
    log: 'разбор DOCX · 18 стр.\nRedactor · 3 замены\nкуски · 24\nвекторы · 24 куска готовы',
    redactions: { ФИО: 2, ТЕЛЕФОН: 1 }, chunks: 24, createdBy: 'admin',
    createdAt: '2026-10-28T03:10:00Z', startedAt: '2026-10-28T03:10:02Z', finishedAt: '2026-10-28T03:11:15Z',
  },
  {
    id: 101, sourceId: 3, kind: 'upload', status: 'failed', title: 'scan_zayavlenie.pdf',
    log: 'разбор PDF · 1 стр.\nошибка: в PDF нет текста — это скан, нужен OCR',
    redactions: {}, chunks: null, createdBy: 'admin',
    createdAt: '2026-10-28T13:38:00Z', startedAt: '2026-10-28T13:38:02Z', finishedAt: '2026-10-28T13:38:15Z',
  },
];

export const INITIAL_EVAL_QUESTIONS: DemoEvalQuestion[] = [
  { id: 1, question: 'Сколько стипендия при пересдаче?', expectedUrls: ['upload:3/polozhenie-stipendii-2026.pdf', 'https://tpu.ru/university/stipendii/academic'], hasAnswer: true, note: 'добавлен 21.10' },
  { id: 2, question: 'Где справка для военкомата?', expectedUrls: ['https://tpu.ru/studies/certificates/military'], hasAnswer: true, note: 'добавлен 07.10' },
  { id: 3, question: 'Как перевестись на бюджет?', expectedUrls: ['https://tpu.ru/studies/transfer/budget'], hasAnswer: true, note: 'добавлен 07.10' },
  { id: 4, question: 'Библиотека в субботу', expectedUrls: [], hasAnswer: false, note: 'добавлен 14.10' },
  { id: 5, question: 'Оплата общежития через Сбербанк', expectedUrls: ['https://tpu.ru/campus/dorm/payment'], hasAnswer: true, note: 'добавлен 14.10' },
  { id: 6, question: 'Продление учебной визы для студентов', expectedUrls: ['https://tpu.ru/international/visa'], hasAnswer: true, note: 'добавлен 18.10' },
  { id: 7, question: 'Повышенная стипендия за статьи Scopus', expectedUrls: ['https://tpu.ru/university/stipendii/science'], hasAnswer: true, note: 'добавлен 19.10' },
  { id: 8, question: 'Справка-вызов на экзаменационную сессию', expectedUrls: ['https://tpu.ru/studies/certificates/exam-call'], hasAnswer: true, note: 'добавлен 20.10' },
];

export const INITIAL_EVAL_RUNS: DemoEvalRun[] = [
  { id: 4, at: '2026-10-28T14:30:00Z', questions: 40, hit3: 0.78, hit5: 0.86, falseFound: 3 },
  { id: 3, at: '2026-10-21T10:00:00Z', questions: 40, hit3: 0.76, hit5: 0.82, falseFound: 5 },
  { id: 2, at: '2026-10-14T10:00:00Z', questions: 40, hit3: 0.74, hit5: 0.76, falseFound: 6 },
  { id: 1, at: '2026-10-07T10:00:00Z', questions: 40, hit3: 0.71, hit5: 0.71, falseFound: 7 },
];

export const INITIAL_MISSES: DemoMiss[] = [
  { query: 'Перевод с платного на бюджет', count: 23, examples: ['как перевестись на бюджет', 'перевод на бюджетное место', 'освободилось бюджетное место'], lastAt: '2026-10-31T11:20:00Z' },
  { query: 'Стипендия за научную работу', count: 14, examples: ['повышенная стипендия за статьи', 'сколько баллов за публикацию'], lastAt: '2026-10-30T16:45:00Z' },
  { query: 'Справка для военкомата', count: 9, examples: ['справка в военкомат где взять', 'форма 26'], lastAt: '2026-10-29T09:15:00Z' },
  { query: 'Расписание пересдач', count: 7, examples: ['когда пересдача по матану', 'график ликвидации задолженностей'], lastAt: '2026-10-28T18:00:00Z' },
  { query: 'Парковка у 10 корпуса', count: 5, examples: ['пропуск на парковку', 'где припарковать машину у 10к'], lastAt: '2026-10-27T12:00:00Z' },
];
