# Укрепление после внешнего ревью: прод-деплой assistant, чат, проверки при старте, мелочи шлюза

Основа — внешнее ревью (GLM, 2026-10-23), находки сверены с кодом. Опирается на `specs/gateway-public-mcp`,
`specs/mcp-key-and-agent-safety` и `specs/personal-data-for-agents` (части А сделаны, коммит `c2df901`) и `docs/SECURITY.md`.

**Граница со спецификацией 3 `deploy-server`.** Сейчас CI (`.github/workflows/ci-cd-ai-backend.yml`) выкладывает на VPS
команды **только `assistant`** — старым `docker/docker-compose.backend-java.yml` (`env_file: .env`, образ `latest`, без укрепления).
Здесь — немедленно закрыть этот разрыв для того, что уже деплоится. Полное развёртывание всех сервисов за caddy на ВМ ТПУ —
по-прежнему спецификация 3, и она возьмёт отсюда тот же файл compose и тот же порядок выкладки.

## Зачем
Ревью показало: система «закрыта по умолчанию» в безопасности, но «молчит» при поломках в эксплуатации, а то, что реально
работает на сервере, собрано в обход заявленных правил. Цель — чтобы на сервере было то же укрепление, что на стенде,
чат не падал и не зависал от одного плохого запроса или зависшего ml-server, а сервисы при неверной настройке
**не стартовали** с понятной ошибкой вместо тихой работы «как будто всё хорошо».

## Принципы
- **Сервер = стенд.** Те же правила, что в `compose.dev.yml`: секреты файлами, `read_only`, `cap_drop`, `no-new-privileges`,
  лимиты памяти, образ по неизменяемому тегу.
- **Ошибка при старте лучше тихой деградации:** неверная настройка → сервис не стартует и пишет, что исправить.
- **Каждая ошибка доходит до клиента по контракту:** в SSE — событие `error`, затем `done`; в MCP — ошибка JSON-RPC.
- **Ничего не ломать команде:** фронтенд и мобилка на моках продолжают работать с `/api/ai/chat` без изменения формата.

## Критерии готовности (проверяемые)

### 1. assistant: чат
- [x] `message` обязателен, не пустой, не длиннее `mytpu.chat.max-message-chars` (по умолчанию 4000); иначе 400 с JSON
  `{error, message}` на русском; тело запроса больше 16 КБ → 413. Проверка: `curl` с `{}`, `{"message":""}`, 5000 символов.
- [x] Обычный ответ: таймаут вызова ml-server `mytpu.chat.timeout` (по умолчанию 60 с) → 504 с понятным текстом, поток
  освобождается. Проверка: ml-server-заглушка, не отвечающая 90 с → 504 через ~60 с.
- [x] Поток SSE: при ошибке ml-server или таймауте простоя потока (`mytpu.chat.stream-idle-timeout`, 30 с без кусков) клиент получает
  событие `error` (`code`, `message`) и затем `done` (`{"finish": "error"}` — поле `finish`, как у обычного `done`;
  контракт — `services/assistant/README.md`). Проверка: остановить ml-server
  посреди потока → последние события `error`, `done`.
- [x] Ретраи Spring AI: не больше 2 попыток, суммарно не дольше таймаута. *Отступление (DECISIONS 23.10):* SDK OpenAI
  повторяет и 429, и 5xx, по кодам это не настраивается — оставлена одна повторная попытка, общий срок держит свой дедлайн.
- [x] Лимиты: одновременных запросов к модели не больше `mytpu.chat.max-concurrent` (по умолчанию 4), сверх — 429 с `Retry-After`;
  с одного адреса — не больше `mytpu.chat.per-ip-per-minute` (по умолчанию 20). Адрес — только от доверенного прокси
  (как `ClientAddress` в gateway).
- [x] Необязательный ключ клиента чата: если задан секрет `assistant_client_key`, без заголовка `X-Assistant-Key` с этим
  значением — 401; не задан — как сейчас (фронтенд на моках не ломается). До identity это единственная защита от чужих вызовов.
- [x] `GET /actuator/health` — только статус, без деталей.
- [x] Запасные имена переменных `SPRING_AI_OPENAI_*` (перепутанные местами в старом `.env`) убраны: только `ML_BASE_URL`,
  `ML_CHAT_MODEL` и секрет `ml_api_key`. Без адреса или ключа assistant **не стартует** с понятной ошибкой.

