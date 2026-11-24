# Стенд «Мой ТПУ» на сервере ТПУ и HTTPS-консоль

Сервер сам получает проверенные сборки из GitHub и обновляет стенд. На компьютере владельца Docker не нужен:
образы собирает GitHub Actions (проверки → образы по digest → смоук → релиз), сервер только скачивает готовое
по исходящему HTTPS.

```
GitHub Actions (main)                      Сервер ТПУ (сеть ТПУ)
  проверки, образы gateway/rag/assistant  →  mytpu-update.timer (раз в 15 мин)
  trivy, смоук контейнеров и маршрутов       mytpu-update.sh: релиз → образы по digest → compose up
  GitHub Release: манифест + compose +       → проверки здоровья → переключение статики и маршрутов
  Caddyfile + console.tar.gz                 → проверка маршрутов, иначе откат комплекта
```

- Образы: `ghcr.io/mytpu/mytpu-backend/{gateway,rag,assistant}` — по digest из манифеста, платформы `amd64` и `arm64`.
- Статика консоли: `console.tar.gz` с SHA-256 в манифесте — обновляется и откатывается вместе с API.
- Конфиг маршрутов: `Caddyfile` из того же релиза, проверяется `caddy validate` до установки.
- Параметры площадки (`/etc/mytpu/server.env`) и секреты (`/etc/mytpu/secrets/`) в релиз не входят.

## Что нужно от площадки

1. **Исходящий HTTPS** с сервера: `api.github.com`, `objects.githubusercontent.com`, `ghcr.io` (+ `pkg-containers.githubusercontent.com`).
   Входящие SSH/SCP для обновления не нужны — SSH остаётся административным доступом из сети ТПУ.
2. **`uname -m`** на сервере: образы собраны для `x86_64` и `aarch64`, выполнять ничего не нужно, но подтвердить архитектуру.
3. **Где завершается HTTPS** для `mytpu.red.tpu.ru`:
   - `MYTPU_TLS=upstream` — HTTPS завершает входной прокси ТПУ, Caddy слушает `127.0.0.1:8443`;
   - `MYTPU_TLS=caddy-cert` — Caddy сам отдаёт сертификат (`MYTPU_TLS_CERT`/`MYTPU_TLS_KEY`, чтение для `caddy`);
   - `MYTPU_TLS=letsencrypt` — домен указывает на этот сервер, открыты 80/443.
   В режиме `upstream` клиентский адрес до Caddy не доходит: **ограничение по сетям делает входной прокси**, а Caddy
   закрывает консоль от всех, кроме адреса прокси. Доверие к `X-Forwarded-For` на порту консоли не расширяется.
4. **Сети администраторов** — диапазоны, которым открыты `/console/` и `/admin/api/*` (в `CONSOLE_ALLOWED_NETWORKS`).
   Пока параметр пуст, оба маршрута отвечают 404 — безопасное начальное состояние.

## Первичная установка (один раз, от root)

```bash
# 1. Копия репозитория на сервере (для установки нужны deploy/ и deploy/dev-secrets.sh)
git clone https://github.com/mytpu/mytpu-backend.git ~/mytpu && cd ~/mytpu
git checkout main

# 2. Зависимости проверяются и устанавливаются только на сервере: docker + compose v2, curl, python3, tar,
#    flock, sha256sum, caddy, systemd. Установщик сам проверит их наличие
sudo bash deploy/tpu-server/install.sh --check     # показать план, ничего не меняя
sudo bash deploy/tpu-server/install.sh             # скрипты, каталоги, секреты, unit-файлы, import в Caddy

# 3. Параметры площадки: HTTPS-режим, сети консоли, адрес ml-server, пути к модели и базе стран
sudo nano /etc/mytpu/server.env                    # права 0600, владелец root

# 4. Учётные данные — в закрытом каталоге (0700). Контейнер читает свой файл с режимом 0644.
sudo sh -c 'printf "%s" "<ключ ml-server>" > /etc/mytpu/secrets/ml_api_key'
# если репозиторий и пакеты закрыты: файл только для root, токен только на чтение
sudo install -m 0600 /dev/null /etc/mytpu/secrets/github_token
sudoedit /etc/mytpu/secrets/github_token
# GHCR_USER — имя владельца токена, в /etc/mytpu/server.env

# 5. Данные, которые в релиз не входят: модель векторов e5 (ONNX) и база стран DB-IP
sudo mkdir -p /var/lib/mytpu/models/e5-small-en-ru-int8 /var/lib/mytpu/geo
sudo cp -a <каталог-модели>/. /var/lib/mytpu/models/e5-small-en-ru-int8/
sudo cp dbip-country-lite.mmdb /var/lib/mytpu/geo/

# 6. Первый запуск обновления: скачать релиз и поднять стенд
sudo systemctl start mytpu-update.service
journalctl -u mytpu-update.service -f
```

После первого запуска:

