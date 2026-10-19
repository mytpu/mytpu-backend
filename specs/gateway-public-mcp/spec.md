# MCP-шлюз «Мой ТПУ» для доверенных клиентов на одной машине 4 ядра / 4 ГБ

Ветка: `feature/gateway` (от `feature/rag`). Основа — документ 22; безопасность — `docs/SECURITY.md`, инструменты для слабой модели — `docs/LLM.md`.

Что входит:
- **этап 0 целиком** — репозиторий переходит на Boot 4.1 / Java 25 и структуру `shared/`, `services/`, `deploy/`; `ai-backend` чинится и становится `services/assistant`;
- **rag** — настоящий поиск по документам (документ 19), одним из первых;
- **gateway** — tool-runtime + door-mcp + инструменты расписания и поиска; доступ к `/mcp` только по ключу доверенного клиента;
- **мастер-ключ** — один ключ клиента открывает ровно те API, что ему включены; настоящие ключи сервисов ТПУ остаются в шлюзе;
- **контракт интеграции** — новое API подключается новым модулем, не трогая ядро; на нём потом встанут консоли и подключение API без кода (`specs/console-and-connectors`);
- **ml-server** — подготовка к сборке в GraalVM Native Image (сама сборка — на сервере);
- **укрепление** по `docs/SECURITY.md` и **полная схема и архитектура** работающей системы — для показа вузу.

## Зачем
Доверенный человек (команда, администрация, пилотная группа) подключает к `https://<домен>/mcp` свой ИИ-агент (Claude Code, MCP Inspector)
по выданному ключу и спрашивает «какие пары у 8К31 в понедельник?» или «как получить справку?». Шлюз отвечает данными ТПУ;
каждый вызов проходит одни и те же проверки и попадает в журнал — на этом же исполнителе потом встанут вход ТПУ, личные инструменты и чат.
Код, документы и схема показывают вузу: модульная архитектура, понятная безопасность, всё помещается в одну машину 4 / 4.

## Критерии готовности (проверяемые)

**Этап 0**
- [ ] Корневой `pom.xml` — Spring Boot 4.1.x, Java 25; `mvn -q verify` из корня собирает `shared/tool-api`, `services/assistant`, `services/rag/*`, `services/gateway`.
- [ ] `backend-java/`, `rag/` (старое место), `help-tpu-service`, `help-tpu-mcp-server` удалены; их код перенесён (`ai-backend` → `services/assistant`, `rag/rag-api` → `services/rag/rag-api`).
- [ ] assistant на Spring AI 2: `POST /api/ai/chat` и `/api/ai/chat/stream` отвечают так же, как до переноса (meta / status / delta / done) против ml-server; переменные `ML_BASE_URL`, `ML_API_KEY`, `ML_CHAT_MODEL` больше не перепутаны; в `MetaEvent` поле `conversation_id`; неиспользуемых импортов нет.
- [ ] Один общий `deploy/docker/spring.Dockerfile` (eclipse-temurin:25-jre, `-Djarmode=tools extract`, тренировочный прогон для AOT-кэша, флаги из документа 22) собирает образ любого Spring-сервиса по `ARG MODULE`.
- [ ] CI: workflow деплоя assistant смотрит на новые пути и JDK 25, образ по-прежнему `ghcr.io/mytpu/ai-backend` (сервер не ломается); новый workflow `ci-build.yml` собирает все модули на pull request без деплоя.

**rag**
- [ ] Загрузка стартового корпуса (`helpdesk-ai/data/raw`: 32 статьи help.tpu.ru, 94 страницы сайта) → документы, разделы, куски ~300 токенов с префиксом «Документ › Раздел», вектор multilingual-e5-small int8 (словарь урезан до ru+en, векторы те же) для каждого куска (`model_version` записан — модель можно сменить без смены схемы).
- [ ] `POST /internal/search {"query":"как получить справку об обучении","limit":5}` с ключом gateway → `found=true`, куски с заголовком, ссылкой, путём раздела и датой; без ключа или с чужим → 401.
- [ ] Набор из ≥ 30 вопросов с ожидаемым документом (составляю я, ты проверяешь): hit@3 ≥ 80 %, hit@5 ≥ 85 %, ложное «нашёл» на вопросах без ответа ≤ 10 %; отчёт печатает профиль `eval`.

