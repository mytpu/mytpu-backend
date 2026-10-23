# Архитектура

Продукт — **MCP-шлюз ТПУ**: единый вход к сервисам ТПУ для ИИ-агентов и приложений. Сайт и мобилка «Мой ТПУ» — демо-клиенты.
Целевая схема — документ 22 (`helpdesk-ai/docs/handoff/22-mikroservisy-4gb.html`): четыре крупных сервиса на одной машине 4 ГБ;
запасной вариант — монолит `app-all` из тех же модулей (документы 20–21).

## Сейчас в репозитории (ветка `feature/gateway`, 19.10)
| Модуль | Отвечает за | Состояние |
|---|---|---|
| `shared/tool-api` (Java, без Spring) | контракт: `Integration`, `Tool`, `ToolDescriptor`, `CallContext` (+ `Trust`, `Consent`), `ToolResult`, `ToolAnswer` (+ `publicSummary`), пометки полей `Classified`/`Mask` | готов |
| `services/assistant` (Java) | чат: `POST /api/ai/chat`, `/api/ai/chat/stream` (SSE meta/status/delta/done) через Spring AI 2 к ml-server | перенесён из `ai-backend`, Boot 4.1 |
| `services/rag/rag-api` (Java) | контракт поиска `DocsSearch` | готов |
| `services/rag/rag-search` (Kotlin) | схема, модель векторов (e5-small ru+en, ONNX), нарезка, загрузка, гибридный поиск, замер | готов, `services/rag/README.md` |
| `services/rag/rag-app` (Kotlin) | `POST /internal/search` (ключ gateway), профили `ingest`, `eval` | готов |
| `services/gateway` (Java) | MCP-шлюз: `catalog`, `access` (мастер-ключи), `runtime`, `mcp` (свой обработчик), `integrations.docs` (`docs_search`), `integrations.rasp` (`rasp_find`) | готов; `rasp_day/week` отложены, `services/gateway/README.md` |
| `deploy/` | общий `spring.Dockerfile` (AOT-кэш, флаги документа 22), `compose.dev.yml` (сети, секреты-файлы, укреплённые контейнеры, лимиты), замер `load/measure.sh` | готов |
| ml-server (`D:\projects\ML-server`, ветка `feature/native-image`) | прокси к LLM на Ktor CIO, подготовлен к GraalVM Native Image | собирается на сервере, `README-native.md` |

## Целевые модули и их единственная ответственность
| Модуль | Отвечает за | НЕ отвечает за | Общается с |
|---|---|---|---|
| `shared/tool-api` | контракт инструмента: `Tool`, `ToolDescriptor`, `CallContext`, `ToolResult` | реализацию проверок и вызовов | — (общий для gateway, assistant, rag) |
| `shared/call-context` | сборка `CallContext` из `X-User-*`, проверка `X-Internal-Key` | вход пользователя | заголовки от caddy/сервисов |
| `services/identity` | вход через oauth.tpu.ru, сессии, `/internal/verify`, OAuth-сервер шлюза, сейф токенов ТПУ | вызовы инструментов | caddy (forward_auth), gateway (JWKS, токены ТПУ) |
| `services/gateway` → `runtime` (tool-runtime) | реестр инструментов, проверки по порядку, кэш, таймауты, журнал; `runtime.personal` — выдача личного по режиму (классы полей, решение, вырезание, суточный лимит, журнал выдачи — `specs/personal-data-for-agents`) | транспорт и разбор токенов | двери → исполнитель → интеграции |
| `services/gateway` → `mcp` (door-mcp) | MCP `/mcp` (Streamable HTTP без состояния, свой обработчик — решение 19.10), `tools/list` по правам ключа, сборка `CallContext` | правила доступа (это runtime) | внешние агенты |
| `services/gateway` → `integrations/*` | по модулю на сервис ТПУ: `rasp`, `docs`, позже `tpuapi`, `help`, `lms` | проверки прав | сервисы ТПУ, rag |
| `services/gateway` → `catalog`, `access` | каталог интеграций; мастер-ключи клиентов (набор разрешённых API) | вызовы инструментов | door-mcp, консоль |
| `services/gateway` → `admin`, `connectors`, `stats` | API консоли MCP; API без кода (коннекторы `GET`); агрегаты журнала | интерфейс консоли | консоль; rag через `/admin/api/rag/**` |
| `console/` | интерфейс консолей MCP и rag (Vite + React + TS, статика) | данные и права | `/admin/api/**` gateway |
| `services/assistant` | чат с LLM, инструменты как `ToolCallback` → gateway | вызовы сервисов ТПУ напрямую | ml-server, gateway |
| `services/rag` | поиск по документам, ночной ingest, модель векторов, API консоли rag | права доступа | gateway (`POST /internal/search`, `/admin/**`) |
| ml-server (отдельный репо) | прокси к LLM, pii | инструменты | assistant |

## Откуда берётся контент
- Описания инструментов для модели и тексты ошибок — ресурсы `src/main/resources` сервиса, не литералы в коде.
- Лимиты, таймауты, TTL кэша, адреса внешних сервисов — `application.yml` + переменные окружения.
- Режим шлюза и выключенные инструменты — таблицы БД сервиса (меняет администратор, действует за ≤30 с).
- Данные расписания — rasp.tpu.ru; документы — rag.

## Поток данных
Дверь (MCP / `/internal/tools` / `/api/tools`) → `CallContext` → исполнитель (проверки → кэш → интеграция с таймаутом) → журнал → ответ двери.

## Схема работы
Документ 23 — процессы и сети, путь вызова, rag, секреты, модули, замер памяти: `helpdesk-ai/docs/handoff/23-shema-raboty.html`,
артефакт https://claude.ai/artifact/PqYqT8W6vaZz1QQKUtSFvz

## Безопасность и модель
- `docs/SECURITY.md` — классы данных, все токены и ключи (хранение, продление, отзыв), паспорт вызова между сервисами, сети, LLM и ПД.
- Спецификации: `specs/gateway-public-mcp/spec.md` (этап 0, rag, gateway, натив ml-server), `specs/console-and-connectors/spec.md` (консоли и API без кода).
- `docs/LLM.md` — как устроить инструменты и путь вопроса, чтобы справлялась слабая модель (GigaChat): ум в системе, модель только понимает и пересказывает.

## Бюджеты
См. `~/.claude/limits.conf`: файл ≤450 строк, `*Service/*Controller/*Manager` ≤200. Память — mem_limit из документа 22 (gateway 380 МБ).
