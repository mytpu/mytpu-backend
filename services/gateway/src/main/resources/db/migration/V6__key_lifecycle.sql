-- Жизненный цикл мастер-ключей: тип demo, заявки, самоперевыпуск, напоминания, группы ловушек (specs/key-lifecycle-and-decoys)

ALTER TABLE api_clients
    DROP CONSTRAINT api_clients_kind_check,
    ADD CONSTRAINT api_clients_kind_check CHECK (kind IN ('person', 'server', 'demo'));

ALTER TABLE api_clients
    ADD COLUMN rotated_at         timestamptz,
    ADD COLUMN expiry_warned_days integer;

-- Заявки на мастер-ключи (шаг 2 спецификации)
CREATE TABLE key_requests (
    id            text        PRIMARY KEY,
    at            timestamptz NOT NULL DEFAULT now(),
    requester     text        NOT NULL,
    contact       text        NOT NULL,
    purpose       text        NOT NULL,
    scopes        text[]      NOT NULL DEFAULT '{}',
    kind          text        NOT NULL CHECK (kind IN ('person', 'server', 'demo')),
    days          integer,
    networks      cidr[]      NOT NULL DEFAULT '{}',
    status        text        NOT NULL DEFAULT 'new' CHECK (status IN ('new', 'approved', 'rejected')),
    decided_by    text,
    decided_at    timestamptz,
    reject_reason text,
    client_id     text        REFERENCES api_clients (id) ON DELETE SET NULL
);
CREATE INDEX key_requests_status_at ON key_requests (status, at);

-- Группы ловушек: настоящие ключи рядом с ловушкой, приостанавливаемые при её срабатывании (шаг 5)
CREATE TABLE key_decoy_groups (
    decoy_id  text NOT NULL REFERENCES api_clients (id) ON DELETE CASCADE,
    client_id text NOT NULL REFERENCES api_clients (id) ON DELETE CASCADE,
    PRIMARY KEY (decoy_id, client_id)
);
