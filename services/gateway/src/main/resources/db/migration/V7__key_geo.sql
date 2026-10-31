-- Ограничение ключей по странам: политика geo_policy и причина для политики any (specs/key-geo-ru-only)

ALTER TABLE api_clients
    ADD COLUMN geo_policy text NOT NULL DEFAULT 'ru_only' CHECK (geo_policy IN ('ru_only', 'any')),
    ADD COLUMN geo_reason text;

-- Существующим ключам консоли и ловушкам — политика any (консоль закрыта сетями, ловушка работает отовсюду)
UPDATE api_clients
SET geo_policy = 'any'
WHERE cardinality(roles) > 0 OR decoy;
