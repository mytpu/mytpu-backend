import { HttpRequest } from '@angular/common/http';
import { RouteResult } from './demo-routes';
import {
  DemoDoc, DemoEvalQuestion, DemoEvalRun, DemoJob, DemoMiss, DemoSection, DemoSource,
  INITIAL_DOC_SECTIONS, INITIAL_EVAL_QUESTIONS, INITIAL_EVAL_RUNS, INITIAL_JOBS,
  INITIAL_MISSES, INITIAL_SOURCES, buildInitialDocs,
} from './demo-fixtures-rag';

export class DemoRagRoutes {
  private sources: DemoSource[] = INITIAL_SOURCES.map((s) => ({ ...s }));
  private docs: DemoDoc[] = buildInitialDocs();
  private jobs: DemoJob[] = INITIAL_JOBS.map((j) => ({ ...j }));
  private questions: DemoEvalQuestion[] = INITIAL_EVAL_QUESTIONS.map((q) => ({ ...q }));
  private runs: DemoEvalRun[] = INITIAL_EVAL_RUNS.map((r) => ({ ...r }));
  private misses: DemoMiss[] = INITIAL_MISSES.map((m) => ({ ...m }));
  private nextId = 200;

  handle(req: HttpRequest<unknown>, path: string, method: string, getParam: (k: string) => string | null): RouteResult | null {
    if (!path.startsWith('/admin/api/rag')) return null;
    const sub = path.substring('/admin/api/rag'.length);

    if (sub === '/overview' && method === 'GET') return this.overview();
    if (sub === '/sources' && method === 'GET') return { status: 200, body: this.sources };
    if (sub === '/sources/files' && method === 'POST') return this.uploadFile(req);
    if (sub === '/sources/page' && method === 'POST') return this.addPage(req);
    const mReindex = sub.match(/^\/sources\/(\d+)\/reindex$/);
    if (mReindex && method === 'POST') return this.reindexSource(Number(mReindex[1]));
    const mSourceState = sub.match(/^\/sources\/(\d+)\/state$/);
    if (mSourceState && method === 'PUT') return this.updateSourceState(Number(mSourceState[1]), (req.body as any)?.enabled);
    const mSourceDel = sub.match(/^\/sources\/(\d+)$/);
    if (mSourceDel && method === 'DELETE') return this.deleteSource(Number(mSourceDel[1]));

    if (sub === '/jobs' && method === 'GET') { this.tickJobs(); return { status: 200, body: this.jobs }; }
    const mJob = sub.match(/^\/jobs\/(\d+)$/);
    if (mJob && method === 'GET') {
      this.tickJobs();
      const j = this.jobs.find((x) => x.id === Number(mJob[1]));
      return j ? { status: 200, body: j } : { status: 404, body: { error: 'job_not_found' } };
    }

    if (sub === '/documents' && method === 'GET') return this.listDocs(getParam);
    const mDoc = sub.match(/^\/documents\/(\d+)$/);
    if (mDoc) {
      const id = Number(mDoc[1]);
      if (method === 'GET') return this.getDoc(id);
      if (method === 'PUT') return this.updateDoc(id, req.body as any);
      if (method === 'DELETE') return this.deleteDoc(id);
    }

    if (sub === '/playground' && method === 'POST') return this.playground(req.body as any);
    if (sub === '/eval/runs') {
      if (method === 'GET') return { status: 200, body: this.runs };
      if (method === 'POST') return this.runEval();
    }
    if (sub === '/eval/questions') {
      if (method === 'GET') return { status: 200, body: this.questions };
      if (method === 'POST') return this.addQuestion(req.body as any);
    }
    const mQ = sub.match(/^\/eval\/questions\/(\d+)$/);
    if (mQ) {
      const qId = Number(mQ[1]);
      if (method === 'PUT') return this.updateQuestion(qId, req.body as any);
      if (method === 'DELETE') { this.questions = this.questions.filter((x) => x.id !== qId); return { status: 200, body: { id: qId, deleted: true } }; }
    }
    if (sub === '/eval/questions/import' && method === 'POST') return { status: 200, body: { added: 8, total: this.questions.length } };
    if (sub === '/misses' && method === 'GET') return { status: 200, body: this.misses };

    return { status: 404, body: { error: 'not_found' } };
  }