**gateway: доступ**
- [ ] `/mcp` без ключа → 401 с `WWW-Authenticate: Bearer`; с отключённым или просроченным ключом → 401; с действующим → работает.
- [ ] Ключ выдаёт администратор командой (профиль `issue-key`): ключ печатается один раз, в БД — только SHA-256; у ключа есть владелец, срок (по умолчанию 90 дней) и **набор разрешений**: какие интеграции (scopes, например `rasp.read`, `docs.read`) включены, какие инструменты внутри них выключены, свои лимиты частоты, режим «только чтение».
- [ ] Мастер-ключ: клиент предъявляет только свой ключ; ключи и токены сервисов ТПУ подставляет интеграция, клиент их не видит ни в ответах, ни в ошибках. Ключ с `docs.read` без `rasp.read` не видит и не вызывает инструменты расписания (`tool_not_allowed`).
- [ ] Scopes при выдаче ключа проверяются по каталогу интеграций: неизвестный scope → отказ команды с перечнем допустимых.
- [ ] Отключение ключа (`enabled=false`) действует не позже чем через 30 с.
- [ ] Claude Code: `claude mcp add --transport http mytpu http://localhost:8081/mcp --header "Authorization: Bearer <ключ>"` → отвечает на «где аудитория 403» (через `rasp_find`, со ссылкой на расписание) и «как получить справку об обучении» (через `docs_search`, со ссылкой); вопрос про пары — после шага 14.

**gateway: интеграции**
- [ ] Каждая интеграция (`docs`, `rasp`) — отдельный пакет Spring Modulith, реализующий `Integration`: id, название, scopes с описаниями на русском, нужные секреты, проверка здоровья, инструменты.
- [ ] Инструмент, добавленный в реестр на работающем gateway (интеграция `example`, dev-команда), появляется в `tools/list` без перезапуска; удалённый — пропадает.
- [ ] Каталог `GET /internal/catalog` (для будущих консолей) отдаёт интеграции → scopes → инструменты → состояние (включена, здорова, есть ли секреты).
- [ ] Интеграцию можно выключить целиком (`integration_state.enabled=false`) — за ≤ 30 с её инструменты пропадают из `tools/list` у всех ключей.
- [ ] Интеграция без нужного секрета не роняет запуск gateway: она помечается «не настроена», её инструменты не публикуются.
- [ ] Проверка «добавить API»: интеграция-образец `example` (одна заглушка, профиль dev) добавляется одним новым пакетом и строкой в `descriptions.properties` — без правок `runtime`, `mcp`, `access` (видно по diff).

**gateway: инструменты** (`docs/LLM.md`, раздел 7)
- [ ] `tools/list` (без `Mcp-Session-Id`) возвращает ровно `docs_search`, `rasp_find` (`rasp_week`, `rasp_day` — отложены, см. шаг 14) (и `system_ping`, только если `mytpu.gateway.dev-tools=true`) и только те, чьи группы есть в scopes ключа; имена — `[a-z0-9_]`; у каждого — короткое описание на русском с примером и JSON Schema, где у всех полей, кроме главного, есть умолчания.
- [ ] ⏸ (отложено, шаг 14) `rasp_day {"group":"8к31","date":"2026-10-19"}` → пары дня (время, вид занятия, дисциплина, преподаватель, корпус, аудитория), совпадают с ro-rasp.tpu.ru; тот же результат для `"date":"19.10"` и для `"date":"завтра"`, вызванного 18.10; `rasp_week` — то же за неделю.
- [ ] Ответ каждого инструмента — `summary` (готовая фраза на русском, с датой и чётностью недели), `data` (для карточки), `hint` (что делать дальше).
- [ ] ⏸ (вместе с шагом 14) Группа с несколькими совпадениями → `needs_choice` с 2–5 вариантами и готовым вопросом; несуществующая группа → ошибка-инструкция «Группа не найдена. Уточни номер, например 8К31.»
- [ ] `rasp_find {"query":"403"}` и `{"query":"Иванов"}` → аудитории и преподаватели с пояснением (корпус, должность) и ссылкой на их страницу rasp.tpu.ru.
- [ ] `docs_search {"query":"как получить справку"}` → `summary` с выдержкой + куски со ссылками; ничего не нашлось → `found=false` и `hint` «спросить в поддержке»; rag недоступен → `upstream_unavailable` без стектрейса.

