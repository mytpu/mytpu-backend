-- Защита мастер-ключей: тип, сети, приостановка, ключи-ловушки, учёт сетей и события (specs/mcp-key-and-agent-safety, часть А)

ALTER TABLE api_clients
    ADD COLUMN kind             text        NOT NULL DEFAULT 'person' CHECK (kind IN ('person', 'server')),
    ADD COLUMN allowed_networks cidr[]      NOT NULL DEFAULT '{}',
    ADD COLUMN suspended_at     timestamptz,
    ADD COLUMN suspended_reason text,
    ADD COLUMN decoy            boolean     NOT NULL DEFAULT false;

-- Подсети вызовов ключа (IPv4 /24, IPv6 /48): полный адрес не храним; строки старше 90 дней удаляются
CREATE TABLE key_networks (
    client_id  text        NOT NULL REFERENCES api_clients (id) ON DELETE CASCADE,
    network    cidr        NOT NULL,
    first_seen timestamptz NOT NULL DEFAULT now(),
    last_seen  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (client_id, network)
);
CREATE INDEX key_networks_last_seen ON key_networks (last_seen);

-- События ключей для консоли: key.new_network, key.network_burst, key.suspended, key.resumed, key.decoy_used, key.network_denied
CREATE TABLE key_events (
    id        bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at        timestamptz NOT NULL DEFAULT now(),
    client_id text        NOT NULL,
    event     text        NOT NULL,
    network   cidr,
    detail    text
);
CREATE INDEX key_events_at ON key_events (at);
CREATE INDEX key_events_client ON key_events (client_id, at);
