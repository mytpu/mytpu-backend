#!/usr/bin/env bash
# Обновление демонстрационных данных шлюза на тестовом сервере Сбера.
# Запускается на сервере из каталога ~/mytpu/deploy:
#   bash test-server/demo-data.sh
#
# Скрипт:
# 1. Сохраняет мастер-ключи администратора (KEEP_IDS и файлы ~/keys/*.out).
# 2. Делает резервную копию таблиц шлюза (~/mytpu/backup-gateway-<дата>.sql).
# 3. Очищает старые тестовые данные одной транзакцией и сбрасывает настройки консоли.
# 4. Наполняет стенд правдоподобными ключами, заявками, событиями и вызовами MCP.
# 5. Выводит итоговую сводку результатов.
set -euo pipefail

cd "$(dirname "$0")/.."

# Маска 077: создаваемые файлы и каталоги доступны только владельцу
umask 077

DEMO_KEYS_DIR="$HOME/keys/demo"
mkdir -p "$DEMO_KEYS_DIR"
chmod 700 "$DEMO_KEYS_DIR"

COMPOSE_CMD=(docker compose --env-file test-server/server.env -f compose.dev.yml -f compose.test-server.yml)

# -----------------------------------------------------------------------------
# 1. Проверка доступности сервисов
# -----------------------------------------------------------------------------
if ! curl -fsS http://127.0.0.1:8081/actuator/health >/dev/null 2>&1; then
    echo "Ошибка: шлюз gateway недоступен на http://127.0.0.1:8081/actuator/health" >&2
    echo "Сначала запустите стенд: bash test-server/up.sh" >&2
    exit 1
fi

if ! "${COMPOSE_CMD[@]}" exec -T postgres pg_isready -U postgres >/dev/null 2>&1; then
    echo "Ошибка: сервис postgres недоступен через docker compose" >&2
    exit 1
fi

# -----------------------------------------------------------------------------
# 2. Определение сохраняемых ключей (KEEP_IDS и ~/keys/*.out)
# -----------------------------------------------------------------------------
ALL_KEEP_IDS=()

# Чтение из переменной окружения KEEP_IDS (разделение запятыми и пробелами)
if [ -n "${KEEP_IDS:-}" ]; then
    IFS=',' read -ra ADDR <<< "$KEEP_IDS"
    for item in "${ADDR[@]}"; do
        clean_id=$(echo "$item" | tr -d '[:space:]')
        if [ -n "$clean_id" ]; then
            ALL_KEEP_IDS+=("$clean_id")
        fi
    done
fi

# Функция извлечения client_id (cli_...) из файлов вывода issue-key
extract_client_ids() {
    local file="$1"
    if [ -f "$file" ]; then
        grep -E '(Мастер-ключ|Ключ-ловушка) выдан:' "$file" 2>/dev/null | grep -o -E 'cli_[0-9a-f]+' || \
        grep -o -E 'cli_[0-9a-f]+' "$file" 2>/dev/null || true
    fi
}

for out_file in "$HOME/keys/console.out" "$HOME/keys/manager-console.out" "$HOME/keys/mcp.out"; do
    if [ -f "$out_file" ]; then
        while IFS= read -r found_id; do
            if [ -n "$found_id" ]; then
                ALL_KEEP_IDS+=("$found_id")
            fi
        done < <(extract_client_ids "$out_file")
    fi
done

