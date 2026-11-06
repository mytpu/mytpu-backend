# Развёртывание стенда «Мой ТПУ» на тестовом сервере Сбера

Инструкция по развёртыванию и проверке предпрод-стенда (postgres, rag, gateway, assistant) на тестовом сервере Ubuntu 24.04 рядом с работающим ml-server и системным Caddy.

---

## 1. Один раз (владелец, с правами администратора)

Команды выполняет владелец сервера с правами `sudo`.

### Установка Docker Engine (Ubuntu 24.04 Noble)

```bash
# Обновление пакетов и установка зависимостей
sudo apt-get update
sudo apt-get install -y ca-certificates curl

# Добавление официального GPG-ключа Docker
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

# Добавление репозитория Docker в apt
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu noble stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# Установка Docker Engine, CLI и Compose плагина
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
```

### Добавление пользователя в группу docker

> Внимание: добавление в группу `docker` предоставляет пользователю эквивалент root-прав на сервере через доступ к сокету Docker. Для тестового стенда это допустимо.

```bash
sudo usermod -aG docker $USER
```
После выполнения команды нужно перелогиниться по SSH или выполнить `newgrp docker`, чтобы права вступили в силу.

### Подключение сайта в Caddy

Системный Caddy не имеет доступа в домашний каталог пользователя (`~/mytpu`, права 750). Поэтому файл сайта копируется в `/etc/caddy/`:

```bash
# 1. После первого запуска upload.sh скопировать сгенерированный сайт
sudo cp /home/$USER/mytpu/deploy/test-server/mytpu.caddy /etc/caddy/mytpu.caddy
sudo chmod 644 /etc/caddy/mytpu.caddy

# 2. Добавить строку импорта в конец системного Caddyfile
echo "import mytpu.caddy" | sudo tee -a /etc/caddy/Caddyfile

# 3. Проверить синтаксис конфигурации
sudo caddy validate --config /etc/caddy/Caddyfile

# 4. Применить конфигурацию без разрыва соединений
sudo systemctl reload caddy
```

### Закрыть ml-server от интернета (основной Caddyfile)

`upload.sh` кладёт на сервер `Caddyfile.main` — основной `/etc/caddy/Caddyfile`, где сайт ml-server отвечает только
помощнику стенда (сеть edge) и самому серверу, остальным — 403. Применить (старый файл сохраняется рядом):
```bash
sudo cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.bak-20261101
sudo cp /home/$USER/mytpu/deploy/test-server/Caddyfile.main /etc/caddy/Caddyfile
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
```
Вернуть как было: `sudo cp /etc/caddy/Caddyfile.bak-20261101 /etc/caddy/Caddyfile` и `sudo systemctl reload caddy`.

### Обновление сайта Caddy (после изменения `Caddyfile.mytpu`)

`upload.sh --files-only` кладёт новый `mytpu.caddy` на сервер; применить:
```bash
sudo cp /home/$USER/mytpu/deploy/test-server/mytpu.caddy /etc/caddy/mytpu.caddy
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

### Установка ключа ml-server

После первого запуска `up.sh` (когда создастся каталог `~/mytpu/deploy/secrets/`):
```bash
cd ~/mytpu/deploy
printf '%s' '<настоящий_ключ_ml-server>' > secrets/ml_api_key
chmod 644 secrets/ml_api_key
touch secrets/.ml_api_key_set
```

### Ключ помощника к шлюзу (роль `chat.service`, спецификация 5)

Помощник зовёт инструменты (`docs_search`, `rasp_find`) через вход шлюза `/chat/tools` по внутренней сети `core`
(подсеть `CORE_SUBNET`, по умолчанию `172.31.241.0/24`). Ключ выдаётся один раз после запуска `up.sh`; пока файл
`secrets/assistant_gateway_key` пустой, помощник отвечает шаблонами без инструментов. Ключ сразу пишется в файл
секрета и в консоль не выводится:
```bash
cd ~/mytpu/deploy
# umask в скобках: файл с ключом создаёт эта оболочка, umask внутри issue-key.sh на него не действует
(umask 077; bash test-server/issue-key.sh --owner="Помощник (чат)" --roles=chat.service --kind=server \
    --networks=172.31.241.0/24 --scopes=docs.read,rasp.read > ~/keys/assistant-gateway.out)
grep -o '^mtpu_[A-Za-z0-9]*' ~/keys/assistant-gateway.out | tr -d '\n' > secrets/assistant_gateway_key
chmod 644 secrets/assistant_gateway_key
test -s secrets/assistant_gateway_key && echo "ключ записан" && shred -u ~/keys/assistant-gateway.out
docker compose --env-file test-server/server.env -f compose.dev.yml -f compose.test-server.yml \
    up -d --no-build --force-recreate assistant
