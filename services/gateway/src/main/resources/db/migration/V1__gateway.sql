-- Схема gateway: режим шлюза, выключенные инструменты и интеграции, мастер-ключи клиентов, журнал вызовов.

-- Режим шлюза (документ 20): normal / read_only / internal_only. Одна строка.
CREATE TABLE mode (
    id         smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    value      text        NOT NULL DEFAULT 'normal' CHECK (value IN ('normal', 'read_only', 'internal_only')),
    reason     text,
    updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO mode DEFAULT VALUES;

-- Строки нет — инструмент включён; администратор выключает инструмент целиком
CREATE TABLE tool_state (
    name       text        PRIMARY KEY,
    enabled    boolean     NOT NULL DEFAULT true,
    reason     text,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE integration_state (
    id         text        PRIMARY KEY,
    enabled    boolean     NOT NULL DEFAULT true,
    reason     text,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Мастер-ключ клиента: сам ключ не хранится, только SHA-256 и первые символы, чтобы узнать его в консоли
CREATE TABLE api_clients (
    id           text        PRIMARY KEY,
    owner        text        NOT NULL,
    key_prefix   text        NOT NULL,
    key_hash     bytea       NOT NULL UNIQUE,
    scopes       text[]      NOT NULL DEFAULT '{}',
    roles        text[]      NOT NULL DEFAULT '{}',
    denied_tools text[]      NOT NULL DEFAULT '{}',
    rate_limits  jsonb       NOT NULL DEFAULT '{}',
    read_only    boolean     NOT NULL DEFAULT true,
    enabled      boolean     NOT NULL DEFAULT true,
    expires_at   timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz
);

-- Журнал вызовов: аргументы — только хэш (docs/SECURITY.md, разделы 9–10); хранится 180 дней
CREATE TABLE audit (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at          timestamptz NOT NULL DEFAULT now(),
    request_id  text        NOT NULL,
    user_id     text,
    client_id   text        NOT NULL,
    channel     text        NOT NULL,
    tool        text        NOT NULL,
    args_hash   text        NOT NULL,
    outcome     text        NOT NULL,
    error_code  text,
    cache_hit   boolean     NOT NULL DEFAULT false,
    duration_ms int         NOT NULL
);
CREATE INDEX audit_at ON audit (at);
CREATE INDEX audit_client_at ON audit (client_id, at);