### 2. Прод-деплой assistant
- [x] `deploy/compose.server.yml` — сервис `assistant` на сервере: образ `ghcr.io/mytpu/ai-backend:${IMAGE_TAG}` (тег = SHA коммита,
  без `latest` и `pull_policy: always`), секреты файлами из `./secrets/` (`ml_api_key`, при необходимости `assistant_client_key`),
  несекретные настройки — в `environment`, `read_only` + `tmpfs`, `cap_drop: [ALL]`, `no-new-privileges`, лимиты памяти,
  `127.0.0.1:8080:8080`, без `env_file`.
- [x] CI: деплой только после зелёного `verify` и `secrets` (gitleaks) в том же запуске; после `up -d` — проверка здоровья
  `curl -fsS 127.0.0.1:8080/actuator/health` (до 60 с); не поднялся → откат на предыдущий тег (сохранён на сервере в файле) и красный CI.
- [x] Сканер образа (trivy, контейнер по digest) — до выгрузки в GHCR; критичные уязвимости с исправлением → CI красный.
- [x] Базовые образы в `deploy/docker/spring.Dockerfile` — по digest; флаги JVM — в одном месте (`ARG`), а не в двух стадиях.
- [x] gitleaks идёт и на push в `main` (сейчас — только `feature/**` и PR).
- [x] Старый `docker/docker-compose.backend-java.yml` удалён; в `services/assistant/README.md` (или `deploy/README.md`) — перенос
  на сервере: `.env` → файлы `secrets/` (делает владелец VPS, значения в репозиторий и чат не попадают).

### 3. Проверки при старте (fail-fast)
- [x] **ml-server:** `ML_PROVIDER` только `mock` или `openai`; другое (в том числе `OpenAI`, опечатка) → сервис не стартует.
  `mock` в проде — только с явным `ML_ALLOW_MOCK=true`, иначе не стартует, если задан `UPSTREAM_URL` (признак прода).
- [x] **ml-server:** `X-Request-Id` принимается только `[A-Za-z0-9_.-]{1,64}`, иначе генерируется свой; тело ошибки апстрима
  читается не больше 4 КБ; таймаут потока — из конфига (`ML_STREAM_TIMEOUT_MS`, 5 мин; *отступление:* отдельная переменная — длинный поток законно
  дольше обычного запроса), а не константа 300 с; битый JSON запроса пишется в лог (WARN, без тела).
- [x] **rag:** при старте поиска проверяется, что для `mytpu.rag.model.version` есть частичный индекс HNSW (по `pg_indexes`);
  нет → сервис не стартует и называет миграцию, которую нужно добавить. Проверка: сменить версию в конфиге → понятная ошибка.
- [x] **rag ingest:** оба файла датасета (`help-tpu.json`, `tpu-site.jsonl`) обязательны и должны читаться целиком; любого нет
  или он битый → загрузка останавливается, корпус не трогается (сейчас отсутствие одного файла стирает половину корпуса).
- [x] **rag ingest:** хэш документа включает заголовок — переименование статьи подхватывается; вектор с `NaN`/`Infinity` — ошибка
  загрузки документа, а не 500 при поиске.
- [x] **rag:** `ef_search` — в `mytpu.rag.search.*`, а не литералом.

### 4. Шлюз (мелкие правки)
- [x] Суточная квота личного **никогда** не превышает лимит: резерв — не больше остатка (`LEAST(maxItems, max − records)`),
  и списки ответа обрезаются до занятого резерва. Проверка: 199 записей занято → следующий вызов отдаёт 1 запись, затем `personal_quota`.
- [x] События ключей-ловушек не попадают под поклиентный лимит 20/мин (ограничены только общим числом фоновых записей).
- [x] `docs/ADDING_API.md` пункт про `needs_choice` совпадает с кодом: не в режиме `full` варианты заменяются фразой «выберет в «Мой ТПУ»».
- [x] `DevCallerResolver` не пишет в лог значения заголовков, только имя заголовка и причину.
- [x] 429 из `McpKeyFilter` (слишком много неудачных попыток) — с `Retry-After: 60`.
- [x] Непредвиденное исключение в `/mcp` → ошибка JSON-RPC `-32603` (`internal error`) с `id` запроса, а не HTML-500 Spring;
  из `PROTOCOL_VERSIONS` убрана `2025-03-26` (в ней были пакеты, которые шлюз не поддерживает).