```

### Переход на фиксированную подсеть `core` (один раз, 06.11.2026)

Сеть `core` получила фиксированную подсеть; уже созданную сеть Docker сам не меняет — её пересоздаёт остановка стека
(данные в томах сохраняются, `--purge` не нужен):
```bash
cd ~/mytpu/deploy && bash test-server/down.sh && bash test-server/up.sh
```

---

## 2. Каждый раз (с ПК разработчика)

### Настройка переменных окружения

```bash
cp deploy/test-server/server.env.example deploy/test-server/server.env
# Отредактируйте deploy/test-server/server.env: укажите адрес SERVER, MYTPU_HOST, ML_BASE_URL и локальные пути
```

### Сборка и упаковка образов

Запуск из Git Bash на Windows в корне проекта:
```bash
# Полная сборка jar и образов с сохранением архива:
bash deploy/test-server/pack.sh

# Если jar и образы уже собраны ранее, только повторная упаковка в архив:
bash deploy/test-server/pack.sh --skip-build

# Без сети к базе trivy (не для выкладки — образы не проверены):
bash deploy/test-server/pack.sh --skip-build --skip-scan
```

`pack.sh` берёт pgvector по digest (переменная `PGVECTOR_DIGEST`; тот же digest — в `.github/workflows/ci-build.yml`),
проверяет все четыре образа trivy (в Docker, кэш базы — `TRIVY_CACHE_DIR`, по умолчанию `D:/claude-tmp/trivy-cache`):
критичная уязвимость с исправлением останавливает упаковку, HIGH — только в отчёте. Рядом с архивом пишется
`mytpu-images.tar.gz.sha256`.

### Перенос на сервер

```bash
# Перенос всех файлов, архива образов и данных (модель, корпус, GeoIP при первом запуске):
bash deploy/test-server/upload.sh

# Только образы (если изменился код сервисов); архив сверяется по sha256 до и после переноса:
bash deploy/test-server/upload.sh --images-only

# Только конфиги и скрипты (быстрое обновление настроек):
bash deploy/test-server/upload.sh --files-only

# Принудительная перезапись модели, корпуса и базы стран:
bash deploy/test-server/upload.sh --force-data
```

### Запуск сервисов на сервере

```bash
# Запуск основного стека (postgres, rag, gateway, assistant):
ssh <SERVER> 'cd ~/mytpu/deploy && bash test-server/up.sh'

# Запуск с индексацией корпуса документов (rag-ingest):
ssh <SERVER> 'cd ~/mytpu/deploy && bash test-server/up.sh --ingest'
```

---

## 3. Ключи и тестовые данные

Ключи выдаёт `issue-key.sh` (на сервере, в `~/mytpu/deploy`); вывод с ключом — сразу в файл, ключ печатается один раз:
```bash
bash test-server/issue-key.sh --owner=console-admin --roles=console.admin > ~/keys/console.out
bash test-server/issue-key.sh --owner="Тестовый агент" --scopes=docs.read,rasp.read > ~/keys/mcp.out
bash test-server/issue-key.sh --owner="Сервис" --kind=server --networks=203.0.113.0/24 --scopes=docs.read
bash test-server/issue-key.sh --decoy --owner="ловушка: репозиторий X" --decoy-group=cli_…,cli_…
```

Наполнить стенд для проверки консоли (ключи всех типов, заявки, события: новая сеть, чужая сеть, вызов из-за
рубежа, ловушка с приостановкой группы) — `bash test-server/seed.sh`. Ключи — в `~/keys/seed/` (права 600);
ключ консоли «только чтение» — `~/keys/seed/viewer.key`. Повторный запуск создаёт ещё один набор.
Адреса клиентов имитируются заголовком `X-Forwarded-For` в запросах с самого сервера (они приходят от доверенного
прокси); снаружи через Caddy подменить адрес нельзя.

### Обновить данные для показа (demo-data.sh)

Скрипт `test-server/demo-data.sh` убирает старые тестовые данные шлюза и заводит правдоподобный набор для демонстрации консоли администратора (клиенты, заявки, события, ловушка, приостановка по геополитике, вызовы расписания и документов).

**Запуск на сервере** (из каталога `~/mytpu/deploy`):
```bash
# Базовый запуск (автоматически сохраняются мастер-ключи из ~/keys/console.out, ~/keys/manager-console.out, ~/keys/mcp.out):
bash test-server/demo-data.sh

