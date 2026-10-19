-- HNSW-индекс для текущей модели векторов (частичный: своя версия — свой индекс, смена модели без простоя)
CREATE INDEX embeddings_hnsw_e5_small_en_ru_int8_v1
    ON embeddings USING hnsw ((embedding::public.halfvec(384)) public.halfvec_cosine_ops)
    WHERE model_version = 'e5-small-en-ru-int8-v1';

-- Набор вопросов ссылается на документы по адресу: id разделов — хэши, их неудобно писать руками
ALTER TABLE eval_questions ADD COLUMN expected_urls text[] NOT NULL DEFAULT '{}';