  private overview(): RouteResult {
    const totalDocs = this.docs.length;
    const docsOff = this.docs.filter((d) => d.status !== 'active').length;
    const totalChunks = this.docs.reduce((acc, d) => acc + d.chunks, 0);
    const lastRun = this.runs[0] || null;
    return {
      status: 200,
      body: {
        sources: this.sources.length, documents: totalDocs, documentsOff: docsOff, chunks: totalChunks,
        embeddings: totalChunks, lastIndexed: '2026-10-29T14:05:00Z', misses7d: 58, modelVersion: 'multilingual-e5-small · v2',
        index: { name: 'embeddings_hnsw_idx', bytes: 43008000 },
        lastEval: lastRun ? { at: lastRun.at, hit3: lastRun.hit3, hit5: lastRun.hit5, falseFound: lastRun.falseFound, questions: lastRun.questions } : null,
      },
    };
  }

  private tickJobs(): void {
    const now = Date.now();
    for (const j of this.jobs) {
      if (j.status === 'running') {
        const elapsed = (now - new Date(j.createdAt).getTime()) / 1000;
        if (elapsed > 4) {
          j.status = 'done'; j.finishedAt = new Date().toISOString(); j.chunks = 38;
          j.log = j.log + '\nкуски · 38\nвекторы · 38 кусков готовы';
        }
      }
    }
  }

  private uploadFile(req: HttpRequest<unknown>): RouteResult {
    const id = ++this.nextId;
    const jId = ++this.nextId;
    let title = 'Загруженный документ';
    if (req.body instanceof FormData) {
      const file = req.body.get('file') as File | null;
      title = (req.body.get('title') as string) || file?.name || title;
    }
    this.sources.unshift({
      id, kind: 'console', title, address: `upload:${id}/${title}`, enabled: true,
      documents: 1, chunks: 38, fileSize: 1048576, lastJob: 'running', createdAt: new Date().toISOString(),
    });
    this.jobs.unshift({
      id: jId, sourceId: id, kind: 'upload', status: 'running', title,
      log: 'разбор файла · 18 стр.\nRedactor · 4 замены (ФИО 3, моб. 1)', redactions: { ФИО: 3, ТЕЛЕФОН: 1 },
      chunks: null, createdBy: 'admin', createdAt: new Date().toISOString(), startedAt: new Date().toISOString(), finishedAt: null,
    });
    return { status: 202, body: { sourceId: id, jobId: jId } };
  }

  private addPage(req: HttpRequest<unknown>): RouteResult {
    const b = req.body as any;
    const url = b?.url || 'https://tpu.ru';
    const title = b?.title || url;
    const id = ++this.nextId;
    const jId = ++this.nextId;
    this.sources.unshift({
      id, kind: 'site', title, address: url, enabled: true,
      documents: 1, chunks: 12, fileSize: 35000, lastJob: 'running', createdAt: new Date().toISOString(),
    });
    this.jobs.unshift({
      id: jId, sourceId: id, kind: 'site', status: 'running', title,
      log: 'скачивание страницы шлюзом\nразбор HTML · Redactor', redactions: {}, chunks: null, createdBy: 'admin',
      createdAt: new Date().toISOString(), startedAt: new Date().toISOString(), finishedAt: null,
    });
    return { status: 202, body: { sourceId: id, jobId: jId } };
  }

  private reindexSource(id: number): RouteResult {
    const s = this.sources.find((x) => x.id === id);
    if (!s) return { status: 404, body: { error: 'source_not_found' } };
    const jId = ++this.nextId;
    this.jobs.unshift({
      id: jId, sourceId: id, kind: 'reindex', status: 'running', title: s.title,
      log: 'переиндексация источника\nразбор и нарезка кусков', redactions: {}, chunks: null, createdBy: 'admin',
      createdAt: new Date().toISOString(), startedAt: new Date().toISOString(), finishedAt: null,
    });
    return { status: 202, body: { sourceId: id, jobId: jId } };
  }