# С сохранением дополнительных ID ключей (через запятую):
KEEP_IDS="cli_aaa111,cli_bbb222" bash test-server/demo-data.sh
```

**Что сохраняется**:
- Ключи, перечисленные в переменной `KEEP_IDS` и найденные в `~/keys/console.out`, `~/keys/manager-console.out`, `~/keys/mcp.out` (в том числе ключ администратора консоли).
- Документы и векторные индексы базы знаний `rag` (таблицы и файлы rag не затрагиваются).
- История миграций Flyway (`flyway_schema_history`).

**Что очищается и сбрасывается (одной SQL-транзакцией)**:
- Таблица `api_clients` (все клиенты, кроме сохраняемых) и связанные подсети `key_networks`.
- Журналы вызовов (`audit`, `audit_minute`) и действий администратора (`admin_audit`) — полностью.
- Прежние заявки (`key_requests`), события (`key_events`) и группы ловушек (`key_decoy_groups`) — полностью.
- Коннекторы, версии и зашифрованные секреты коннекторов из сейфа (`connectors`, `connector_versions`, `connector_secrets`).
- Режим шлюза возвращается в `normal` (`mode`), все интеграции и инструменты включаются (`integration_state`, `tool_state`), индивидуальные лимиты сбрасываются к значениям по умолчанию (`tool_settings`).

**Что создаётся**:
- 10 правдоподобных мастер-ключей (серверные боты с подсетями, мобильное приложение, студенческие агенты, истекающие ключи, ключ с политикой `geo=any`, ключ-ловушка с группой). Ключи сохраняются в `~/keys/demo/` с правами 600, в консоль не выводятся.
- 6 заявок на мастер-ключи (3 новые, 2 одобрены с сохранением ключей в `~/keys/demo/`, 1 отклонена с указанием причины).
- Серии вызовов `docs_search` и `rasp_find` с реальными вопросами студентов и обращением к расписанию с российских IP-адресов.
- Срабатывание ключа «Олимпиадный портал ТПУ» при вызове с `8.8.8.8` (приостановка из-за рубежа, событие `key.foreign_country`).
- Срабатывание ключа-ловушки с `203.0.113.50` (фоновая приостановка группы из двух server-ключей: Деканат + НТБ, событие `key.decoy_used` и `key.group_suspended`).

**Резервная копия и откат**:
Перед очисткой скрипт автоматически сохраняет дамп затрагиваемых таблиц шлюза:
`~/mytpu/backup-gateway-<ГГГГММДД_ЧЧММСС>.sql`.

Для отката данных из резервной копии выполните команду на сервере (из каталога `~/mytpu/deploy`):
```bash
docker compose --env-file test-server/server.env -f compose.dev.yml -f compose.test-server.yml \
    exec -T postgres psql -U postgres -d gateway < ~/mytpu/backup-gateway-<дата>.sql
```

## Консоль: две проверки

**Демо без сервера** — подставные данные, все экраны и действия, ничего не уходит в сеть:
```bash
cd console && npm run start:demo
```
Открыть http://localhost:4300/console, ключ `demo` (администратор) или `demo-viewer` (только чтение).

**Живой стенд** — с ПК, из корня репозитория, одной командой:
```bash
bash deploy/test-server/console.sh            # администратор
bash deploy/test-server/console.sh --viewer   # только чтение
```
Скрипт открывает SSH-туннель, кладёт ключ входа в буфер обмена (на экран не выводит) и запускает консоль:
http://localhost:4200/console → вставить ключ (Ctrl+V). Ctrl+C — закрыть консоль и туннель. После входа буфер
лучше очистить (скопировать что-нибудь другое): история буфера Windows хранит скопированное.

## 4. Чек-лист проверок

- [ ] **Проверка доступности шлюза по HTTPS**:
  ```bash
  curl -fsS https://<MYTPU_HOST>/actuator/health
  # Ожидаемый ответ: {"status":"UP"}
  ```

- [ ] **Защита консоли на уровне Caddy**:
  ```bash
  curl -i https://<MYTPU_HOST>/admin/api/me
  # Ожидаемый ответ: HTTP/2 404
  ```

- [ ] **Подключение Claude Code к MCP-шлюзу**:
  ```bash
  claude mcp add --transport http mytpu https://<MYTPU_HOST>/mcp --header "Authorization: Bearer <мастер-ключ>"
  ```
  В Claude Code задать вопрос: «Как получить справку об обучении в ТПУ?». Инструмент `docs_search` должен вернуть выдержки из документов.

- [ ] **Отказ при неверном ключе**:
  ```bash
  WRONG_KEY=неверный-ключ   # нарочно неверный: шлюз должен ответить 401
  curl -i -X POST https://<MYTPU_HOST>/mcp \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $WRONG_KEY" \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
  # Ожидаемый ответ: HTTP/2 401 Unauthorized
  ```

- [ ] **Защита ключа server по подсети**:
  Вызов с ключом `server` с IP-адреса вне разрешённой подсети возвращает `403 Forbidden`. В логах gateway фиксируется отказ по подсети.

- [ ] **Блокировка ключа с политикой «только РФ» через VPN**:
  Ключ MCP выдан с политикой `ru` (по умолчанию). Первый же вызов с зарубежного адреса → `401 key_suspended`, событие
  `key.foreign_country` с кодом страны и подсетью; снять — консоль «События ключей» или `issue-key --resume=<id>`.

- [ ] **Срабатывание ключа-ловушки**:
  Любой вызов с decoy-ключом генерирует событие `key.decoy_used`, группа ключей переводится в приостановленное состояние.

- [ ] **Доступ к консоли через SSH-туннель**:
  1. На ПК разработчика открыть SSH-туннель к порту шлюза:
     ```bash
     ssh -N -L 18081:127.0.0.1:8081 <SERVER>
     ```
  2. В каталоге `console/` на ПК запустить dev-сервер:
     ```bash
     npm run start:tunnel
     ```
  3. Открыть браузер по адресу `http://localhost:4200/console`.
  4. Войти по выданному ключу администратора (`console.admin`).
  5. Проверить, что без туннеля консоль недоступна.