**gateway: исполнитель**
- [ ] Повторный вызов в пределах TTL (rasp — 10 мин, docs — 1 ч) не идёт во внешний сервис: в журнале `cache_hit = true`, длительность < 20 мс.
- [ ] Неверные аргументы (`date: "в прошлом веке"`, пустой `query`) → `invalid_arguments` с текстом-инструкцией; интеграция не вызывается.
- [ ] Больше `rate-limit` вызовов одного инструмента одним ключом за минуту → `rate_limited`; другой ключ в это время обслуживается.
- [ ] `tool_state.enabled=false` → не позже чем через 30 с `tool_disabled`; `mode='read_only'` и `'internal_only'` работают по документу 20.
- [ ] Каждый вызов (успех и отказ) — строка в `gateway.audit`: время, `request_id`, канал `MCP`, клиент (id ключа), инструмент, хэш аргументов (не сами аргументы), итог, `cache_hit`, мс.
- [ ] Таймаут интеграции → `upstream_timeout`, поток запроса освобождается.

**Безопасность** (`docs/SECURITY.md`, раздел 13)
- [ ] Ни одного секрета в переменных окружения: `docker inspect` gateway и rag не показывает ключей и паролей; секреты — файлы `/run/secrets/*` с правами 0400.
- [ ] rag и Postgres недоступны снаружи и не имеют выхода в интернет (сети `internal: true`); из контейнера rag `curl https://ya.ru` не проходит; порт 5432 не опубликован.
- [ ] Ключи сравниваются за постоянное время; ключ gateway → rag свой, не совпадает ни с одним другим.
- [ ] Контейнеры gateway, rag, assistant: не root, `read_only: true`, `cap_drop: [ALL]`, `no-new-privileges`.
- [ ] После нагрузочного прогона в логах нет ни аргументов инструментов, ни ключей, ни заголовков `Authorization`/`Cookie` (`grep` по шаблонам из `docs/SECURITY.md`, раздел 10).
- [ ] У каждого сервиса свой пользователь Postgres с правами только на свою БД.

**ml-server: готов к нативной сборке** (`D:\projects\ML-server`)
- [ ] Сервер Ktor на движке CIO (Netty убран); `./gradlew build` проходит, JVM-версия отвечает обычным ответом и потоком SSE на мок-провайдере, 429 при занятости — как раньше.
- [ ] Подготовлено, но не запускалось: плагин `org.graalvm.buildtools.native`, метаданные достижимости, `Dockerfile.native`, корневой сертификат НУЦ Минцифры (для GigaChat), инструкция сборки и проверки на сервере в `README-native.md` (команды и ожидаемые цифры: старт ≤ 200 мс, RSS ≤ 128 МБ при 20 потоках).

**Машина 4 / 4** (на твоём ПК — только ориентир; как факт цифры снимаются на сервере)
- [ ] Лимиты контейнеров из документа 22: gateway и rag по `380m`, assistant `300m`, Postgres `640m`, `memswap_limit = mem_limit`, флаги JVM из раздела 3 документа 22.
- [ ] Под нагрузкой (50 параллельных вызовов MCP вперемешку, 5 минут) на ПК — ни одного OOM-kill и рестарта; цифры `docker stats` и `jcmd VM.native_memory summary` записаны с пометкой «ПК, ориентир».
- [ ] Ничего не растёт без предела: у каждого кэша Caffeine есть `maximumSize`/`maximumWeight`, пулы БД по 5, MCP без сессий не хранит состояние клиентов.
- [ ] Скрипт `deploy/load/measure.sh` повторяет тот же замер на сервере одной командой.

**Документы**
- [ ] Ни один файл не превышает лимиты `~/.claude/limits.conf`; классов `*Manager` нет.
- [ ] Документ 23 со схемой и архитектурой работающей системы — в `helpdesk-ai/docs/handoff` и артефактом; `docs/ARCHITECTURE.md`, `docs/DECISIONS.md`, `docs/SECURITY.md` — по факту кода.

