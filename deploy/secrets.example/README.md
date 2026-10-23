# Секреты

Секреты — отдельные файлы в `deploy/secrets/` (каталог в `.gitignore`), в контейнерах — `/run/secrets/<имя>`.
Переменные окружения для секретов не используем: их видно в `docker inspect` и `/proc/<pid>/environ` (`docs/SECURITY.md`, раздел 3).

Для локального стенда: `bash deploy/dev-secrets.sh` — создаст недостающие файлы со случайными значениями.

| Файл | Кто читает | Что это |
|---|---|---|
| `pg_superuser_password` | postgres | пароль суперпользователя (нужен только при первом запуске) |
| `gateway_db_password` | postgres, gateway | пароль пользователя `gateway` к БД `gateway` |
| `rag_db_password` | postgres, rag | пароль пользователя `rag` к БД `rag` |
| `assistant_db_password` | postgres, assistant | пароль пользователя `assistant` к БД `assistant` |
| `rag_internal_key` | gateway, rag | сервисный ключ gateway → rag (`POST /internal/search`) |
| `gateway_audit_key` | gateway | ключ HMAC для отпечатков аргументов в журнале `gateway.audit` (без него шлюз не стартует) |
| `ml_api_key` | assistant | ключ assistant → ml-server (`ML_API_KEY` ml-server) |
