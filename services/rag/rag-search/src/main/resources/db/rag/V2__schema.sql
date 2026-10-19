-- Таблицы документа 19. Куски и векторы пересобираются загрузкой; ценные данные — sources, glossary, query_log, eval_questions.

CREATE TABLE sources (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind        text        NOT NULL CHECK (kind IN ('dataset', 'site', 'file', 'naumen', 'bookstack')),
    title       text        NOT NULL,
    address     text        NOT NULL,
    selector    text,
    enabled     boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (kind, address)
);

CREATE TABLE documents (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id    bigint      NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    url          text        NOT NULL UNIQUE,
    title        text        NOT NULL,
    access       text        NOT NULL DEFAULT 'public' CHECK (access IN ('public', 'students', 'staff')),
    audience     text,
    content_hash text        NOT NULL,
    etag         text,
    status       text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled', 'outdated')),
    updated_at   timestamptz NOT NULL,
    indexed_at   timestamptz NOT NULL DEFAULT now()
);

-- id = хэш(url + путь заголовков): стабилен между загрузками, на него ссылается набор вопросов
CREATE TABLE sections (
    id          text   PRIMARY KEY,
    document_id bigint NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    ord         int    NOT NULL,
    path        text   NOT NULL,
    text        text   NOT NULL,
    tokens      int    NOT NULL
);
CREATE INDEX sections_document ON sections (document_id);

-- Единица поиска: ~300 токенов с контекстным заголовком «Документ › Раздел»
CREATE TABLE chunks (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    section_id text NOT NULL REFERENCES sections (id) ON DELETE CASCADE,
    ord        int  NOT NULL,
    heading    text NOT NULL,
    text       text NOT NULL,
    tokens     int  NOT NULL,
    tsv        tsvector GENERATED ALWAYS AS (
                   setweight(to_tsvector('russian'::regconfig, heading), 'A') ||
                   setweight(to_tsvector('russian'::regconfig, text), 'B')
               ) STORED,
    UNIQUE (section_id, ord)
);
CREATE INDEX chunks_tsv ON chunks USING gin (tsv);

-- Вектор без размерности: HNSW-индекс создаётся на каждую версию модели с приведением к её размерности
CREATE TABLE embeddings (
    chunk_id      bigint         NOT NULL REFERENCES chunks (id) ON DELETE CASCADE,
    model_version text           NOT NULL,
    embedding     public.halfvec NOT NULL,
    PRIMARY KEY (chunk_id, model_version)
);

CREATE TABLE glossary (
    term         text   PRIMARY KEY,
    replacements text[] NOT NULL
);

CREATE TABLE lexicon (
    word text PRIMARY KEY,
    freq int  NOT NULL
);

-- Текст запроса — уже после вырезания ПД; хранится 30 дней (docs/SECURITY.md, раздел 15)
CREATE TABLE query_log (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at             timestamptz NOT NULL DEFAULT now(),
    query          text        NOT NULL,
    found          boolean     NOT NULL,
    chunk_ids      bigint[]    NOT NULL DEFAULT '{}',
    top_score      real,
    rating         smallint,
    ticket_created boolean     NOT NULL DEFAULT false
);
CREATE INDEX query_log_at ON query_log (at);

CREATE TABLE eval_questions (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question             text    NOT NULL UNIQUE,
    expected_section_ids text[]  NOT NULL DEFAULT '{}',
    has_answer           boolean NOT NULL,
    note                 text
);