## Не входит (non-goals)
- identity: вход ТПУ, сессии сайта и мобилки, OAuth-сервер для агентов, сейф токенов ТПУ, паспорт вызова — отдельная спецификация (решения уже приняты, см. `docs/SECURITY.md`).
- Анонимный доступ к `/mcp` — не делаем: MCP пока только для доверенных людей по ключу.
- Личные инструменты, запись, черновики, outbox и SSE-события.
- Консоль MCP-сервера, консоль rag и подключение новых API без кода (декларативные коннекторы) — `specs/console-and-connectors/spec.md`; здесь — только то, на что они опираются (контракт `Integration`, каталог, таблицы ключей и состояний).
- assistant: инструменты в чате, роутер и проверка ответа из `docs/LLM.md`, история диалогов — спецификация assistant (этап 4).
- rag: ночной ingest по таймеру, новые источники, глоссарий, исправление опечаток, rerank, сравнение моделей векторов.
- ml-server: сама нативная сборка и замер (на сервере), pii, адаптер GigaChat, `/health/upstream`.
- Открытое API расписания (будет не скоро); caddy, HTTPS, деплой нового набора сервисов на сервер.
- Вопросы к вузу (152-ФЗ, договор с Сбером, трансграничная передача, согласия) — после того как проект примут.
- Автотесты — пишет тестировщик команды; в шагах — сборка и ручная проверка; для тестировщика отмечено, что проверять.

## Ограничения
- **Безопасность.** `docs/SECURITY.md`; в этой спецификации — его раздел 13 и ключи доверенных клиентов. Секреты — файлы-секреты Docker, не переменные окружения и не репо. Аргументы вызовов в журнал и логи не пишем — только SHA-256. rasp.tpu.ru не нагружаем: кэш 10 мин, `Semaphore` на 4 одновременных запроса, свой `User-Agent` с контактом. Корпус rag — уже без ФИО; файлы модели векторов в git не кладём (D:\models, путь — env).
- **Не сломать живой деплой.** CI деплоит `ai-backend` на VPS при пуше в `main`. Имя образа и `docker/docker-compose.backend-java.yml` пока не меняем; при влитии в `main` бэкендерам нужно переименовать переменные в `.env` на сервере (`ML_BASE_URL`, `ML_API_KEY`, `ML_CHAT_MODEL`) — это строка в README и в описании PR. Поле `conversation_id` — сообщить клиентам (фронт на моках).
- **Архитектура.** `shared/tool-api`; `services/assistant`, `services/gateway`, `services/rag/{rag-api,rag-search,rag-app}`; `deploy/`. Внутри gateway — пакеты Spring Modulith `runtime`, `mcp`, `access`, `integrations.*`; `mcp` и интеграции знают только `runtime` и `tool-api`. Каждая проверка исполнителя — отдельный `ToolCheck`. gateway ходит в rag только по HTTP; в монолите `DocsSearch` станет вызовом метода.
- **Языки.** tool-api, gateway, assistant, rag-api — Java; rag-search, rag-app, ml-server — Kotlin. Один модуль — один язык.
- **Стек.** Java 25, Spring Boot 4.1.x, Spring AI 2.0.x (`spring-ai-starter-model-openai` в assistant; MCP-сервер gateway — свой, решение 19.10), Spring Modulith, JdbcClient, Flyway, Caffeine, jsoup, Postgres 17 + pgvector 0.8; rag — ONNX Runtime Java + DJL HuggingFace Tokenizers, multilingual-e5-small int8; ml-server — Kotlin 2.2, Ktor 3 CIO, GraalVM Native Image. Без JPA и Spring Security (появится в identity).
- **Память и CPU** (4 ядра / 4 ГБ на всё):
  - только нужные стартеры; `actuator` наружу — только `health`; без Micrometer-экспортеров;
  - `spring.threads.virtual.enabled=true`; без `synchronized` вокруг ввода-вывода — только `ReentrantLock`;
  - в кэше gateway — разобранные записи, а не HTML (неделя ≈ 84 КБ HTML против 2–3 КБ записей); кэши ограничены `maximumWeight`;
  - rag: модель загружается один раз, ONNX `intraOpNumThreads=2`; векторы `halfvec` + HNSW; загрузка корпуса — тот же образ с профилем `ingest`, `cpus: 2`, процесс завершается после работы;
  - Hikari по 5, `minimum-idle` 1; Tomcat `max-connections` 200, `accept-count` 50; запись журнала — без транзакции поверх вызова интеграции;
  - флаги JVM и лимиты — документ 22; жёсткий `cpus` у сервисов запросов не ставим.
