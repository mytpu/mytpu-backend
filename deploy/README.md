# Развёртывание

- `compose.dev.yml` — стенд на ПК: все сервисы, сети `edge`/`core`/`data`, секреты-файлы (`bash deploy/dev-secrets.sh`).
- `compose.server.yml` — сервер команды (VPS): пока только `assistant`, с тем же укреплением. Полное развёртывание
  за caddy на ВМ ТПУ — спецификация `deploy-server`.

## Сервер: перенос со старого `.env` на файлы (один раз, делает владелец VPS)
Раньше CI запускал `docker-compose.backend-java.yml` с `env_file: .env` (секреты в переменных окружения, образ `latest`).
Теперь секреты — файлами, образ — по SHA коммита. В папке деплоя на сервере (`~/SL`):

1. `mkdir -m 700 secrets`
2. Ключ к ml-server — в файл `secrets/ml_api_key`: без кавычек и без перевода строки в конце (`printf '%s' 'ключ' >
   secrets/ml_api_key`), владелец — uid контейнера, только чтение: от root `chown 10001:10001 secrets/*` и
   `chmod 400 secrets/*` (читать файл может только сервис; `644` открыл бы ключ всем пользователям хоста).
   Значение берётся из старого `.env` (там оно могло лежать под перепутанным именем `SPRING_AI_OPENAI_BASE_URL`).
3. `secrets/assistant_client_key` — пустой файл (ключ клиента чата выключен) или случайная строка, если `/api/ai/chat`
   доступен снаружи; тогда то же значение — во фронтенд (заголовок `X-Assistant-Key`).
4. `server.env` — только несекретное:
   ```
   ML_BASE_URL=https://<адрес ml-server>/v1
   ML_CHAT_MODEL=tpu-assistant
   TRUSTED_PROXIES=
   ```
   `TRUSTED_PROXIES` — адрес обратного прокси, если он стоит перед assistant на этом же сервере (иначе пусто). Если прокси
   есть, а переменная пуста, все пользователи окажутся «одним адресом» (шлюз docker-сети) и поделят одно окно
   `per-ip-per-minute`.
5. Старый `.env` удалить после первого успешного деплоя.
6. В настройках репозитория GitHub (Settings → Variables) завести `ASSISTANT_DEPLOY_ENABLED=true` — до этого CI собирает,
   проверяет и выгружает образ, но на сервер не выкладывает.
7. Settings → Environments → `production`: Required reviewers (кто подтверждает выкладку) и перенести туда секреты
   `VPS_HOST`, `VPS_USER`, `VPS_KEY`, `GHCR_USERNAME`, `GHCR_TOKEN` — тогда их видит только подтверждённая выкладка.

## Как CI выкладывает
`build` (сборка и тесты assistant, образ, trivy) → выгрузка в GHCR с тегом = SHA → `deploy` (только при
`ASSISTANT_DEPLOY_ENABLED=true`): копирование `compose.server.yml`, остановка старого контейнера `ai-backend`,
`up -d` нового, проверка `127.0.0.1:8080/actuator/health` до 60 с. Не поднялся → запуск прошлого тега (файл
`assistant.tag` на сервере) и красный CI. Секреты GitHub (`VPS_*`, `GHCR_*`) передаются в скрипт переменными окружения.

## Консоль администратора (gateway `/admin/api/**`)
- Отвечает только с сетей `ADMIN_ALLOWED_NETWORKS` (по умолчанию localhost), остальным — 404. В Docker запрос с хоста
  приходит в контейнер шлюза с адреса шлюза сети `edge`, а не с `127.0.0.1`: узнать его —
  `docker network inspect mytpu-dev_edge -f '{{(index .IPAM.Config 0).Gateway}}'` и вписать ровно его (`/32`),
  не всю подсеть Docker — иначе консоль откроется соседним контейнерам.
- Через прокси (caddy) консоль не публиковать: закрыть `/admin/api/*` в его конфиге; запрос от доверенного прокси без
  `X-Forwarded-For` консоль не пропускает.
- Ключ консоли: `issue-key --roles=console.admin` (или `console.viewer` — только чтение).
- Секреты консоли: `connector_vault_key` (сейф секретов коннекторов), `rag_admin_key` (шлюз → rag `/admin`) —
  `bash deploy/dev-secrets.sh` создаёт их на стенде.

## База стран для ключей «только РФ»
- Скачать базу: `https://download.db-ip.com/free/dbip-country-lite-YYYY-MM.mmdb.gz` (где `YYYY-MM` — текущий месяц).
- Распаковать как `dbip-country-lite.mmdb` в папку `GEO_DB_HOST_DIR` (по умолчанию `../geo` рядом с репозиторием).
- Обновлять раз в месяц (перезапуск gateway).
- Лицензия CC BY 4.0 — атрибуция «IP Geolocation by DB-IP» (ссылка https://db-ip.com) в документации и в подвале консоли.
- `KEY_GEO_ENABLED=true` без файла — gateway не стартует.
- `TRUSTED_PROXIES` обязателен: без него шлюз видит адрес прокси или моста Docker (частная сеть) и страну не проверяет
  (при старте — WARN). Указать адрес caddy или шлюза сети `edge` (`docker network inspect … -f '{{(index .IPAM.Config 0).Gateway}}'`).
- Перед первым включением: миграция V7 ставит всем выданным ключам `person` и `server` политику `ru_only`. Ключи облачных
  агентов (ChatGPT-коннекторы, Claude в браузере) иначе приостановятся на первом вызове — найти их и перевести заранее:
  `issue-key --resume=<id> --geo=any --geo-reason="облачный агент …"` или консоль; список — по `gateway.key_events`
  (подсети вне России) или у владельцев ключей.

