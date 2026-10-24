-- Консоль администратора (specs/console-and-connectors): журнал действий, свои лимиты инструментов, агрегаты журнала.

-- Журнал действий администратора: кто, когда, что было → что стало; значения секретов — '***'
CREATE TABLE admin_audit (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at          timestamptz NOT NULL DEFAULT now(),
    actor_id    text        NOT NULL,
    actor_owner text        NOT NULL,
    action      text        NOT NULL,
    object_type text        NOT NULL,
    object_id   text,
    before      jsonb,
    after       jsonb
);
CREATE INDEX admin_audit_at ON admin_audit (at);
CREATE INDEX admin_audit_object ON admin_audit (object_type, object_id, at);

-- Свои лимиты инструмента поверх application.yml (пусто — значение из конфигурации)
CREATE TABLE tool_settings (
    tool            text        PRIMARY KEY,
    cache_ttl_ms    bigint      CHECK (cache_ttl_ms >= 0),
    timeout_ms      bigint      CHECK (timeout_ms > 0),
    rate_per_minute int         CHECK (rate_per_minute > 0),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- Агрегаты журнала по минутам: обзор консоли читает их, а не сырой журнал.
-- buckets — сколько вызовов уложилось в ≤ 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000, 30000 мс и дольше (12 корзин)
CREATE TABLE audit_minute (
    minute     timestamptz NOT NULL,
    client_id  text        NOT NULL,
    tool       text        NOT NULL,
    calls      int         NOT NULL DEFAULT 0,
    errors     int         NOT NULL DEFAULT 0,
    cache_hits int         NOT NULL DEFAULT 0,
    total_ms   bigint      NOT NULL DEFAULT 0,
    buckets    int[]       NOT NULL,
    PRIMARY KEY (minute, client_id, tool)
);
CREATE INDEX audit_minute_tool ON audit_minute (tool, minute);

-- Перевыпуск ключа из консоли: прежний ключ принимается ещё до previous_key_until (по умолчанию 24 ч)
ALTER TABLE api_clients
    ADD COLUMN previous_key_hash  bytea UNIQUE,
    ADD COLUMN previous_key_until timestamptz;
