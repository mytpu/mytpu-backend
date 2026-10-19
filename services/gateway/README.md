# gateway — MCP-шлюз «Мой ТПУ»

Единый вход к сервисам ТПУ для ИИ-агентов. Клиент знает только свой **мастер-ключ**; в ключе включены разрешённые API
(scopes), выключенные инструменты, лимиты, режим «только чтение» и срок. Настоящие ключи сервисов ТПУ остаются в шлюзе.
Безопасность — `docs/SECURITY.md`, инструменты под слабую модель — `docs/LLM.md`, решения — `docs/DECISIONS.md`.

## Что внутри (пакеты Spring Modulith)
| Пакет | Отвечает за |
|---|---|
| `catalog` | каталог интеграций (`Integration`), их включение (`integration_state`), `GET /internal/catalog` |
| `access` | мастер-ключи (`api_clients`, только SHA-256), фильтр `/mcp` и `/internal/**`, выдача ключа (`issue-key`) |
| `runtime` | реестр инструментов, исполнитель: проверки по порядку → кэш → вызов с таймаутом → журнал (`audit`, 180 дней) |
| `mcp` | MCP Streamable HTTP без состояния: `POST /mcp`, методы `initialize`, `ping`, `tools/list`, `tools/call` |
| `integrations.docs` | `docs_search` — поиск по документам через rag |
| `integrations.rasp` | `rasp_find` — аудитории, преподаватели, группы со ссылкой на расписание |
| `integrations.example` | образец интеграции, только при `mytpu.gateway.dev-tools=true` (`system_ping`) |
| `integrations.common` | разбор дат по-русски (`DateArgParser`), общие бины |

## Запуск
```bash
bash deploy/dev-secrets.sh                                           # секреты-файлы (один раз)
mvn -q package -DskipTests
RAG_MODEL_HOST_DIR=D:/models/e5-small-en-ru-int8 docker compose -f deploy/compose.dev.yml up -d --build
```
Шлюз: `http://127.0.0.1:8081/mcp`, здоровье: `/actuator/health`.

## Выдать мастер-ключ
```bash
docker compose -f deploy/compose.dev.yml run --rm --no-deps -T gateway \
  --spring.profiles.active=issue-key --owner="Фамилия И. О." --scopes=docs.read,rasp.read
```
Ключ печатается **один раз**. Параметры: `--roles=console.admin|console.viewer` (ключ консоли), `--deny=инструмент,…`,
`--rate=инструмент:число,…` (свой лимит в минуту), `--days=90` (срок), `--write` (снять «только чтение»).
Отозвать: `UPDATE gateway.api_clients SET enabled = false WHERE id = '…'` — действует за ≤ 30 с (в консоли — кнопкой).

## Подключить Claude Code
```bash
claude mcp add --transport http mytpu http://localhost:8081/mcp --header "Authorization: Bearer <мастер-ключ>"
```
Дальше в Claude Code: «где аудитория 403?», «как получить справку об обучении в ТПУ?».

## Настройки
| Что | Где |
|---|---|
| БД | `GATEWAY_DB_URL` (по умолчанию `jdbc:postgresql://postgres:5432/gateway`), пароль — секрет `gateway_db_password` |
| rag | `RAG_URL` (`http://rag:8080`), ключ — секрет `rag_internal_key` |
| Расписание | `mytpu.gateway.rasp.*`: адрес, User-Agent, таймауты, не больше 4 одновременных запросов к сайту, кэш поиска 10 мин |
| Режим и выключенные инструменты | таблицы `gateway.mode`, `gateway.tool_state`, `gateway.integration_state` (кэш 30 с) |
| Тексты | ошибки-инструкции — `messages.properties`, описания инструментов — `tools/descriptions.properties` |

## Добавить API кодом
1. Пакет `integrations.<id>`: бин, реализующий `org.mytpu.tool.api.Integration` (id, название, scopes, нужные секреты, инструменты).
2. Инструмент — `Tool<Вход, Выход>`; вход — record с аннотациями Bean Validation (`@NotBlank` = обязательное поле в JSON Schema).
3. Ответ — `ToolResult.ok(new ToolAnswer<>(summary, data, hint))`: готовая фраза + данные + подсказка (`docs/LLM.md`).
4. Описание и подсказки к полям — `tools/descriptions.properties` (`<инструмент>.description`, `<инструмент>.arg.<поле>`).
5. Секреты — файлы в `/run/secrets/<имя>`; имя — в `requiredSecrets()`; без секрета интеграция «не настроена» и не публикуется.
Ядро (`runtime`, `mcp`, `access`) при этом не меняется — проверки, лимиты, кэш и журнал достаются новому инструменту даром.

## Расписание: что отложено и почему
`rasp_day` / `rasp_week` отложены (решение 19.10): на rasp.tpu.ru названия дисциплин скрыты — страница расшифровывает их
скриптом с ключом, полученным от сервера; это защита от автоматического сбора, и без разрешения ТПУ мы её не обходим.
Что уже известно для будущего шага (Открытое API или разрешение):
- `/redirect/kalendar.html?hash=…` → редирект на `/<тип>_<id>/<год>/<неделя>/view.html` текущей учебной недели
  (`gruppa` — группа, `sg` — поток подгрупп, `user` — преподаватель, `pomeschenie` — аудитория);
- таблица `table.timetable`: строки — пары (`td[title="08:30 - 10:05"]`), столбцы — дни (заголовки «19.10.26 понедельник»);
- в ячейке открыто: вид занятия (`<b title="Лекция">ЛК</b>`), преподаватель (ссылка `/user_…`), корпус (`/sooruzhenie_…`),
  аудитория (`/pomeschenie_…`); чётность недели — в шапке страницы.

## При влитии в `main` (для бэкендеров)
- assistant переехал в `services/assistant`; образ по-прежнему `ghcr.io/mytpu/ai-backend`, деплой — тот же workflow.
- Переменные assistant на сервере: `ML_BASE_URL` (**с `/v1`**, например `https://…/v1`), `ML_CHAT_MODEL`; ключ — файл-секрет
  `ml_api_key` или переменная `ML_API_KEY`. Старые `SPRING_AI_OPENAI_*` больше не читаются.
- В событии `meta` поле `conservation_id` переименовано в `conversation_id` — фронту и мобилке.