  private updateSourceState(id: number, enabled: boolean): RouteResult {
    const s = this.sources.find((x) => x.id === id);
    if (!s) return { status: 404, body: { error: 'source_not_found' } };
    s.enabled = enabled;
    this.docs.filter((d) => d.sourceId === id).forEach((d) => (d.status = enabled ? 'active' : 'disabled'));
    return { status: 200, body: { id, enabled } };
  }

  private deleteSource(id: number): RouteResult {
    this.sources = this.sources.filter((x) => x.id !== id);
    this.docs = this.docs.filter((x) => x.sourceId !== id);
    return { status: 200, body: { id, deleted: true } };
  }

  private listDocs(getParam: (k: string) => string | null): RouteResult {
    const q = getParam('q')?.toLowerCase()?.trim();
    const source = getParam('source') ? Number(getParam('source')) : null;
    const status = getParam('status');
    let res = this.docs;
    if (q) res = res.filter((d) => d.title.toLowerCase().includes(q) || d.url.toLowerCase().includes(q));
    if (source) res = res.filter((d) => d.sourceId === source);
    if (status) res = res.filter((d) => d.status === status);
    return { status: 200, body: res };
  }

  private getDoc(id: number): RouteResult {
    const doc = this.docs.find((d) => d.id === id);
    if (!doc) return { status: 404, body: { error: 'document_not_found' } };
    const sections: DemoSection[] = id === 1 ? INITIAL_DOC_SECTIONS : [
      { id: 's-1', ord: 1, path: 'Раздел 1. Общие сведения', text: doc.title, tokens: 210, chunks: [{ ord: 1, heading: 'Введение', text: `Основной текст документа «${doc.title}». Настоящие правила и регламенты действуют для студентов ТПУ. Ответственный: [ФИО], тел. [ТЕЛЕФОН].`, tokens: 210 }] },
      { id: 's-2', ord: 2, path: 'Раздел 2. Порядок оформления', text: 'Порядок подачи заявлений и сроки рассмотрения.', tokens: 195, chunks: [{ ord: 2, heading: 'Сроки и форма', text: 'Заявления принимаются в течение семестра. Консультации в каб. 112.', tokens: 195 }] },
    ];
    return { status: 200, body: { document: doc, sections } };
  }

  private updateDoc(id: number, b: any): RouteResult {
    const doc = this.docs.find((d) => d.id === id);
    if (!doc) return { status: 404, body: { error: 'document_not_found' } };
    if (b?.status) doc.status = b.status;
    if (b?.access) doc.access = b.access;
    return { status: 200, body: doc };
  }

  private deleteDoc(id: number): RouteResult {
    this.docs = this.docs.filter((d) => d.id !== id);
    return { status: 200, body: { id, deleted: true } };
  }

