-- pgvector ставит суперпользователь при создании БД (deploy/postgres/init); здесь — проверка, что он есть.
-- Типы и операторы pgvector лежат в схеме public, поэтому в миграциях они указаны с ней.
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
