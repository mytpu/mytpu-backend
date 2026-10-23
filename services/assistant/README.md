# assistant — чат «Мой ТПУ» с моделью

Принимает вопрос пользователя и отвечает через модель ml-server (Spring AI 2, OpenAI-совместимый API).
Безопасность и деплой — `docs/SECURITY.md` (раздел 11), решения — `docs/DECISIONS.md`, прод — `deploy/README.md`.

## API
| Метод | Ответ |
|---|---|
| `POST /api/ai/chat` `{"message": "…"}` | JSON с ответом модели |
| `POST /api/ai/chat/stream` `{"message": "…"}` | SSE: `meta` → `status` → `delta`… → `done`; при сбое — `error` (`code`, `message`), затем `done` с причиной `error` |
| `GET /actuator/health` | `{"status":"UP"}` (подробностей наружу нет) |

Ошибки — JSON `{"error": "<код>", "message": "<текст для человека>"}`; тексты — `src/main/resources/messages.properties`.

| Код | HTTP | Когда |
|---|---|---|
| `bad_request`, `message_required`, `message_too_long` | 400 | не JSON, пустой вопрос, длиннее `max-message-chars` |
| — | 411 / 413 | нет `Content-Length` / тело больше `max-body-bytes` |
| `unauthorized` | 401 | задан секрет `assistant_client_key`, а заголовка `X-Assistant-Key` нет или он неверный |
| `rate_limited`, `busy` | 429 + `Retry-After: 60` | лимит с адреса в минуту / все места для одновременных запросов заняты |
| `upstream_timeout` | 504 | модель не ответила за `timeout` (в потоке — молчит дольше `stream-idle-timeout`) |
| `upstream_unavailable` | 502 | ml-server недоступен или ответил ошибкой; поток оборвался без признака завершения |

## Что внутри
| Пакет | Отвечает за |
|---|---|
| `controllers` | приём запроса, проверка (`ChatRequestValidator`), ответы об ошибках (`ChatErrorHandler`) |
| `services` | вызов модели с дедлайном (`ChatService`), события потока при сбое (`StreamErrors`) |
| `limits` | размер тела, ключ клиента, окно по адресу и семафор (`ChatLimits`), адрес клиента за доверенным прокси |
| `config` | настройки `mytpu.chat.*`, проверка обязательных настроек при старте (`RequiredSettings`) |

## Настройки
| Переменная / секрет | Зачем | По умолчанию |
|---|---|---|
| `ML_BASE_URL` | адрес ml-server (`…/v1`) | — (без неё не стартует) |
| секрет `ml_api_key` (или `ML_API_KEY`) | ключ к ml-server | — (без него не стартует) |
| `ML_CHAT_MODEL` | имя модели | — (без него не стартует) |
| `ML_TIMEOUT` | срок ответа модели | `60s` |
| секрет `assistant_client_key` | ключ клиента чата; без секрета проверка выключена | — |
| `TRUSTED_PROXIES` | CIDR прокси, чей `X-Forwarded-For` учитывается | пусто |
| `mytpu.chat.*` | `max-message-chars` 4000, `max-body-bytes` 16384, `stream-idle-timeout` 30 с, `max-concurrent` 4, `per-ip-per-minute` 20 | `application.yml` |

Повторная попытка к модели — одна (SDK повторяет и 429, и 5xx); общий срок ответа держит свой дедлайн `ML_TIMEOUT`.

## Запуск
```bash
mvn -q -pl services/assistant -am package -DskipTests
docker compose -f deploy/compose.dev.yml up -d --build assistant      # http://127.0.0.1:8082
```
Прод на VPS — `deploy/compose.server.yml` и `deploy/README.md`.