  private playground(b: any): RouteResult {
    const q = (b?.query || '').trim().toLowerCase();

    // Запросы из «Ненайденных» всегда отвечают правдоподобным кандидатом ниже порога (found: false)
    const isMissQuery = this.misses.some(
      (m) => m.query.toLowerCase() === q || m.examples.some((ex) => ex.toLowerCase() === q),
    );

    const isExplicitFound = (q.includes('стипенд') || q.includes('пересда') || q.includes('экзамен'))
      && !isMissQuery
      && !q.includes('научн')
      && !q.includes('платн')
      && !q.includes('бюджет');

    if (!isExplicitFound) {
      let title = 'Единое окно поддержки и часто задаваемые вопросы';
      let path = 'help.tpu.ru · Справка · #1';
      let sim = 0.25;

      if (q.includes('бюджет') || q.includes('платн')) {
        title = 'Порядок перехода с платного обучения на бесплатное';
        path = 'tpu.ru · Учебный офис · #12';
        sim = 0.27;
      } else if (q.includes('научн') || q.includes('стать')) {
        title = 'Учёт публикаций и рейтинг научной активности';
        path = 'devdocs.tpu.ru · Наука · #6';
        sim = 0.28;
      } else if (q.includes('военкомат') || q.includes('форм') || q.includes('справк')) {
        title = 'Воинский учёт и заказ справок для военного комиссариата';
        path = 'lk.tpu.ru · Документы · #3';
        sim = 0.29;
      } else if (q.includes('расписан') || q.includes('пересдач')) {
        title = 'График ликвидации задолженностей и повторной аттестации';
        path = 'rasp.tpu.ru · Расписание · #8';
        sim = 0.27;
      } else if (q.includes('парковк') || q.includes('машин') || q.includes('корпус')) {
        title = 'Порядок проезда и парковки на территории кампуса';
        path = 'help.tpu.ru · Пропуска · #2';
        sim = 0.26;
      }

      const candidates = [
        { place: 1, sectionId: 's-1', chunkId: 101, title, path, similarity: sim, inText: false, rrf: 0.018 },
        { place: 2, sectionId: 's-2', chunkId: 102, title: 'Правила внутреннего распорядка обучающихся ТПУ', path: 'tpu.ru · Регламенты · #5', similarity: 0.21, inText: false, rrf: 0.012 },
      ];

      return {
        status: 200,
        body: {
          found: false,
          reason: 'below_soft_threshold',
          bestSimilarity: sim,
          margin: 0.06,
          thresholds: { minSimilarity: 0.35, softSimilarity: 0.32, minMargin: 0.1 },
          candidates,
        },
      };
    }

    const candidates = [
      { place: 1, sectionId: 's-3', chunkId: 16, title: 'При пересдаче экзамена стипендия не назначается…', path: 'tpu.ru · Положение о стипендиях · #16', similarity: 0.81, inText: true, rrf: 0.0328 },
      { place: 2, sectionId: 's-2', chunkId: 3, title: 'Государственная академическая стипендия назначается…', path: 'lk.tpu.ru · Стипендии · #3', similarity: 0.74, inText: true, rrf: 0.0315 },
      { place: 3, sectionId: 's-1', chunkId: 2, title: 'Ликвидация академической задолженности: сроки и порядок', path: 'rasp.tpu.ru · Пересдачи · #2', similarity: 0.61, inText: true, rrf: 0.031 },
      { place: 4, sectionId: 's-4', chunkId: 4, title: 'Повышенная стипендия за научную деятельность: условия…', path: 'devdocs.tpu.ru · Исследования · #4', similarity: 0.58, inText: false, rrf: 0.029 },
      { place: 5, sectionId: 's-5', chunkId: 7, title: 'Ниже порога · материальная помощь для студентов', path: 'help.tpu.ru · Поддержка · #7', similarity: 0.29, inText: false, rrf: 0.0201 },
    ];
    return {
      status: 200,
      body: {
        found: true,
        reason: 'found',
        bestSimilarity: 0.81,
        margin: 0.52,
        thresholds: { minSimilarity: 0.35, softSimilarity: 0.32, minMargin: 0.1 },
        candidates,
      },
    };
  }

  private runEval(): RouteResult {
    const id = this.runs.length + 1;
    const r: DemoEvalRun = { id, at: new Date().toISOString(), questions: this.questions.length, hit3: 0.79, hit5: 0.87, falseFound: 2 };
    this.runs.unshift(r);
    return { status: 200, body: { id, report: { answerable: this.questions.length - 1, hitAt3: 0.79, hitAt5: 0.87, unanswerable: 1, falseFound: 0.05, p95Millis: 78, misses: [], answeredSimilarity: [0.81, 0.74], unansweredSimilarity: [0.25], traces: [] } } };
  }

  private addQuestion(b: any): RouteResult {
    const id = ++this.nextId;
    const q: DemoEvalQuestion = { id, question: b?.question || '', expectedUrls: b?.expectedUrls || [], hasAnswer: b?.hasAnswer !== false, note: 'добавлен вручную' };
    this.questions.push(q);
    return { status: 201, body: q };
  }

  private updateQuestion(id: number, b: any): RouteResult {
    const q = this.questions.find((x) => x.id === id);
    if (!q) return { status: 404, body: { error: 'question_not_found' } };
    if (b?.question) q.question = b.question;
    if (b?.expectedUrls) q.expectedUrls = b.expectedUrls;
    if (b?.hasAnswer !== undefined) q.hasAnswer = b.hasAnswer;
    return { status: 200, body: q };
  }
}