- [x] Кэш разобранных сетей в `NetworkPolicy` ограничен (`maximumSize`); сеть из БД, которая не разбирается, → отказ ключу
  (403 + WARN), а не 500.
- [x] Хэш аргументов в журнале одинаков для отказа и успеха (один и тот же канонический JSON).
- [x] `docs/SECURITY.md`: ответы личных инструментов до вырезания живут в кэше шлюза в памяти ≤ 60 с; метка недоверенного
  текста (`SessionTaint`) — в памяти экземпляра, при нескольких экземплярах нужна общая.

## Не входит (non-goals)
- Полное развёртывание всех сервисов за caddy на ВМ ТПУ, HTTPS, бэкапы — спецификация 3 `deploy-server`.
- Вход пользователя в чат, история диалогов, `conversation_id` от клиента — спецификации 4 и 5.
- **Тесты** — их пишет тестировщик команды. Здесь — список для него (раздел «Для тестировщика»), ручные проверки в плане.
- Нативная сборка ml-server и её прогон — на сервере, в спецификации 3.
- Мёртвый код `rasp_week` (`RaspClient.page`, `GroupResolver`, `RaspRefResolver`) и пустые таблицы rag (`glossary`, `lexicon`,
  `query_log`) — остаются как задел; в README помечаются «не используется до …».
- Журнал запросов rag (`query_log`) и порог eval в CI — отдельная задача после первых живых пользователей.

## Ограничения
- Безопасность/данные: значения секретов (`.env` на VPS, `deploy/secrets/*`, `ml_api_key`) не читать и не печатать; перенос `.env`
  в файлы на сервере делает владелец VPS по инструкции. `GHCR_TOKEN` в SSH-шаге — через `env:` действия, не интерполяцией в скрипт.
- Архитектура: в assistant новых `*Service` нет — `ChatService` остаётся вызовом модели; лимиты (`ChatLimits`), ошибки потока
  (`StreamErrors`), обработчик ошибок (`ChatErrorHandler`) — отдельные классы. ml-server — отдельный репозиторий (`{ML-server}`,
  ветка `feature/native-image`), его правки — отдельным коммитом там.
- Контент: тексты ошибок чата — `services/assistant/src/main/resources/messages.properties`; лимиты и таймауты — `application.yml`
  (`mytpu.chat.*`); тег образа и пути — переменные compose.

## Для тестировщика (приоритет)
1. `ResultRedactor` + `FieldClassifier` — ни одно `PERSONAL`/`SENSITIVE` значение не уходит в `masked`/`reference` (в том числе
   в `summary`, `hint`, `needs_choice`, ошибках); 2. `Cidr`/`ClientAddress`/`NetworkPolicy` — IPv4, IPv6, IPv4-mapped, подделка
   `X-Forwarded-For`; 3. `PersonalQuota` — параллельные вызовы, граница лимита; 4. `McpKeyFilter` — порядок проверок, ловушка,
   приостановка; 5. rag `Chunker`/`QueryText` и eval-порог; 6. SSE чата и ml-server — обрыв, таймаут, `error` + `done`.

## План (заполняет Claude, утверждаешь ты)
Пути: `A/` = `services/assistant/src/main/`, `G/` = `services/gateway/src/main/java/org/mytpu/gateway/`,
`S/` = `services/rag/rag-search/src/main/`, `M/` = `{ML-server}/src/main/kotlin/ru/tpu/ml/`.