- **Контент.** Описания инструментов и тексты ошибок-инструкций — `tools/descriptions.properties`, `messages.properties`; лимиты, таймауты, TTL, адреса — `application.yml`; режим, выключенные инструменты и ключи клиентов — таблицы БД; вопросы для замера rag — `services/rag/rag-search/src/main/resources/eval/questions.jsonl`.

## Как устроено (для ревью плана)
- `shared/tool-api` (Java, без Spring): `Integration` (`id`, `title`, `scopes()` → `ScopeInfo(name, title, description)`, `requiredSecrets()`, `health()`, `tools()`), `Tool<I, O>`, `ToolDescriptor` (name, group, description key, inputType, outputType, kind, sensitivity, cacheTtl, timeout, rateLimit), `CallContext` (userId?, roles, clientId, channel, scopes, requestId), `ToolResult` (sealed: `Ok`, `NeedsChoice`, `Denied`, `Failed`), `ToolAnswer` (`summary`, `data`, `hint`), `ErrorCode`.
- `services/rag/rag-search` (Kotlin, автоконфигурация для будущего монолита): `RagProperties`, `RagMigrations`, `Embedder` (ONNX + токенизатор, префиксы `query:`/`passage:`), `Chunker`, `HybridDocsSearch : DocsSearch` (один SQL: HNSW по `halfvec` + `ts_rank` по `russian`, RRF k=60), `CorpusIngest`, `RagEval`. Таблицы — документ 19.
- `services/rag/rag-app` (Kotlin): запуск, `InternalSearchController` (`POST /internal/search`, ключ gateway), профили `ingest`, `eval`.
- gateway `access` (мастер-ключ): `ApiClientStore` (таблица `gateway.api_clients`: id, владелец, `key_prefix` (первые 8 символов — чтобы узнать ключ в консоли), `key_hash`, scopes, `denied_tools`, `rate_limits` (jsonb), `read_only`, `enabled`, `expires_at`, `last_used_at`; кэш 30 с), `McpKeyFilter` (servlet-фильтр на `/mcp`: `Bearer` → SHA-256 → сравнение за постоянное время → атрибут запроса; иначе 401), `ApiKeyIssuer` (профиль `issue-key`: генерирует `mtpu_` + 32 случайных байта base64url, печатает один раз).
- gateway `catalog`: `IntegrationCatalog` (все бины `Integration`, проверка: имена инструментов уникальны, scopes не пересекаются, секреты на месте), `IntegrationState` (таблица `integration_state`, кэш 30 с), `CatalogController` (`GET /internal/catalog`).
- gateway `runtime`: `ToolRegistry` (инструменты от `IntegrationCatalog`; поддерживает добавление и удаление на ходу и рассылает событие `ToolsChanged` — на этом встанут коннекторы из `specs/console-and-connectors`), `ToolExecutor` (цепочка `ToolCheck` → кэш → вызов с таймаутом → журнал), `EnabledCheck` (инструмент, интеграция, режим), `AccessCheck` (scope ключа, `denied_tools`, `read_only`; WRITE пока отклоняется), `RateLimitCheck` (ключ + инструмент), `ArgumentsCheck`; `ToolCache`, `AuditLog`, `AuditRetention`, `GatewayControl`.
- gateway `mcp`: свой обработчик MCP Streamable HTTP без состояния (`McpEndpoint`, `McpProtocol`: initialize, ping, tools/list, tools/call; ответ — JSON, без сессий) — у сервера Spring AI один список инструментов на всех (решение 19.10); `tools/list` строится на каждый запрос по правам ключа, `InputSchemas` строит JSON Schema из record аргументов, `McpCallerResolver` (сейчас `ApiKeyCallerResolver`; в identity рядом встанет JWT агента на 30 мин), `McpTransportConfig` (атрибуты запроса → `McpTransportContext`).
- gateway `integrations.common`: `DateArgParser` (сегодня / завтра / послезавтра / дни недели / «19.10» / ISO, Asia/Tomsk), `SecretFiles`, маскирующий фильтр logback. `integrations.docs`: `DocsSearchTool`, `RagHttpDocsSearch`. `integrations.rasp`: `RaspClient`, `RaspSearchParser`, `RaspRefResolver`, `RaspWeekParser`, `GroupResolver`, `RaspFindTool`, `RaspWeekTool`, `RaspDayTool`.
- ml-server: `ktor-server-netty` → `ktor-server-cio`, плагин native, `META-INF/native-image/…`, `Dockerfile.native`, `certs/russian_trusted_root_ca.pem`, `README-native.md`.