# Удаление дубликатов
UNIQUE_KEEP_IDS=()
if [ ${#ALL_KEEP_IDS[@]} -gt 0 ]; then
    while IFS= read -r line; do
        if [ -n "$line" ]; then
            UNIQUE_KEEP_IDS+=("$line")
        fi
    done < <(printf "%s\n" "${ALL_KEEP_IDS[@]}" | sort -u)
fi

if [ ${#UNIQUE_KEEP_IDS[@]} -eq 0 ]; then
    echo "Ошибка: список сохраняемых ключей пуст." >&2
    echo "Переменная KEEP_IDS не задана, а файлы ~/keys/{console.out,manager-console.out,mcp.out} отсутствуют или не содержат cli_..." >&2
    echo "Для безопасности очистки укажите KEEP_IDS=\"cli_...\" или создайте мастер-ключ консоли:" >&2
    echo "  bash test-server/issue-key.sh --owner=console-admin --roles=console.admin > ~/keys/console.out" >&2
    exit 1
fi

echo "==> Сохраняемые ключи (${#UNIQUE_KEEP_IDS[@]} шт.): ${UNIQUE_KEEP_IDS[*]}"

# Формирование SQL-списка для выражений NOT IN ('cli_...', ...)
KEEP_SQL_LIST=""
for kid in "${UNIQUE_KEEP_IDS[@]}"; do
    if [ -n "$KEEP_SQL_LIST" ]; then
        KEEP_SQL_LIST="${KEEP_SQL_LIST}, '${kid}'"
    else
        KEEP_SQL_LIST="'${kid}'"
    fi
done

# -----------------------------------------------------------------------------
# 3. Поиск ключа администратора консоли для вызовов админ-API
# -----------------------------------------------------------------------------
CONSOLE_KEY=""
if [ -f "$HOME/keys/console.key" ]; then
    CONSOLE_KEY=$(tr -d '\r\n' < "$HOME/keys/console.key")
elif [ -f "$HOME/keys/console.out" ]; then
    CONSOLE_KEY=$(grep -o -E 'mtpu_[A-Za-z0-9_-]{20,}' "$HOME/keys/console.out" | head -1 || true)
fi

if [ -z "$CONSOLE_KEY" ]; then
    echo "Ошибка: действующий мастер-ключ администратора консоли не найден в ~/keys/console.key или ~/keys/console.out" >&2
    echo "Выдайте ключ консоли администратора перед запуском:" >&2
    echo "  bash test-server/issue-key.sh --owner=console-admin --roles=console.admin > ~/keys/console.out" >&2
    exit 1
fi

# Каталог временных файлов для тел JSON-запросов (UTF-8)
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT

# -----------------------------------------------------------------------------
# 4. Резервная копия таблиц шлюза перед очисткой (pg_dump)
# -----------------------------------------------------------------------------
BACKUP_DIR="$HOME/mytpu"
mkdir -p "$BACKUP_DIR"
BACKUP_FILE="$BACKUP_DIR/backup-gateway-$(date +%Y%m%d_%H%M%S).sql"

echo "==> Создание резервной копии шлюза: $BACKUP_FILE"
# Таблицы шлюза — в схеме gateway: копия всей схемы (данные и структура)
"${COMPOSE_CMD[@]}" exec -T postgres pg_dump -U postgres -d gateway -n gateway > "$BACKUP_FILE"

if [ ! -s "$BACKUP_FILE" ]; then
    echo "Ошибка: файл резервной копии пуст или не был создан" >&2
    exit 1
fi
echo "    Резервная копия успешно создана ($(du -h "$BACKUP_FILE" | cut -f1))"

# -----------------------------------------------------------------------------
# 5. Замер удаляемых данных и очистка БД одной транзакцией
# -----------------------------------------------------------------------------
echo "==> Подсчёт данных перед очисткой..."
STATS_RAW=$("${COMPOSE_CMD[@]}" exec -T -e PGOPTIONS=-csearch_path=gateway postgres psql -U postgres -d gateway -t -A -F ',' -c "
SELECT
    (SELECT count(*) FROM api_clients WHERE id NOT IN (${KEEP_SQL_LIST})),
    (SELECT count(*) FROM key_decoy_groups),
    (SELECT count(*) FROM key_events),
    (SELECT count(*) FROM key_requests),
    (SELECT count(*) FROM audit),
    (SELECT count(*) FROM audit_minute),
    (SELECT count(*) FROM admin_audit),
    (SELECT count(*) FROM connectors),
    (SELECT count(*) FROM tool_settings);
")

IFS=',' read -r DEL_CLIENTS DEL_DECOY DEL_EVENTS DEL_REQUESTS DEL_AUDIT DEL_MINUTE DEL_ADMIN_AUDIT DEL_CONNECTORS DEL_TOOL_SETTINGS <<< "$STATS_RAW"

echo "==> Очистка БД одной транзакцией и сброс настроек консоли..."
"${COMPOSE_CMD[@]}" exec -T -e PGOPTIONS=-csearch_path=gateway postgres psql -U postgres -d gateway -v ON_ERROR_STOP=1 << EOF
BEGIN;

-- 1. Удаление связей ловушек
DELETE FROM key_decoy_groups;

-- 2. Очистка событий ключей
DELETE FROM key_events;

-- 3. Очистка заявок на ключи
DELETE FROM key_requests;

-- 4. Очистка журналов вызовов и минутных агрегатов
TRUNCATE audit;
TRUNCATE audit_minute;

-- 5. Очистка журнала действий администратора
TRUNCATE admin_audit;

-- 6. Удаление подсетей удаляемых клиентов
DELETE FROM key_networks WHERE client_id NOT IN (${KEEP_SQL_LIST});

-- 7. Удаление клиентов (кроме сохраняемых)
DELETE FROM api_clients WHERE id NOT IN (${KEEP_SQL_LIST});

-- 8. Сброс режима шлюза в normal
UPDATE mode SET value = 'normal', reason = NULL, updated_at = now() WHERE id = 1;

-- 9. Включение всех интеграций и инструментов (удаление переопределений)
DELETE FROM integration_state;
DELETE FROM tool_state;

-- 10. Сброс лимитов инструментов к значениям по умолчанию
DELETE FROM tool_settings;

-- 11. Удаление коннекторов, их версий и секретов из сейфа (каскадно)
DELETE FROM connectors;
DELETE FROM connector_secrets;

-- 12. Очистка суточного учёта и журнала выдачи личных данных удаляемых клиентов
DELETE FROM personal_usage WHERE client_id NOT IN (${KEEP_SQL_LIST});
DELETE FROM disclosures WHERE client_id NOT IN (${KEEP_SQL_LIST});

COMMIT;
EOF
echo "    Очистка и сброс завершены успешно."

# Шлюз держит режим, состояние интеграций и найденные ключи в памяти — перезапуск, чтобы он перечитал БД
echo "==> Перезапуск шлюза..."
"${COMPOSE_CMD[@]}" restart gateway >/dev/null
for i in $(seq 1 60); do
    curl -fsS http://127.0.0.1:8081/actuator/health >/dev/null 2>&1 && break
    sleep 2
done
curl -fsS http://127.0.0.1:8081/actuator/health >/dev/null || { echo "Шлюз не поднялся после перезапуска" >&2; exit 1; }

# -----------------------------------------------------------------------------
# 6. Вспомогательные функции выпуска ключей, заявок и вызовов MCP
# -----------------------------------------------------------------------------
issue_key() {
    local key_file="$1"
    shift
    local output
    output=$(bash test-server/issue-key.sh "$@")

    local key
    key=$(printf '%s\n' "$output" | grep -o -E 'mtpu_[A-Za-z0-9_-]{20,}' | head -1 || true)
    if [ -z "$key" ]; then
        echo "Ошибка: ключ не найден в выводе issue-key.sh" >&2
        exit 1
    fi
    printf '%s' "$key" > "$key_file"
    chmod 600 "$key_file"

    local client_id
    client_id=$(printf '%s\n' "$output" | grep -E '(Мастер-ключ|Ключ-ловушка) выдан:' | grep -o -E 'cli_[0-9a-f]+' | head -1 || true)
    if [ -z "$client_id" ]; then
        client_id=$(printf '%s\n' "$output" | grep -o -E 'cli_[0-9a-f]+' | head -1 || true)
    fi
    if [ -z "$client_id" ]; then
        echo "Ошибка: client_id не найден в выводе issue-key.sh" >&2
        exit 1
    fi
    printf '%s' "$client_id"
}

mcp_call() {
    local key_file="$1"
    local xff="$2"
    local tool="$3"
    local json_args="$4"

    local key
    key=$(tr -d '\r\n' < "$key_file")
    local body
    body=$(printf '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":%s}}' "$tool" "$json_args")

    curl -s -o /dev/null -w '%{http_code}' \
        -X POST "http://127.0.0.1:8081/mcp" \
        -H "Content-Type: application/json" \
        -H "Accept: application/json, text/event-stream" \
        -H "Authorization: Bearer $key" \
        -H "X-Forwarded-For: $xff" \
        --data-binary "$body"
}

create_request() {
    local req_file="$1"
    local resp_file="$TEMP_DIR/req_resp.json"
    local http_code
    http_code=$(curl -s -o "$resp_file" -w '%{http_code}' \
        -X POST "http://127.0.0.1:8081/admin/api/key-requests" \
        -H "Content-Type: application/json; charset=utf-8" \
        -H "Authorization: Bearer $CONSOLE_KEY" \
        -H "X-Forwarded-For: 127.0.0.1" \
        --data-binary @"$req_file")

    local req_id
    req_id=$(grep -o -E 'req_[0-9a-f]+' "$resp_file" | head -1 || true)
    if [ "$http_code" != "201" ] || [ -z "$req_id" ]; then
        echo "Ошибка создания заявки (HTTP $http_code): $(cat "$resp_file")" >&2
        exit 1
    fi
    printf '%s' "$req_id"
}

# -----------------------------------------------------------------------------
# 7. Наполнение: выпуск правдоподобных мастер-ключей (10 ключей)
# -----------------------------------------------------------------------------
echo
echo "=================================================================="
echo "1/3. Выпуск демонстрационных мастер-ключей"
echo "=================================================================="

echo "--> 1. Деканат ИШИТР · бот расписания (server, 176.108.244.0/24)..."
DEKANAT_ID=$(issue_key "$DEMO_KEYS_DIR/dekanat-bot.key" \
    --owner="Деканат ИШИТР · бот расписания" \
    --kind=server --scopes=rasp.read --networks=176.108.244.0/24)

echo "--> 2. Мобильное приложение «Мой ТПУ» (server, 10.0.0.0/8)..."
MOBILE_ID=$(issue_key "$DEMO_KEYS_DIR/mobile-app.key" \
    --owner="Мобильное приложение «Мой ТПУ»" \
    --kind=server --scopes=docs.read,rasp.read --networks=10.0.0.0/8)

echo "--> 3. НТБ ТПУ · поисковый агент (server, 176.108.244.0/24)..."
NTB_ID=$(issue_key "$DEMO_KEYS_DIR/ntb-agent.key" \
    --owner="НТБ ТПУ · поисковый агент" \
    --kind=server --scopes=docs.read --networks=176.108.244.0/24)

echo "--> 4. Студсовет · Telegram-бот (person, 30 дней)..."
STUDSOVET_ID=$(issue_key "$DEMO_KEYS_DIR/studsovet-bot.key" \
    --owner="Студсовет · Telegram-бот" \
    --kind=person --scopes=docs.read,rasp.read --days=30)

echo "--> 5. Claude Code · кафедра ОИТ (person, 30 дней)..."
CLAUDE_ID=$(issue_key "$DEMO_KEYS_DIR/claude-oit.key" \
    --owner="Claude Code · кафедра ОИТ" \
    --kind=person --scopes=docs.read,rasp.read --days=30)

echo "--> 6. Олимпиадный портал ТПУ (person, 10 дней — в список «истекают»)..."
OLYMP_ID=$(issue_key "$DEMO_KEYS_DIR/olymp-portal.key" \
    --owner="Олимпиадный портал ТПУ" \
    --kind=person --scopes=docs.read,rasp.read --days=10)

echo "--> 7. Выпускной проект ИШИТР (person, 5 дней — в список «истекают»)..."
GRAD_ID=$(issue_key "$DEMO_KEYS_DIR/grad-project.key" \
    --owner="Выпускной проект ИШИТР" \
    --kind=person --scopes=docs.read --days=5)

echo "--> 8. Хакатон Томск 2026 (demo, 7 дней)..."
HACKATHON_ID=$(issue_key "$DEMO_KEYS_DIR/hackathon.key" \
    --owner="Хакатон Томск 2026" \
    --kind=demo --scopes=docs.read,rasp.read --days=7)

echo "--> 9. ChatGPT-коннектор Центра карьеры (person, geo any)..."
CHATGPT_ID=$(issue_key "$DEMO_KEYS_DIR/chatgpt-career.key" \
    --owner="ChatGPT-коннектор Центра карьеры" \
    --kind=person --scopes=docs.read,rasp.read --days=30 \
    --geo=any --geo-reason="облачный агент ChatGPT — ходит с серверов в США")

echo "--> 10. Ловушка: тестовый репозиторий GitLab ТПУ (группа: Деканат + НТБ)..."
DECOY_ID=$(issue_key "$DEMO_KEYS_DIR/decoy-gitlab.key" \
    --decoy --owner="ловушка: тестовый репозиторий GitLab ТПУ" \
    --decoy-group="${DEKANAT_ID},${NTB_ID}")

echo "    Все 10 ключей выпущены в $DEMO_KEYS_DIR."

# -----------------------------------------------------------------------------
# 8. Наполнение: создание и обработка заявок (6 заявок)
# -----------------------------------------------------------------------------
echo
echo "=================================================================="
echo "2/3. Создание и обработка заявок на мастер-ключи"
echo "=================================================================="

# Заявка 1: Лаборатория робототехники (будет одобрена)
cat << 'EOF' > "$TEMP_DIR/req1.json"
{
  "requester": "Лаборатория робототехники ИШНКБ",
  "contact": "robotics_lab@tpu.ru",
  "purpose": "Автономный информационный стенд расписания занятий в корпусе 10",
  "scopes": ["rasp.read"],
  "kind": "server",
  "days": 90,
  "networks": ["176.108.244.0/24"]
}
EOF

# Заявка 2: Соколов А. Д. (будет одобрена)
cat << 'EOF' > "$TEMP_DIR/req2.json"
{
  "requester": "Соколов Артем Дмитриевич",
  "contact": "sokolov_ad@tpu.ru",
  "purpose": "Курсовой проект: семантический поиск по нормативным регламентам ТПУ",
  "scopes": ["docs.read"],
  "kind": "person",
  "days": 30,
  "networks": []
}
EOF

# Заявка 3: ООО Внешний Мониторинг (будет отклонена)
cat << 'EOF' > "$TEMP_DIR/req3.json"
{
  "requester": "ООО Внешний Мониторинг",
  "contact": "monitoring_ext@tpu.ru",
  "purpose": "Массовая выгрузка расписания занятий без ограничения по подсетям",
  "scopes": ["rasp.read"],
  "kind": "person",
  "days": 30,
  "networks": []
}
EOF

# Заявка 4: Кафедра программной инженерии (новая)
cat << 'EOF' > "$TEMP_DIR/req4.json"
{
  "requester": "Кафедра программной инженерии",
  "contact": "pi_dept@tpu.ru",
  "purpose": "Стенд автоматизированного тестирования студенческих лабораторных работ с расписанием",
  "scopes": ["rasp.read"],
  "kind": "server",
  "days": 90,
  "networks": ["176.108.244.0/24"]
}
EOF

# Заявка 5: ИИТ клуб ТПУ (новая)
cat << 'EOF' > "$TEMP_DIR/req5.json"
{
  "requester": "ИИТ клуб ТПУ",
  "contact": "itclub@tpu.ru",
  "purpose": "Чат-бот адаптации первокурсников (поиск по регламентам и расписание пар)",
  "scopes": ["docs.read", "rasp.read"],
  "kind": "person",
  "days": 30,
  "networks": []
}
EOF

# Заявка 6: Васильева Е. И. (новая)
cat << 'EOF' > "$TEMP_DIR/req6.json"
{
  "requester": "Васильева Елена Игоревна",
  "contact": "vasilieva_ei@tpu.ru",
  "purpose": "Демонстрация интеллектуального помощника абитуриента на дне открытых дверей ТПУ",
  "scopes": ["docs.read"],
  "kind": "demo",
  "days": 7,
  "networks": []
}
EOF

REQ1_ID=$(create_request "$TEMP_DIR/req1.json")
REQ2_ID=$(create_request "$TEMP_DIR/req2.json")
REQ3_ID=$(create_request "$TEMP_DIR/req3.json")
REQ4_ID=$(create_request "$TEMP_DIR/req4.json")
REQ5_ID=$(create_request "$TEMP_DIR/req5.json")
REQ6_ID=$(create_request "$TEMP_DIR/req6.json")
echo "--> Создано 6 заявок: $REQ1_ID, $REQ2_ID, $REQ3_ID, $REQ4_ID, $REQ5_ID, $REQ6_ID"

# Одобрение заявки 1
cat << 'EOF' > "$TEMP_DIR/approve1.json"
{
  "owner": "Лаборатория робототехники ИШНКБ",
  "scopes": ["rasp.read"],
  "kind": "server",
  "days": 90,
  "networks": ["176.108.244.0/24"]
}
EOF

APPROVE1_RESP="$TEMP_DIR/approve1_resp.json"
APPROVE1_HTTP=$(curl -s -o "$APPROVE1_RESP" -w '%{http_code}' \
    -X POST "http://127.0.0.1:8081/admin/api/key-requests/${REQ1_ID}/approve" \
    -H "Content-Type: application/json; charset=utf-8" \
    -H "Authorization: Bearer $CONSOLE_KEY" \
    -H "X-Forwarded-For: 127.0.0.1" \
    --data-binary @"$TEMP_DIR/approve1.json")

APPROVED1_KEY=$(grep -o -E 'mtpu_[A-Za-z0-9_-]{20,}' "$APPROVE1_RESP" | head -1 || true)
APPROVED1_CLIENT_ID=$(grep -o -E 'cli_[0-9a-f]+' "$APPROVE1_RESP" | head -1 || true)
if [ "$APPROVE1_HTTP" != "200" ] || [ -z "$APPROVED1_KEY" ]; then
    echo "Ошибка одобрения заявки $REQ1_ID (HTTP $APPROVE1_HTTP): $(cat "$APPROVE1_RESP")" >&2
    exit 1
fi
printf '%s' "$APPROVED1_KEY" > "$DEMO_KEYS_DIR/approved-robotics.key"
chmod 600 "$DEMO_KEYS_DIR/approved-robotics.key"
echo "--> Заявка $REQ1_ID одобрена -> ключ $APPROVED1_CLIENT_ID в approved-robotics.key"

# Одобрение заявки 2
cat << 'EOF' > "$TEMP_DIR/approve2.json"
{
  "owner": "Соколов Артем Дмитриевич",
  "scopes": ["docs.read"],
  "kind": "person",
  "days": 30,
  "networks": []
}
EOF

APPROVE2_RESP="$TEMP_DIR/approve2_resp.json"
APPROVE2_HTTP=$(curl -s -o "$APPROVE2_RESP" -w '%{http_code}' \
    -X POST "http://127.0.0.1:8081/admin/api/key-requests/${REQ2_ID}/approve" \
    -H "Content-Type: application/json; charset=utf-8" \
    -H "Authorization: Bearer $CONSOLE_KEY" \
    -H "X-Forwarded-For: 127.0.0.1" \
    --data-binary @"$TEMP_DIR/approve2.json")

APPROVED2_KEY=$(grep -o -E 'mtpu_[A-Za-z0-9_-]{20,}' "$APPROVE2_RESP" | head -1 || true)
APPROVED2_CLIENT_ID=$(grep -o -E 'cli_[0-9a-f]+' "$APPROVE2_RESP" | head -1 || true)
if [ "$APPROVE2_HTTP" != "200" ] || [ -z "$APPROVED2_KEY" ]; then
    echo "Ошибка одобрения заявки $REQ2_ID (HTTP $APPROVE2_HTTP): $(cat "$APPROVE2_RESP")" >&2
    exit 1
fi
printf '%s' "$APPROVED2_KEY" > "$DEMO_KEYS_DIR/approved-sokolov.key"
chmod 600 "$DEMO_KEYS_DIR/approved-sokolov.key"
echo "--> Заявка $REQ2_ID одобрена -> ключ $APPROVED2_CLIENT_ID в approved-sokolov.key"

# Отклонение заявки 3
cat << 'EOF' > "$TEMP_DIR/reject3.json"
{
  "reason": "Для серверных интеграций обязательно указание фиксированной подсети ТПУ (CIDR)."
}
EOF

REJECT3_RESP="$TEMP_DIR/reject3_resp.json"
REJECT3_HTTP=$(curl -s -o "$REJECT3_RESP" -w '%{http_code}' \
    -X POST "http://127.0.0.1:8081/admin/api/key-requests/${REQ3_ID}/reject" \
    -H "Content-Type: application/json; charset=utf-8" \
    -H "Authorization: Bearer $CONSOLE_KEY" \
    -H "X-Forwarded-For: 127.0.0.1" \
    --data-binary @"$TEMP_DIR/reject3.json")

if [ "$REJECT3_HTTP" != "200" ]; then
    echo "Ошибка отклонения заявки $REQ3_ID (HTTP $REJECT3_HTTP): $(cat "$REJECT3_RESP")" >&2
    exit 1
fi
echo "--> Заявка $REQ3_ID отклонена с фиксацией причины в журнале"

# -----------------------------------------------------------------------------
# 9. Наполнение: вызовы MCP (генерация журнала, подсетей и срабатываний защиты)
# -----------------------------------------------------------------------------
echo
echo "=================================================================="
echo "3/3. Вызовы MCP инструментов для генерации журнала и событий"
echo "=================================================================="

echo "--> Вызовы от серверных ботов и ассистента..."
mcp_call "$DEMO_KEYS_DIR/dekanat-bot.key" "176.108.244.10" "rasp_find" '{"query":"403"}' >/dev/null
mcp_call "$DEMO_KEYS_DIR/dekanat-bot.key" "176.108.244.12" "rasp_find" '{"query":"209"}' >/dev/null
mcp_call "$DEMO_KEYS_DIR/dekanat-bot.key" "176.108.244.20" "rasp_find" '{"query":"Иванов"}' >/dev/null

mcp_call "$DEMO_KEYS_DIR/mobile-app.key" "10.1.5.10" "docs_search" '{"query":"как получить справку об обучении"}' >/dev/null
mcp_call "$DEMO_KEYS_DIR/mobile-app.key" "10.2.8.20" "docs_search" '{"query":"как заселиться в общежитие"}' >/dev/null
mcp_call "$DEMO_KEYS_DIR/mobile-app.key" "10.0.12.30" "rasp_find" '{"query":"Петров"}' >/dev/null
mcp_call "$DEMO_KEYS_DIR/mobile-app.key" "10.1.5.10" "docs_search" '{"query":"какие есть академические стипендии"}' >/dev/null

mcp_call "$DEMO_KEYS_DIR/ntb-agent.key" "176.108.244.100" "docs_search" '{"query":"где находится читальный зал НТБ"}' >/dev/null
mcp_call "$DEMO_KEYS_DIR/ntb-agent.key" "176.108.244.105" "docs_search" '{"query":"доступ к электронным ресурсам библиотеки"}' >/dev/null

echo "--> Вызовы персональных ключей с разных подсетей РФ (события key.new_network)..."
mcp_call "$DEMO_KEYS_DIR/studsovet-bot.key" "176.108.244.30" "docs_search" '{"query":"порядок оформления академического отпуска"}' >/dev/null
mcp_call "$DEMO_KEYS_DIR/studsovet-bot.key" "95.165.10.15"  "docs_search" '{"query":"как восстановиться после отчисления"}' >/dev/null
mcp_call "$DEMO_KEYS_DIR/studsovet-bot.key" "213.87.128.5"  "rasp_find"   '{"query":"Смирнов"}' >/dev/null
mcp_call "$DEMO_KEYS_DIR/studsovet-bot.key" "31.173.80.20"  "docs_search" '{"query":"материальная помощь студентам"}' >/dev/null

mcp_call "$DEMO_KEYS_DIR/claude-oit.key" "176.108.244.40" "docs_search" '{"query":"как получить доступ в личный кабинет студента"}' >/dev/null
mcp_call "$DEMO_KEYS_DIR/claude-oit.key" "95.165.42.88"  "docs_search" '{"query":"расписание экзаменационной сессии"}' >/dev/null
mcp_call "$DEMO_KEYS_DIR/claude-oit.key" "213.87.160.40" "rasp_find"   '{"query":"Ковалев"}' >/dev/null

mcp_call "$DEMO_KEYS_DIR/grad-project.key" "176.108.244.50" "docs_search" '{"query":"правила оформления выпускных квалификационных работ"}' >/dev/null
mcp_call "$DEMO_KEYS_DIR/grad-project.key" "95.165.10.25"  "docs_search" '{"query":"сроки сдачи диплома и защиты"}' >/dev/null

mcp_call "$DEMO_KEYS_DIR/hackathon.key" "176.108.244.60" "rasp_find"   '{"query":"Кузнецов"}' >/dev/null
mcp_call "$DEMO_KEYS_DIR/hackathon.key" "31.173.100.12"  "docs_search" '{"query":"положение о хакатонах ТПУ"}' >/dev/null

echo "--> Вызовы ChatGPT-коннектора (разрешена любая страна geo=any)..."
mcp_call "$DEMO_KEYS_DIR/chatgpt-career.key" "198.51.100.50" "docs_search" '{"query":"практика и стажировки для студентов ТПУ"}' >/dev/null
mcp_call "$DEMO_KEYS_DIR/chatgpt-career.key" "176.108.244.70" "docs_search" '{"query":"Центр карьеры ТПУ вакансии партнеров"}' >/dev/null

echo "--> Вызовы одобренных ключей..."
mcp_call "$DEMO_KEYS_DIR/approved-robotics.key" "176.108.244.80" "rasp_find"   '{"query":"10 корпус"}' >/dev/null
mcp_call "$DEMO_KEYS_DIR/approved-sokolov.key"  "176.108.244.90" "docs_search" '{"query":"устав ТПУ и правила внутреннего распорядка"}' >/dev/null

echo "--> Вызов ключом «Олимпиадный портал»: сначала РФ, затем из США (8.8.8.8)..."
mcp_call "$DEMO_KEYS_DIR/olymp-portal.key" "176.108.244.15" "rasp_find" '{"query":"олимпиада прорыв"}' >/dev/null
CODE_FOREIGN=$(mcp_call "$DEMO_KEYS_DIR/olymp-portal.key" "8.8.8.8" "docs_search" '{"query":"льготы победителям олимпиад"}')
echo "    Вызов с 8.8.8.8: HTTP $CODE_FOREIGN (ожидается 401: ключ приостановлен foreign:US)"

echo "--> Вызов ключа-ловушки с 203.0.113.50 (приостановка группы Деканат + НТБ)..."
CODE_DECOY=$(mcp_call "$DEMO_KEYS_DIR/decoy-gitlab.key" "203.0.113.50" "docs_search" '{"query":"секретные материалы"}')
echo "    Вызов ловушки: HTTP $CODE_DECOY (ожидается 401: срабатывание ловушки и фоновая приостановка)"

# Ожидание фиксации асинхронной приостановки группы ловушки в БД
sleep 2

# -----------------------------------------------------------------------------
# 10. Итоговая сводная таблица
# -----------------------------------------------------------------------------
echo
echo "=========================================================================================================="
echo "                                   ДЕМОНСТРАЦИОННЫЕ ДАННЫЕ ОБНОВЛЕНЫ"
echo "=========================================================================================================="
echo "1. ОЧИСТКА СТАРЫХ ДАННЫХ:"
printf "  - Клиенты api_clients:          %s удалено (сохранено: %s)\n" "$DEL_CLIENTS" "${#UNIQUE_KEEP_IDS[@]}"
printf "  - Группы ловушек:              %s удалено\n" "$DEL_DECOY"
printf "  - События key_events:           %s удалено\n" "$DEL_EVENTS"
printf "  - Заявки key_requests:          %s удалено\n" "$DEL_REQUESTS"
printf "  - Журнал вызовов audit:         %s удалено\n" "$DEL_AUDIT"
printf "  - Минутные агрегаты:            %s удалено\n" "$DEL_MINUTE"
printf "  - Журнал действий admin_audit:  %s удалено\n" "$DEL_ADMIN_AUDIT"
printf "  - Коннекторы и версии:          %s удалено\n" "$DEL_CONNECTORS"
printf "  - Свои лимиты tool_settings:    %s сброшено\n" "$DEL_TOOL_SETTINGS"
echo "  - Режим шлюза:                  сброшен в normal"
echo "  - Инструменты и интеграции:     включены"
echo "  - База знаний rag:              НЕ ЗАТРАГИВАЛАСЬ"
echo "----------------------------------------------------------------------------------------------------------"
echo "2. СОЗДАННЫЕ МАСТЕР-КЛЮЧИ (сохранены в ~/keys/demo/, права 600):"
printf "%-18s | %-38s | %-8s | %s\n" "ID клиента" "Владелец" "Тип" "Статус / Особенности"
echo "-------------------+----------------------------------------+----------+----------------------------------"
printf "%-18s | %-38s | %-8s | %s\n" "$DEKANAT_ID" "Деканат ИШИТР · бот расписания" "server" "Приостановлен (группа ловушки)"
printf "%-18s | %-38s | %-8s | %s\n" "$MOBILE_ID" "Мобильное приложение «Мой ТПУ»" "server" "Активен (сеть 10.0.0.0/8)"
printf "%-18s | %-38s | %-8s | %s\n" "$NTB_ID" "НТБ ТПУ · поисковый агент" "server" "Приостановлен (группа ловушки)"
printf "%-18s | %-38s | %-8s | %s\n" "$STUDSOVET_ID" "Студсовет · Telegram-бот" "person" "Активен (вызовы с 4 подсетей РФ)"
printf "%-18s | %-38s | %-8s | %s\n" "$CLAUDE_ID" "Claude Code · кафедра ОИТ" "person" "Активен (вызовы с подсетей РФ)"
printf "%-18s | %-38s | %-8s | %s\n" "$OLYMP_ID" "Олимпиадный портал ТПУ" "person" "Приостановлен (вызов с 8.8.8.8, 10 дн)"
printf "%-18s | %-38s | %-8s | %s\n" "$GRAD_ID" "Выпускной проект ИШИТР" "person" "Истекает (осталось 5 дней)"
printf "%-18s | %-38s | %-8s | %s\n" "$HACKATHON_ID" "Хакатон Томск 2026" "demo" "Истекает (осталось 7 дней)"
printf "%-18s | %-38s | %-8s | %s\n" "$CHATGPT_ID" "ChatGPT-коннектор Центра карьеры" "person" "Активен (geo: any, США разрешено)"
printf "%-18s | %-38s | %-8s | %s\n" "$DECOY_ID" "ловушка: тестовый репозиторий" "server" "Ловушка сработала (203.0.113.50)"
printf "%-18s | %-38s | %-8s | %s\n" "$APPROVED1_CLIENT_ID" "Лаборатория робототехники ИШНКБ" "server" "Выпущен по заявке $REQ1_ID"
printf "%-18s | %-38s | %-8s | %s\n" "$APPROVED2_CLIENT_ID" "Соколов Артем Дмитриевич" "person" "Выпущен по заявке $REQ2_ID"
echo "----------------------------------------------------------------------------------------------------------"
echo "3. СОЗДАННЫЕ ЗАЯВКИ НА КЛЮЧИ:"
printf "%-12s | %-32s | %-12s | %s\n" "ID заявки" "Заявитель" "Статус" "Результат / Причина"
echo "-------------+----------------------------------+--------------+------------------------------------------"
printf "%-12s | %-32s | %-12s | %s\n" "$REQ1_ID" "Лаборатория робототехники ИШНКБ" "approved" "Одобрена -> $APPROVED1_CLIENT_ID"
printf "%-12s | %-32s | %-12s | %s\n" "$REQ2_ID" "Соколов Артем Дмитриевич" "approved" "Одобрена -> $APPROVED2_CLIENT_ID"
printf "%-12s | %-32s | %-12s | %s\n" "$REQ3_ID" "ООО Внешний Мониторинг" "rejected" "Отклонена: нет CIDR подсети"
printf "%-12s | %-32s | %-12s | %s\n" "$REQ4_ID" "Кафедра программной инженерии" "new" "Ожидает решения (server, 90 дней)"
printf "%-12s | %-32s | %-12s | %s\n" "$REQ5_ID" "ИИТ клуб ТПУ" "new" "Ожидает решения (person, 30 дней)"
printf "%-12s | %-32s | %-12s | %s\n" "$REQ6_ID" "Васильева Елена Игоревна" "new" "Ожидает решения (demo, 7 дней)"
echo "----------------------------------------------------------------------------------------------------------"
echo "4. РЕЗЕРВНАЯ КОПИЯ ДЛЯ ОТКАТА:"
echo "  Файл копии: $BACKUP_FILE"
echo "  Команда отката в случае необходимости:"
echo "    ${COMPOSE_CMD[*]} exec -T -e PGOPTIONS=-csearch_path=gateway postgres psql -U postgres -d gateway < $BACKUP_FILE"
echo "=========================================================================================================="
echo
echo "Обновление демонстрационных данных завершено успешно."
