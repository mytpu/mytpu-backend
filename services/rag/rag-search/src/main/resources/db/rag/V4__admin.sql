-- Консоль rag (specs/console-and-connectors): загруженные файлы, задачи загрузки, прогоны замера.

-- Файл из консоли: хранится на томе rag-files (путь — относительно mytpu.rag.files-dir), источник kind = 'file' или 'site'
CREATE TABLE files (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id    bigint      NOT NULL UNIQUE REFERENCES sources (id) ON DELETE CASCADE,
    name         text        NOT NULL,
    size         bigint      NOT NULL,
    sha256       text        NOT NULL,
    stored_path  text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- Одна задача за раз (фоновый исполнитель rag); log — короткий, без текста документа
CREATE TABLE ingest_jobs (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id   bigint      NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    kind        text        NOT NULL CHECK (kind IN ('upload', 'reindex')),
    status      text        NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'done', 'failed')),
    log         text        NOT NULL DEFAULT '',
    redactions  jsonb       NOT NULL DEFAULT '{}',
    chunks      int,
    created_by  text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    started_at  timestamptz,
    finished_at timestamptz
);
CREATE INDEX ingest_jobs_status ON ingest_jobs (status, id);

CREATE TABLE eval_runs (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at          timestamptz NOT NULL DEFAULT now(),
    questions   int         NOT NULL,
    hit3        real        NOT NULL,
    hit5        real        NOT NULL,
    false_found int         NOT NULL,
    report      jsonb       NOT NULL
);

-- Поиск ненайденного за 30 дней
CREATE INDEX query_log_found_at ON query_log (found, at);