## План (заполняет Claude, утверждаешь ты)
| # | Шаг | Файлы | Проверка |
|---|---|---|---|
| 1 ✅ | Этап 0: корень на Boot 4.1 и новая структура | `pom.xml` (parent Boot 4.1.x, Java 25, Kotlin 2.2, BOM Spring AI 2 и Modulith, модули `shared`, `services`); `shared/pom.xml`; `services/pom.xml`; `git mv` `backend-java/ai-backend` → `services/assistant`, `rag/rag-api` → `services/rag/rag-api`, `rag/pom.xml` → `services/rag/pom.xml`; удалить `backend-java/`, `help-tpu-*`; `.claude/verify.sh` | `mvn -q verify` из корня на JDK 25 |
| 2 ✅ | Этап 0: починка assistant | `services/assistant/pom.xml` (`spring-boot-starter-webmvc`, Spring AI 2), пакет `org.mytpu.assistant`, `application.yml` (`ML_BASE_URL`, `ML_API_KEY`, `ML_CHAT_MODEL`), `MetaEvent` (`conversation_id`), `ChatService` (импорты, Jackson 3) | assistant стартует; curl `/api/ai/chat` и `/chat/stream` против ml-server (мок) — те же события, что до переноса |
| 3 ✅ | Этап 0: Dockerfile и CI | `deploy/docker/spring.Dockerfile` (`ARG MODULE`, extract, AOT-тренировка, флаги), `.github/workflows/ci-cd-ai-backend.yml` (новые пути, JDK 25, тот же образ), `.github/workflows/ci-build.yml` | `docker build --build-arg MODULE=services/assistant` проходит; образ стартует; workflow-файлы — проверка синтаксиса (`actionlint` в Docker); запуск CI — после твоего пуша |
| 4 ✅ | shared/tool-api | `shared/tool-api/…/{Integration,ScopeInfo,HealthStatus,Tool,ToolDescriptor,CallContext,ToolResult,ToolAnswer,Choice,ToolException,ErrorCode,Channel,Kind,Sensitivity}.java` | `mvn -q -pl shared/tool-api install` |
| 5 ✅ | rag: БД и схема (+ каркас `rag-app`, чтобы прогнать миграции) | `deploy/compose.dev.yml` (pgvector/pgvector:pg17 на 127.0.0.1:5434, БД и пользователи `gateway`, `rag`, `assistant`), `deploy/postgres/init.sql`; `services/rag/rag-search/pom.xml`, `RagProperties.kt`, `RagMigrations.kt`, `RagSearchAutoConfiguration.kt`, `db/rag/V1__init.sql`, `V2__schema.sql` | `\dt rag.*` — все таблицы; повторный запуск — без изменений; пользователь `gateway` не видит БД `rag` |
| 6 ✅ | rag: модель векторов и загрузка корпуса | `embed/OnnxEmbedder.kt`, `ingest/{Chunker,DatasetReader,DocumentStore,CorpusIngest,SourceDocument}.kt`; `rag-app/{IngestRunner,ProcessMemory}.kt`, профиль `ingest`; `tools/rag/trim_e5_vocab.py` (урезанный словарь); `RAG_MODEL_DIR` (D:\models\e5-small-en-ru-int8) | ingest проходит: 126 документов, 362 раздела, 698 кусков = 698 векторов; ПК, контейнер, 2 ядра: 62 с, пик — лимит 380 МБ (поэтому `rag-ingest` — 512 МБ) |
| 7 ✅ | rag: гибридный поиск и `/internal/search` | `search/{HybridDocsSearch,QueryText,RagEval}.kt`, `db/rag/V3__hnsw_e5_small_en_ru.sql`, `eval/questions.jsonl` (46 вопросов: 37 с ответом, 9 без); `rag-app/{InternalSearchController,InternalKeyFilter,EvalRunner}.kt` | без ключа и с чужим → 401, с ключом — разделы со ссылками; `eval` (ПК): hit@3 86,5 %, hit@5 89,2 %, ложных «нашёл» 0 %, p95 23 мс |
| 8 ✅ | Каркас gateway и БД | `services/gateway/pom.xml`, `GatewayApplication.java`, `application.yml`, `db/migration/V1__gateway.sql` (mode, tool_state, integration_state, audit, api_clients) | `/actuator/health` = UP; пять таблиц |
| 9 ✅ | Каталог интеграций и мастер-ключ | `catalog/{IntegrationCatalog,IntegrationState,CatalogController}.java`; `access/{ApiClientStore,McpKeyFilter,ApiKeyIssuer,ApiClient}.java`; `integrations/example/` (профиль dev) | `issue-key` с неизвестным scope → отказ; ключ печатается один раз, в БД — хэш и префикс; `/mcp` без ключа / с чужим / с отключённым → 401 за ≤ 30 с; `GET /internal/catalog` показывает `example`; diff добавления `example` не трогает ядро |
| 10 ✅ | tool-runtime | `runtime/{ToolRegistry,ToolExecutor,ToolCache,AuditLog,AuditRetention,GatewayControl}.java`, `runtime/check/{ToolCheck,EnabledCheck,AccessCheck,RateLimitCheck,ArgumentsCheck}.java`, `runtime/SystemPingTool.java`, `tools/descriptions.properties`, `messages.properties` | сборка; список инструментов в логе старта; `ApplicationModules.verify()` в профиле dev |
| 11 ✅ | door-mcp (свой обработчик MCP, решение 19.10) | `mcp/{McpEndpoint,McpProtocol,McpResults,InputSchemas,McpCallerResolver,ApiKeyCallerResolver}.java` | два ключа — разный `tools/list`; запрещённый → `tool_not_allowed`; неверные аргументы → `invalid_arguments` с полем; журнал; `tool_disabled` за 0 с, выключенная интеграция за 29 с, `gateway_mode` за 30 с; 61-й вызов → `rate_limited`, другой ключ обслуживается; `system_echo` появляется и пропадает без перезапуска; GET → 405 |
| 12 ✅ | Общие части и docs_search | `integrations/common/{DateArgParser,CommonConfig}.java` (секреты — через configtree Spring, отдельный `SecretFiles` не понадобился), `logback-spring.xml` (маска); `integrations/docs/{DocsIntegration,DocsSearchTool,RagHttpDocsSearch,DocsProperties}.java` | `docs_search` «как получить справку об обучении» → `summary` с выдержкой и ссылкой; «рецепт борща» → `found=false` + `hint`; rag остановлен → `upstream_unavailable` без стектрейса, повтор из кэша — `cache_hit`, 0 мс; `DateArgParser` — 22 фразы верно |
| 13 ✅ | int-rasp: поиск и группы | `integrations/rasp/{RaspClient,RaspProperties,RaspSearchParser,RaspRefResolver,GroupResolver,RaspFindTool}.java` | `rasp_find` «403», «Иванов»; `GroupResolver` «8к31», «8 К 31» → группа или `needs_choice` |
| 14 ⏸ | int-rasp: неделя и день — **отложено 19.10** | — | названия дисциплин на rasp.tpu.ru скрыты защитой от сбора; ждём Открытое API или разрешение ТПУ (решение 19.10). Разметка недели (время, вид занятия, преподаватель, корпус, аудитория) и редирект на текущую неделю уже изучены — заметки в `services/gateway/README.md` |
| 15 ✅ | ml-server: подготовка натива | `D:\projects\ML-server`, ветка `feature/native-image`: `build.gradle.kts` (CIO, плагин native 1.1.12), `Application.kt` (CIO), `META-INF/native-image/ru.tpu/ml-server/reachability-metadata.json` (агент GraalVM 24, пути mock и openai), `Dockerfile.native` (GraalVM 24.0.2), `certs/russian_trusted_root_ca.pem`, `etc/ml-server-native.service`, `tools/sse_load.py`, `README-native.md` | `./gradlew build` с тестами; JVM на CIO: health, models, обычный ответ, SSE (11 событий + [DONE]), 401, 400, 4 потока при лимите 1 → 200 + 429×3; `nativeCompile` не запускался |
| 16 ✅ | Укрепление | `deploy/compose.dev.yml`: `secrets:` (`deploy/secrets/` в `.gitignore`, `deploy/secrets.example/`), сети `edge` / `core` / `data` / `egress`, `user`, `read_only`, `tmpfs`, `cap_drop`, `security_opt` | критерии «Безопасность»: `docker inspect`, `curl` изнутри rag наружу, `psql` под чужим пользователем, `grep` по логам |
| 17 ✅ | Замер на ПК (ориентир) | `deploy/compose.measure.yml` (NMT), `deploy/load/{mcp-load.py,measure.sh}`, `JDK_JAVA_OPTIONS` по сервисам | ПК, 50 клиентов × 5 мин: 55 564 вызова, ошибок 0, OOM и рестартов 0, секретов в логах 0; старт gateway 3,3 с, rag 4,1 с, assistant 0,9 с (AOT); простой: gateway 146 МиБ, rag 271, CPU < 0,2 %; пик: gateway 307 / 380, rag 375 / 380, assistant 117, postgres 120 — сумма 0,92 ГБ; кэш внутри шлюза 0–2 мс (журнал), путь через проброс портов Docker Desktop — p95 34 мс; куча gateway после полной сборки не измерена (в JRE-образе нет jcmd) |
| 18 ✅ | Связка, README, ревью | `services/{gateway,rag}/README.md`, строки в `docs/DECISIONS.md`, `.claude/verify.sh` | `verify.sh` зелёный; вместо `claude mcp add` (CLI на ПК нет, ваши настройки не трогал) — официальный MCP Inspector: initialize, tools/list, tools/call `docs_search`; arch-reviewer и security-reviewer — критичное исправлено (обход фильтров ключей), перепроверено: `/%69nternal`, `/internal;x`, `/%6Dcp` → 401 |
| 19 ✅ | Полная схема и архитектура (артефакт https://claude.ai/artifact/PqYqT8W6vaZz1QQKUtSFvz) | `helpdesk-ai/docs/handoff/23-shema-raboty.html` (+ артефакт): связная SVG-схема процессов, сетей и портов; пути запросов (MCP → расписание, MCP → документы, загрузка корпуса, будущие вход ТПУ и чат с роутером); кто кого чем вызывает; токены и секреты; карта модулей; память (ПК + место под цифры сервера); что дальше; раздел «почему это надёжно» для вуза | каждый блок схемы — реальный модуль или процесс из кода и compose; ссылка — в `docs/ARCHITECTURE.md` |

После каждого шага — гасить сервисы, Postgres, Maven/Gradle-демонов.
Шаг 15 (ml-server) от остальных не зависит.
Для тестировщика: разбор страниц расписания (сохранённые HTML), `DateArgParser`, `GroupResolver`, проверки исполнителя по порядку, `McpKeyFilter`, права ключа (scopes, `denied_tools`, `read_only`).

## Решено 19.10
1 — делаем весь этап 0 и чиним `ai-backend`; 2 — ветка `feature/gateway` от `feature/rag` (создана); 3 — Java; 4 — multilingual-e5-small;
5 — готового набора вопросов нет, составляю я; 6 — Открытого API не будет скоро, читаем публичные страницы;
7 — меряем на ПК как ориентир, факт — на сервере; 9 — натив только готовим, собираем на сервере (весь проект, скорее всего, на одном);
10 — паспорт вызова; 11 — без фонового продления; 12 — сайт 30 мин простоя, мобилка 7 дней, агент 30 мин;
13 — анонима нет, MCP — для доверенных по ключу; 14 — история чата 30 дней; 15 — имена с `_`;
16–20 — после принятия проекта; 21 — неизвестно, проверяем в identity; 22 — выход в интернет с сервера есть.

## Открытые вопросы
1. **Тесты разбора страниц расписания** (вопрос 7 прошлого списка остался без ответа). По умолчанию не пишу — отмечено для тестировщика.
2. **Абсолютный срок сессии сайта.** 30 минут — это срок простоя; абсолютный оставляю 14 дней (после — вход заново). Ок?