```bash
curl -sS https://mytpu.red.tpu.ru/actuator/health                       # {"status":"UP"}
curl -sS -o /dev/null -w '%{http_code}\n' https://mytpu.red.tpu.ru/mcp  # 401 без ключа
curl -sS -o /dev/null -w '%{http_code}\n' https://mytpu.red.tpu.ru/internal/tools  # 404
```

Открыть консоль администраторам:

```bash
sudoedit /etc/mytpu/server.env                # CONSOLE_ALLOWED_NETWORKS по правилам выбранного TLS-режима
sudo /usr/local/bin/mytpu-update.sh --config    # пересобрать маршруты без обновления версии
sudo docker compose --env-file /var/lib/mytpu/state/compose.env -f /var/lib/mytpu/state/compose.yml \
  run --rm --no-deps -T gateway --spring.profiles.active=issue-key --owner=console-admin --roles=console.admin
```

Дальше в браузере `https://mytpu.red.tpu.ru/console/`: вход ключом `console.admin` (изменения) или `console.viewer`
(только чтение). Прямой переход на вложенную страницу и F5 работают, HTTP перенаправляется на HTTPS.
Ключ выдаётся один раз в сеансе администратора. После проверки стенда включите таймер командой
`sudo systemctl enable --now mytpu-update.timer`.

## Обновление

Workflow выпускает релизы только из защищённой ветки `main`. Перед первым запуском убедитесь, что в GitHub для неё действует правило защиты ветки или ruleset.

Таймер проверяет канал раз в 15 минут после загрузки сервера (`OnBootSec=5min`) и обновляет стенд, если в канале
появился новый релиз. Один запуск — один комплект версий.

```bash
sudo systemctl start mytpu-update.service        # обновить сейчас
/usr/local/bin/mytpu-update.sh --check           # что в канале и что активно (ничего не меняет)
sudo /usr/local/bin/mytpu-update.sh --tag v20261125-742d583   # конкретная версия
sudo systemctl stop mytpu-update.timer           # пауза обновлений (systemctl start — включить)
journalctl -u mytpu-update.service -n 200        # журнал: что скачано, проверено и переключено
```

Порядок одного запуска: загрузка файлов релиза → `sha256sum -c` → сверка манифеста (digest всех трёх образов, хеш
статики) → `docker compose config` → загрузка образов по digest → `compose up` → здоровье postgres, rag, gateway,
assistant → переключение статики, compose и маршрутов → проверка маршрутов. Ошибка на любом шаге возвращает
предыдущий комплект: образы, статику и конфигурацию маршрутов. Повтор уже активной версии пропускается,
параллельный запуск блокируется (`flock`).

## Откат

```bash
sudo /usr/local/bin/mytpu-update.sh --rollback   # предыдущий активный комплект: образы, статика, маршруты
```

Откат образов **не** откатывает данные. Если релиз меняет схему БД несовместимо с предыдущим, откат образов
недопустим — нужна отдельная процедура миграции; такие релизы помечаются в описании релиза.

## Прокси, адрес клиента и лимиты тела

- `upstream`: Caddy слушает **только `127.0.0.1`** (`bind 127.0.0.1`), поэтому до него доходит лишь входной
  прокси на том же хосте. Он передаёт исходный `Host`, заменяет входной `X-Forwarded-For` проверенным адресом
  клиента, делает HTTP → HTTPS и ограничивает доступ к `/console`, `/console/**`, `/admin/api` и `/admin/api/**`.
  После проверки этого ограничения из двух сетей укажите `CONSOLE_ALLOWED_NETWORKS="127.0.0.1/32"`.
  До проверки оставьте список пустым. Caddy передаёт проверенный адрес шлюзу и assistant.
- `caddy-cert` и `letsencrypt`: клиент подключается напрямую, и Caddy сам подставляет адрес соединения
  (`header_up X-Forwarded-For {remote_host}`) — подделанный заголовок клиента до шлюза не доходит.
  Сетевое ограничение консоли здесь применяет сам Caddy (`CONSOLE_ALLOWED_NETWORKS`).
- На порт консоли `18090` заголовки адреса **не передаются** (`header_up -X-Forwarded-For`): шлюз проверяет
  адрес соединения, а не заголовок.
- Тело запроса: `/admin/api/**` — до 24 МБ (документ rag до 20 МБ проходит), `/mcp` и `/api/ai/*` — 2 МБ.
  Ограничение задаётся в каждом маршруте: общий лимит на сайте отменил бы загрузку через консоль.

## Установка рядом с работающим Caddy

`install.sh` не перезаписывает `/etc/caddy/Caddyfile`: он дописывает одну строку `import mytpu.caddy`
(повторная установка её не дублирует), сохраняет чужие сайты, импорты и глобальные настройки и проверяет
**итоговый** конфиг целиком через `caddy validate`. Если проверка или `systemctl reload caddy` не прошли,
прежние файлы возвращаются и Caddy перечитывает их — стенд не остаётся с нерабочим Caddy.

Права: `/etc/mytpu` и `/etc/mytpu/secrets` — `700`, файлы секретов — `600` у root. Демон Docker читает их сам и
монтирует в контейнеры, где их видят пользователи сервисов (postgres и UID `10001`). Токен GitHub
(`secrets/github_token`) в контейнеры не монтируется. Проверки: `test/install-scenarios.sh` и смоук в CI.