| # | Шаг | Файлы | Проверка |
|---|---|---|---|
| 1 ✅ | assistant: валидация, ошибки, health | `services/assistant/pom.xml` (validation, actuator), `A/java/.../dto/ChatRequest.java` (`@NotBlank`, `@Size`), `A/java/.../controllers/ChatController.java` (`@Valid`), `A/java/.../controllers/ChatErrorHandler.java`, `A/resources/messages.properties`, `A/resources/application.yml` (лимит тела, health) | `curl`: `{}`, пустое, 5000 символов → 400 с текстом; 20 КБ → 413; `/actuator/health` → `{"status":"UP"}` |
| 2 ✅ | assistant: таймауты, ретраи, ошибки SSE | `A/java/.../services/ChatService.java` (`timeout`, `onErrorResume` → `error` + `done`), `A/java/.../services/StreamErrors.java`, `A/java/.../dto/ErrorEvent.java`, `A/resources/application.yml` (`spring.ai.retry.*`, таймауты HTTP-клиента), без запасных `SPRING_AI_OPENAI_*` | заглушка-«висяк» → 504 через 60 с; остановка ml-server в потоке → `error`, `done`; без `ML_BASE_URL` — не стартует |
| 3 ✅ | assistant: лимиты и необязательный ключ | `A/java/.../limits/ChatLimits.java` (семафор + окно по адресу), `A/java/.../limits/ClientAddress.java` (доверенные прокси), `A/java/.../limits/ClientKeyFilter.java` | 5 параллельных при лимите 4 → один 429 с `Retry-After`; 21-й запрос с адреса за минуту → 429; с секретом `assistant_client_key` без заголовка → 401, без секрета — 200 |
| 4 ✅ | Прод-деплой и CI | `deploy/compose.server.yml`, `deploy/docker/spring.Dockerfile` (digest, один `ARG` флагов), `.github/workflows/ci-cd-ai-backend.yml` (тег = SHA, needs verify + secrets, trivy, health-check и откат, токен через `env`), `.github/workflows/ci-build.yml` (gitleaks на `main`), удалить `docker/docker-compose.backend-java.yml`, `deploy/README.md` (перенос `.env` → `secrets/`) | `docker compose -f deploy/compose.server.yml config`; локально: образ из `spring.Dockerfile` собирается, `compose.server.yml` поднимает assistant с секретом-файлом, health → UP; `actionlint` в контейнере; trivy в контейнере на собранном образе. Сам деплой на VPS — после пуша и переноса `.env` владельцем |
| 5 ✅ | ml-server: fail-fast и мелочи | `M/config/Config.kt` (`ML_PROVIDER` ∈ {mock, openai}, `ML_ALLOW_MOCK`), `M/Application.kt`, `M/api/Monitoring.kt` (`X-Request-Id`), `M/providers/OpenAiCompatibleProvider.kt` (тело ошибки ≤ 4 КБ, таймаут потока из конфига), `M/api/Errors.kt` (лог битого JSON) | `ML_PROVIDER=OpenAI` → не стартует; `UPSTREAM_URL` + `mock` без `ML_ALLOW_MOCK` → не стартует; `X-Request-Id: a b<c>` → свой id; `./gradlew build --no-daemon` |
| 6 ✅ | rag: проверки при старте и ingest | `S/kotlin/.../search/HnswIndexCheck.kt`, `S/kotlin/.../ingest/DatasetReader.kt` (оба файла обязательны), `S/kotlin/.../ingest/CorpusIngest.kt` (заголовок в хэше), `S/kotlin/.../embed/OnnxEmbedder.kt` (конечные числа), `S/kotlin/.../search/HybridDocsSearch.kt` + `RagProperties.kt` (`ef-search`) | версия модели без индекса → rag не стартует с именем миграции; без `tpu-site.jsonl` → ingest остановлен, число документов в БД то же; переименованная статья → `changed` |
| 7 ✅ | Шлюз: мелкие правки | `G/runtime/personal/PersonalQuota.java` + `PersonalDisclosure.java` (резерв ≤ остатка), `G/access/KeyEvents.java` (ловушки вне лимита), `G/access/DevCallerResolver.java` (лог), `G/access/McpKeyFilter.java` (`Retry-After`), `G/mcp/McpEndpoint.java` + `McpProtocol.java` (`-32603`, версии), `G/access/NetworkPolicy.java` (Caffeine, отказ на битой сети), `G/runtime/ToolExecutor.java` (один хэш), `docs/ADDING_API.md` | 199 занято → 1 запись, затем `personal_quota`; 25 вызовов ловушки → 25 событий; 31-я неудача → 429 с `Retry-After`; исключение в обработчике → `{"jsonrpc":"2.0","error":{"code":-32603…}}`; повтор проверок `mcp-key-and-agent-safety` (13) и `personal-data-for-agents` (24) |
| 8 ✅ | Документы и ревью | `docs/SECURITY.md` (раздел деплоя, кэш личного, `SessionTaint`), `docs/DECISIONS.md`, `docs/ARCHITECTURE.md`, `services/assistant/README.md`, `specs/README.md` | `verify.sh` зелёный; arch-reviewer и security-reviewer — критичное исправлено |

