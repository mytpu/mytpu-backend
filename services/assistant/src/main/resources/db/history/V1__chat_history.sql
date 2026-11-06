-- История диалогов помощника (spec 5, шаг 7): только маскированный текст, очистка по сроку; включается настройкой
CREATE TABLE chat_history (
    id              bigserial PRIMARY KEY,
    conversation_id uuid        NOT NULL,
    message_id      uuid        NOT NULL,
    role            text        NOT NULL CHECK (role IN ('user', 'assistant')),
    content         text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX chat_history_created_at ON chat_history (created_at);
CREATE INDEX chat_history_conversation ON chat_history (conversation_id);
