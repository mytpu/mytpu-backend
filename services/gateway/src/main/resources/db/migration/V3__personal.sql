-- Выдача личного внешним агентам (specs/personal-data-for-agents, часть А)

-- Суточный счёт личных записей на пару «пользователь — клиент»; строки старше 7 дней удаляются
CREATE TABLE personal_usage (
    user_id   text    NOT NULL,
    client_id text    NOT NULL,
    day       date    NOT NULL,
    records   integer NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, client_id, day)
);

-- Журнал выдачи: какие поля (имена, без значений), в каком режиме и почему; хранится 1 год
CREATE TABLE disclosures (
    id        bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at        timestamptz NOT NULL DEFAULT now(),
    user_id   text        NOT NULL,
    client_id text        NOT NULL,
    tool      text        NOT NULL,
    mode      text        NOT NULL,
    reason    text        NOT NULL,
    fields    text[]      NOT NULL DEFAULT '{}',
    records   integer     NOT NULL
);
CREATE INDEX disclosures_user ON disclosures (user_id, at);
CREATE INDEX disclosures_at ON disclosures (at);