Шаги 1–3 — по порядку (assistant); 4 — после 1 (нужен health); 5 и 6 — независимы (другой репозиторий и модуль); 7 — независим.

## Ответы на открытые вопросы (утверждено 2026-10-23)
1. Новый деплой в CI — за флагом, **выключен** (`vars.ASSISTANT_DEPLOY_ENABLED != 'true'` → шаг деплоя пропускается),
   пока владелец VPS не перенесёт `.env` в файлы `secrets/`. Сборка, проверки и выгрузка образа работают как раньше.
2. `/api/ai/chat` наружу — уточнить у владельца VPS; ключ клиента `assistant_client_key` готов, включается секретом.
3. Лимиты чата — 4 одновременных, 20 в минуту с адреса, 4000 символов, 60 с ответа.
4. `mock` в ml-server при заданном `UPSTREAM_URL` — только с `ML_ALLOW_MOCK=true`, иначе не стартует.

## Открытые вопросы (были)
1. **Чей VPS из `secrets.VPS_HOST`** и кто перенесёт там `.env` в файлы `secrets/`? Без этого шага новый деплой не стартует —
   до переноса CI нужно держать выключенным (или временно оставить старый путь). Предлагаю: шаг 4 мержится, деплой включается
   флагом в CI после подтверждения владельца VPS.
2. **Открыт ли `/api/ai/chat` наружу** через прокси на этом VPS (порт в compose — только `127.0.0.1`)? Если да — включить
   `assistant_client_key` сразу и передать ключ фронтенду через его конфиг.
3. **Лимиты чата по умолчанию:** 4 одновременных, 20 в минуту с адреса, 4000 символов, 60 с ответа — подходят для показа вузу?
4. **ml-server `mock` в проде:** запрещать без `ML_ALLOW_MOCK=true` (предлагаю) или только предупреждать в логе?

## Итог (2026-10-23)
Все шаги 1–8 выполнены и проверены по таблице «План»; `verify.sh` зелёный, ml-server — `./gradlew build`.
- Проверки на стенде: assistant (валидация, 411/413, лимиты, ключ клиента, 504/502, `error` → `done`), сервер-симуляция
  `compose.server.yml` (health UP, `read_only`, `cap_drop`, uid 10001), actionlint 0 замечаний, trivy 0 критичных,
  ml-server (fail-fast провайдера и mock, `X-Request-Id`), rag на Postgres (нет индекса → не стартует, нет `tpu-site.jsonl` →
  ingest остановлен, документов 126 как было, переименование → `changed`), шлюз — 13 + 23 прежних проверки и 7 новых.
- Ревью (arch-reviewer, security-reviewer). Исправлено: 🔴 обход ключа клиента и лимита тела путём `/api;x/…` и `/%61pi/…`
  (фильтры теперь закрыты по умолчанию); 🟡 429 на потоке превращался в 500; IPv6-лимит по /64; место в лимите
  освобождается только после реального конца вызова модели; проверка `mytpu.chat.*` > 0 при старте; цикл пакетов
  `limits`↔`controllers` (ошибки — в `errors`); CI: `packages: write` только у `build`, `concurrency`, откат и при падении
  `compose up`, `docker logout`; права файлов секретов `400` с владельцем 10001; в лог `/mcp` — только известный метод и
  класс исключения; ml-server читает тело ошибки с потолком и в обычном вызове, в лог — без тела ответа модели.
- Отступления: одна повторная попытка Spring AI (SDK повторяет и 429); `ML_STREAM_TIMEOUT_MS`; Tomcat 11.0.27 закреплён;
  `ClientAddress` assistant повторяет правило шлюза; суточный лимит личного — транзакция с блокировкой (все — в DECISIONS).
- Хвосты ревью закрыты отдельно 23.10: HMAC-SHA256 с секретом `gateway_audit_key` вместо SHA-256 для хэша аргументов
  журнала; `ML_PROVIDER` в ml-server обязателен; выкладка в CI — через окружение `production` с подтверждением.
  Деплой включается флагом после переноса `.env` владельцем VPS.
