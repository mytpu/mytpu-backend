-- Коннекторы из консоли (specs/console-and-connectors): описание версиями, опубликована одна, секреты зашифрованы.

CREATE TABLE connectors (
    id                text        PRIMARY KEY CHECK (id ~ '^[a-z][a-z0-9_]{1,31}$'),
    published_version int,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

-- Версия не меняется после сохранения: правка — новая версия; публикация и откат — смена connectors.published_version
CREATE TABLE connector_versions (
    connector_id text        NOT NULL REFERENCES connectors (id) ON DELETE CASCADE,
    version      int         NOT NULL CHECK (version > 0),
    spec         jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   text        NOT NULL,
    PRIMARY KEY (connector_id, version)
);

ALTER TABLE connectors
    ADD CONSTRAINT connectors_published_fk FOREIGN KEY (id, published_version)
        REFERENCES connector_versions (connector_id, version);

-- AES-256-GCM, мастер-ключ — файл-секрет connector_vault_key; AAD = id коннектора + имя секрета + origin.
-- origin — куда секрет разрешено отправлять (способ входа, адреса API и токена): версия с другим адресом его не получит
CREATE TABLE connector_secrets (
    connector_id text        NOT NULL REFERENCES connectors (id) ON DELETE CASCADE,
    name         text        NOT NULL,
    origin       text        NOT NULL,
    nonce        bytea       NOT NULL,
    ciphertext   bytea       NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (connector_id, name)
);