## Учётные данные для закрытого репозитория

- `secrets/github_token` — fine-grained PAT только на чтение: `Contents: read` (файлы релиза через API
  `release assets`, `Accept: application/octet-stream`) и `Packages: read` (образы GHCR). Файл `0600`, владелец root.
- `GHCR_USER` в `server.env` — владелец токена; без него `docker login` не выполняется и закрытые образы не скачаются.
- Публикация в CI идёт только из `main`: ручной запуск с другой ветки прогоняет проверки, но ничего не публикует.
- Первый ключ консоли выдаётся на сервере после первого обновления (ключ показывается один раз):

```bash
sudo docker compose -f /var/lib/mytpu/state/compose.yml --env-file /var/lib/mytpu/state/compose.env \
     exec -T gateway java -cp /app/app.jar -Dloader.main=org.mytpu.gateway.access.IssueKeyCli \
     org.springframework.boot.loader.launch.PropertiesLauncher --roles=console.admin
```

Если CLI в образе недоступен, выдайте ключ через консоль после входа первым административным ключом,
полученным при установке стенда на ПК (`deploy/test-server/README.md`).

## Откат и его порядок

`mytpu-update.sh --rollback` возвращает предыдущий **комплект**: compose, статику консоли, скрипт инициализации БД,
образы по digest из манифеста и конфиг маршрутов. Версия записывается только после проверки здоровья
postgres, rag, gateway, assistant и маршрутов — при сбое активной остаётся прежняя версия. Активный и предыдущий
комплекты не удаляются при чистке старых релизов (хранятся последние три плюс они).

## Что где лежит

| Путь | Что |
|---|---|
| `/etc/mytpu/server.env` | параметры площадки (0600 root) — секретов в нём нет |
| `/etc/mytpu/secrets/` | ключи и пароли файлами (0600 root), в git и образы не попадают |
| `/etc/mytpu/postgres-init/` | создание БД и пользователей на пустом томе |
| `/etc/caddy/mytpu.caddy` | конфиг маршрутов, подключён в `/etc/caddy/Caddyfile` строкой `import mytpu.caddy` |
| `/var/lib/mytpu/releases/<версия>/` | комплекты релизов (последние три) |
| `/var/lib/mytpu/state/` | активная версия, прошлая версия, активный `compose.yml` и `compose.env` |
| `/var/lib/mytpu/console/` | статика консоли, которую раздаёт Caddy |
| `/var/lib/mytpu/{models,geo,dataset}` | модель векторов, база стран, корпус для ingest |
| `/var/lib/mytpu` (тома) | `mytpu-pgdata`, `mytpu-rag-files` — имена постоянные, переживают переустановку |

```bash
sudo docker compose -f /var/lib/mytpu/state/compose.yml --env-file /var/lib/mytpu/state/compose.env ps
sudo docker compose -f /var/lib/mytpu/state/compose.yml --env-file /var/lib/mytpu/state/compose.env logs -f gateway
sudo docker compose -f /var/lib/mytpu/state/compose.yml --env-file /var/lib/mytpu/state/compose.env \
     --profile ingest run --rm rag-ingest     # разовый ingest тем же образом rag, что и поиск
```

## Проверки и ограничения

- В CI (`.github/workflows/release-tpu.yml`): `mvn verify`, сборка консоли, trivy, смоук готовых контейнеров на
  настоящем compose, `caddy validate` и проверка маршрутов (`deploy/tpu-server/smoke-routes.sh`,
  `smoke-stack.sh`), затем манифест и GitHub Release. Публикация начинается только после всех проверок.
- Локально без Docker: `bash deploy/tpu-server/test/update-scenarios.sh` (первый запуск, повтор, гонка, обрыв
  загрузки, ошибка здоровья, манифест без digest, откат) и `bash deploy/tpu-server/smoke-routes.sh` при наличии Caddy.
- **На сервере подтвердить:** DNS и TLS `mytpu.red.tpu.ru`, доступ с разрешённой и внешней сети, вход
  `console.admin`/`console.viewer` и отказ на запись для viewer, digest запущенных образов
  (`docker inspect --format '{{index .Config.Image}}'`), пробное обновление с откатом.
- Первый запуск создаёт БД и пользователей сервисов на пустом томе. Если данные уже есть (прежний стенд), до первого
  запуска укажите его тома: `MYTPU_PGDATA_VOLUME` и `MYTPU_RAG_FILES_VOLUME` в `/etc/mytpu/server.env`
  (`docker volume ls` покажет имена). Имя проекта compose — `MYTPU_PROJECT` (по умолчанию `mytpu`).
- Версия PostgreSQL закреплена (`POSTGRES_IMAGE`), обновлением она не меняется: миграция БД — отдельная процедура.
- rag поднимается только с моделью векторов; без каталога `RAG_MODEL_HOST_DIR` контейнер перезапускается, обновление
  откатывается по проверке здоровья.
