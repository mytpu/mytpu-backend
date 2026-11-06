-- Поля ответа, выключенные у ключа: инструмент → список полей (specs/console-and-connectors, шаг 7а)
ALTER TABLE api_clients ADD COLUMN denied_fields jsonb NOT NULL DEFAULT '{}'::jsonb;
