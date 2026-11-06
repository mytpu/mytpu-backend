# assistant — чат «Мой ТПУ» с моделью

Принимает вопрос пользователя, нормализует сущности (раскладка, группа, аудитория, дата), определяет намерение
(`router`) и отвечает либо прямым путём через инструменты MCP-шлюза (`tools`), либо моделью ml-server (Spring AI 2) с теми же инструментами; ответ модели сверяется с данными инструментов (`verify`), не сошёлся — шаблон из данных.
Безопасность и деплой — `docs/SECURITY.md` (раздел 11), архитектура — `docs/LLM.md`, решения — `docs/DECISIONS.md`.

## API
| Метод | Ответ |
|---|---|
| `POST /api/ai/chat` `{"message": "…", "conversationId": "…"}` | JSON с ответом: `{"message": "…"}` |
| `POST /api/ai/chat/stream` `{"message": "…", "conversationId": "…"}` | SSE: `meta` → `status` → `card` (необязательно) → `sources` (необязательно) → `delta` → `done`; при сбое — `error`, затем `done` (`reason: error`) |
| `GET /actuator/health` | `{"status":"UP"}` (подробностей наружу нет) |

Поле `conversationId` в запросе необязательное: при передаче валидного UUID диалог продолжается с накопленными слотами; событие `meta` возвращает настоящий `conversation_id`.

События потока SSE:
- `meta`: `{"conversation_id": "…", "message_id": "…", "request_id": "…"}`
- `status`: `{"stage": "thinking", "label": "…"}`
- `card` *(новое)*: `{"tool": "rasp_find", "summary": "…", "data": {…}, "hint": "…"}`
- `sources` *(новое)*: `[{"title": "…", "url": "…"}]`
- `delta`: `{"text": "…"}` — весь ответ одним событием: ответ модели проверяется до отправки (`docs/DECISIONS.md`, 06.11)
- `done`: `{"finish": "stop"}`

Ошибки — JSON `{"error": "<код>", "message": "<текст для человека>"}`; тексты — `src/main/resources/messages.properties`.

| Код | HTTP | Когда |
|---|---|---|
| `bad_request`, `message_required`, `message_too_long` | 400 | не JSON, пустой вопрос, длиннее `max-message-chars` |
| — | 411 / 413 | нет `Content-Length` / тело больше `max-body-bytes` |
| `unauthorized` | 401 | задан секрет `assistant_client_key`, а заголовка `X-Assistant-Key` нет или он неверный |
| `rate_limited`, `busy` | 429 + `Retry-After: 60` | лимит с адреса в минуту / все места для одновременных запросов заняты |
| `upstream_timeout` | 504 | инструмент прямого пути не ответил за таймаут (путь с моделью при сбое отвечает шаблоном) |
| `upstream_unavailable`, `tool_unavailable` | 502 | шлюз недоступен на прямом пути |

## Что внутри
| Пакет | Отвечает за |
|---|---|
| `controllers` | приём запроса, проверка (`ChatRequestValidator`), ответы об ошибках (`ChatErrorHandler`) |
| `services` | оркестрация запроса (`ChatService`), обработка ошибок потока (`StreamErrors`) |
| `normalize` | исправление раскладки (`KeyboardLayout`), выделение группы (`GroupParser`), аудитории (`RoomParser`), даты (`RussianDateParser`) |
| `router` | маршрутизация намерения без модели (`IntentRouter`): `ROOM_WHERE`, `SCHEDULE`, `DOCS_HOWTO`, `GREETING`, `OTHER` |
| `conversation` | хранение диалогов и слотов в памяти Caffeine на 30 мин (`ConversationStore`), интерфейс истории (`HistoryRepository`) |
| `tools` | клиент инструментов MCP-шлюза (`GatewayToolsClient`) и сборщик прямого ответа без LLM (`DirectAnswerer`) |
| `llm` | путь с моделью: подсказка `prompts/system.md`, инструменты для модели (`ToolCatalog`, `GatewayToolCallback`), срок 15 с (`ModelAnswerer`), выбор «ответ модели или шаблон» (`ModelPath`) |
| `verify` | проверка ответа по данным: время, числа, ссылки только `*.tpu.ru` (`AnswerVerifier`); шаблоны (`TemplateAnswers`) |
| `limits` | размер тела, ключ клиента, окно по адресу и семафор (`ChatLimits`), адрес клиента за доверенным прокси |
| `config` | настройки `mytpu.chat.*`, `mytpu.assistant.*`, проверка обязательных настроек при старте (`RequiredSettings`) |

## Настройки
| Переменная / секрет | Зачем | По умолчанию |
|---|---|---|
| `ML_BASE_URL` | адрес ml-server (`…/v1`) | — (без неё не стартует) |
| секрет `ml_api_key` (или `ML_API_KEY`) | ключ к ml-server | — (без него не стартует) |
| `ML_CHAT_MODEL` | имя модели | — (без него не стартует) |
| `ML_TIMEOUT` | срок одного запроса к модели | `15s` |
| `mytpu.assistant.model-timeout` | весь путь с моделью и инструментами; дальше — шаблон из данных | `15s` |
| `GATEWAY_URL` | адрес MCP-шлюза для инструментов | `http://gateway:8080` |
| секрет `assistant_gateway_key` | ключ помощника к шлюзу (роль `chat.service`, тип `server`, сети); пустой — инструменты выключены, ответы шаблоном | пусто |
| `mytpu.assistant.history.enabled` | сохранение истории сообщений в БД | `false` |
| секрет `assistant_client_key` | ключ клиента чата; без секрета проверка выключена | — |
| `TRUSTED_PROXIES` | CIDR прокси, чей `X-Forwarded-For` учитывается | пусто |
| `mytpu.chat.*` | `max-message-chars` 4000, `max-body-bytes` 16384, `max-concurrent` 4, `per-ip-per-minute` 20 | `application.yml` |

## Запуск
```bash
mvn -q -pl services/assistant -am package -DskipTests
docker compose -f deploy/compose.dev.yml up -d --build assistant      # http://127.0.0.1:8082
```