---

## 5. Замер ресурсов и памяти

Запуск на сервере в каталоге `~/mytpu/deploy`:

```bash
# 1. Замер в покое:
bash test-server/measure.sh

# 2. Сохранить ключ MCP во временный файл:
printf '%s' '<мастер-ключ>' > /tmp/test-mcp-key

# 3. Замер под нагрузкой (20 параллельных вызовов docs_search):
bash test-server/measure.sh --load /tmp/test-mcp-key

# 4. Удалить временный файл с ключом:
rm -f /tmp/test-mcp-key
```

### Результаты замера (31.10.2026, сервер 2 ядра / 4 ГБ, корпус 126 документов / 698 фрагментов)

| Сервис | Лимит | Покой | Загрузка корпуса (пик) | 20 клиентов × 60 с через HTTPS (пик) |
|---|---|---|---|---|
| `postgres` | 640 МБ | 54 МБ | — | 82 МБ, ЦП 111 % |
| `rag` | 380 МБ | 269 МБ | 279 МБ | 299 МБ, ЦП 66 % |
| `gateway` | 380 МБ | 158 МБ | 154 МБ | 182 МБ, ЦП 21 % |
| `assistant` | 300 МБ | 131 МБ | 131 МБ | 131 МБ |
| `rag-ingest` | 512 МБ | — | 336 МБ (49 с) | — |
| **Контейнеры вместе** | 2,2 ГБ | ≈ 0,6 ГБ | ≈ 0,9 ГБ | ≈ 0,7 ГБ |
| **Свободно на хосте** | — | 2,7 ГБ | 2,7 ГБ | 2,6 ГБ |

Нагрузка (`deploy/load/mcp-load.py`): 6456 вызовов за 60 с (107 в секунду); прошли 854, остальные —
`rate_limited` (лимиты ключа работают); `docs_search` p50 86 мс, p95 1030 мс. ml-server на время замера
остановлен (чат не проверялся).

## 6. Остановка стенда

На сервере в каталоге `~/mytpu/deploy`:

```bash
# Остановка сервисов с сохранением томов данных (БД postgres, файлы rag):
bash test-server/down.sh

# Полная остановка с удалением томов и архива образов:
bash test-server/down.sh --purge
```

Владельцу сервера для отключения сайта в Caddy:
```bash
# Удалить строку импорта mytpu.caddy из /etc/caddy/Caddyfile
sudo sed -i '/import mytpu.caddy/d' /etc/caddy/Caddyfile
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
sudo rm -f /etc/caddy/mytpu.caddy
```

---

## 7. Если что-то не работает

1. **403 или 404 при входе в консоль через SSH-туннель**:
   Проверьте значение `TRUSTED_PROXIES` в `server.env`. Оно должно совпадать со шлюзом docker-сети `edge` (`172.31.240.1/32`). Если адрес отличается, Docker передаёт запрос шлюзу от нераспознанного прокси, и заголовок `X-Forwarded-For: 127.0.0.1` игнорируется.

2. **Ошибки 502 или 401 в assistant при обращении к LLM**:
   Проверьте файл `secrets/ml_api_key`. В нём должен быть указан корректный токен ml-server без пробелов и символов переноса строки. Также проверьте, что `ML_BASE_URL` в `server.env` содержит суффикс `/v1`.

3. **Завершение процесса rag-ingest по OOM (нехватка памяти)**:
   Не запускайте загрузку корпуса одновременно с нагрузочным тестированием. Сервер располагает ~3,2 ГБ свободной памяти; одновременная работа всех сервисов и ingest может исчерпать доступный объем.

4. **Caddy выдаёт ошибку валидации конфигурации**:
   Убедитесь, что файл `/etc/caddy/mytpu.caddy` скопирован, имеет права на чтение (`chmod 644`) и в нём корректно подставлен домен вместо `{{MYTPU_HOST}}`.
