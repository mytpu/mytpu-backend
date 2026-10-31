#!/usr/bin/env bash
# Наполнение тестового стенда данными для ручной проверки консоли администратора.
# Запускается на сервере из каталога ~/mytpu/deploy:
#   bash test-server/seed.sh
#
# Внимание: повторный запуск допустим — он создаёт дополнительный набор тестовых ключей и заявок.
set -euo pipefail

cd "$(dirname "$0")/.."

# Маска 077: создаваемые файлы ключей доступны только владельцу
umask 077

KEYS_DIR="$HOME/keys/seed"
mkdir -p "$KEYS_DIR"

# 1. Проверка доступности шлюза
if ! curl -fsS http://127.0.0.1:8081/actuator/health >/dev/null 2>&1; then
    echo "Ошибка: шлюз gateway недоступен на http://127.0.0.1:8081/actuator/health" >&2
    echo "Сначала запустите стенд: bash test-server/up.sh" >&2
    exit 1
fi

# 2. Поиск мастер-ключа администратора консоли
CONSOLE_KEY=""
if [ -f "$HOME/keys/console.key" ]; then
    CONSOLE_KEY=$(tr -d '\r\n' < "$HOME/keys/console.key")
elif [ -f "$HOME/keys/console.out" ]; then
    CONSOLE_KEY=$(grep -o -E 'mtpu_[A-Za-z0-9_-]{20,}' "$HOME/keys/console.out" | head -1 || true)
fi

if [ -z "$CONSOLE_KEY" ]; then
    echo "Ошибка: ключ администратора консоли не найден в ~/keys/console.key или ~/keys/console.out" >&2
    echo "Выдайте ключ консоли администратора перед запуском сида:" >&2
    echo "  bash test-server/issue-key.sh --owner=console-admin --roles=console.admin > ~/keys/console.out" >&2
    exit 1
fi

# Каталог временных файлов для тел JSON-запросов (UTF-8)
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT

# Вспомогательная функция выпуска ключа через issue-key.sh
# Возвращает client_id (stdout), ключ сохраняет в указанный файл, никогда не печатая сам ключ в консоль.
issue_key() {
    local key_file="$1"
    shift
    echo "==> Выпуск ключа: $*" >&2
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
    echo "    Успешно: id=$client_id -> $key_file" >&2
    printf '%s' "$client_id"
}

# Вспомогательная функция вызова MCP инструмента через HTTP шлюза
# Формат: mcp_call <key-file> <xff> <tool> <json-args>
# Возвращает HTTP-код ответа
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

# 3. Выпуск набора мастер-ключей
echo "=================================================================="
echo "1/3. Выпуск мастер-ключей через issue-key.sh"
echo "=================================================================="

PERSON1_ID=$(issue_key "$KEYS_DIR/person1.key" --owner="Иван Иванов (person 1)" --scopes=docs.read,rasp.read)
PERSON2_ID=$(issue_key "$KEYS_DIR/person2.key" --owner="Петр Петров (person 2, geo any)" --scopes=docs.read,rasp.read --geo=any --geo-reason="облачный агент для проверки")
SERVER_ID=$(issue_key "$KEYS_DIR/server.key" --owner="Сервер интеграции (server)" --kind=server --scopes=docs.read,rasp.read --networks=203.0.113.0/24)
DEMO_ID=$(issue_key "$KEYS_DIR/demo.key" --owner="Демо доступ (demo)" --kind=demo --scopes=docs.read,rasp.read)
VIEWER_ID=$(issue_key "$KEYS_DIR/viewer.key" --owner="Наблюдатель консоли" --roles=console.viewer)
DECOY_ID=$(issue_key "$KEYS_DIR/decoy.key" --decoy --owner="ловушка: тестовый репозиторий" --decoy-group="${PERSON1_ID},${SERVER_ID}")

# 4. Вызовы MCP инструментов с эмуляцией разных адресов клиентов через X-Forwarded-For
echo
echo "=================================================================="
echo "2/3. Вызовы MCP для генерации событий и проверки политик"
echo "=================================================================="

echo "==> Вызовы person 1 (docs_search с 176.108.244.10, затем новая сеть 95.165.10.10)..."
P1_HTTP1=$(mcp_call "$KEYS_DIR/person1.key" "176.108.244.10" "docs_search" '{"query":"как получить справку об обучении"}')
P1_HTTP2=$(mcp_call "$KEYS_DIR/person1.key" "176.108.244.10" "docs_search" '{"query":"какие есть стипендии"}')
P1_HTTP3=$(mcp_call "$KEYS_DIR/person1.key" "176.108.244.10" "docs_search" '{"query":"как заселиться в общежитие"}')
P1_HTTP4=$(mcp_call "$KEYS_DIR/person1.key" "95.165.10.10" "docs_search" '{"query":"как поменять пароль"}')
echo "    Коды person 1: $P1_HTTP1, $P1_HTTP2, $P1_HTTP3 (сеть 1), $P1_HTTP4 (новая сеть РФ)"

echo "==> Вызов person 2 (geo any, docs_search с 8.8.8.8)..."
P2_HTTP=$(mcp_call "$KEYS_DIR/person2.key" "8.8.8.8" "docs_search" '{"query":"где найти учебный план"}')
echo "    Код person 2: $P2_HTTP (ожидается 200)"

echo "==> Вызов demo (ru_only по умолчанию, docs_search с 8.8.4.4)..."
DEMO_HTTP=$(mcp_call "$KEYS_DIR/demo.key" "8.8.4.4" "docs_search" '{"query":"есть ли бассейн"}')
echo "    Код demo: $DEMO_HTTP (ожидается 401: ключ приостановлен из-за key.foreign_country)"

echo "==> Вызов server (docs_search с 198.51.100.7 вне разрешённой сети 203.0.113.0/24)..."
SERVER_HTTP=$(mcp_call "$KEYS_DIR/server.key" "198.51.100.7" "docs_search" '{"query":"расписание сессии"}')
echo "    Код server: $SERVER_HTTP (ожидается 403: сеть запрещена)"

echo "==> Вызов decoy (ловушка, docs_search с 203.0.113.99)..."
DECOY_HTTP=$(mcp_call "$KEYS_DIR/decoy.key" "203.0.113.99" "docs_search" '{"query":"проверка ловушки"}')
echo "    Код decoy: $DECOY_HTTP (ожидается 401: срабатывание ловушки и фоновая приостановка группы)"

# Небольшая пауза для завершения фоновой приостановки группы ловушки в БД
sleep 1

# 5. Создание и обработка заявок на мастер-ключи через API консоли
echo
echo "=================================================================="
echo "3/3. Создание и обработка заявок на мастер-ключи в консоли"
echo "=================================================================="

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

# Подготовка UTF-8 тел 5 заявок
cat << 'EOF' > "$TEMP_DIR/req1.json"
{
  "requester": "Сидоров Алексей Михайлович",
  "contact": "sidorov_am@tpu.ru",
  "purpose": "Интеграция телеграм-бота учебной группы с расписанием и поиском по документам",
  "scopes": ["docs.read", "rasp.read"],
  "kind": "person",
  "days": 30,
  "networks": []
}
EOF

cat << 'EOF' > "$TEMP_DIR/req2.json"
{
  "requester": "ООО Вектор Интеграция",
  "contact": "vector_contractor@tpu.ru",
  "purpose": "Сторонняя выгрузка расписания без указания фиксированных IP-адресов",
  "scopes": ["rasp.read"],
  "kind": "person",
  "days": 30,
  "networks": []
}
EOF

cat << 'EOF' > "$TEMP_DIR/req3.json"
{
  "requester": "Кафедра программной инженерии",
  "contact": "pi_lab@tpu.ru",
  "purpose": "Сервер автоматической сверки расписания лабораторных занятий",
  "scopes": ["rasp.read"],
  "kind": "server",
  "days": 90,
  "networks": ["198.51.100.0/24"]
}
EOF

cat << 'EOF' > "$TEMP_DIR/req4.json"
{
  "requester": "Смирнова Елена Дмитриевна",
  "contact": "smirnova_ed@tpu.ru",
  "purpose": "Демонстрация работы ассистента на дне открытых дверей ТПУ",
  "scopes": ["docs.read"],
  "kind": "demo",
  "days": 7,
  "networks": []
}
EOF

cat << 'EOF' > "$TEMP_DIR/req5.json"
{
  "requester": "Отдел документационного обеспечения",
  "contact": "odo@tpu.ru",
  "purpose": "Поиск по нормативным регламентам и локальным актам для сотрудников ТПУ",
  "scopes": ["docs.read"],
  "kind": "person",
  "days": 14,
  "networks": []
}
EOF

echo "==> Создание 5 заявок на мастер-ключи..."
REQ1_ID=$(create_request "$TEMP_DIR/req1.json")
REQ2_ID=$(create_request "$TEMP_DIR/req2.json")
REQ3_ID=$(create_request "$TEMP_DIR/req3.json")
REQ4_ID=$(create_request "$TEMP_DIR/req4.json")
REQ5_ID=$(create_request "$TEMP_DIR/req5.json")
echo "    Заявки созданы: $REQ1_ID, $REQ2_ID, $REQ3_ID, $REQ4_ID, $REQ5_ID"

echo "==> Одобрение заявки $REQ1_ID..."
cat << 'EOF' > "$TEMP_DIR/approve.json"
{
  "owner": "Сидоров Алексей Михайлович",
  "scopes": ["docs.read", "rasp.read"],
  "days": 30,
  "kind": "person",
  "networks": []
}
EOF

APPROVE_RESP="$TEMP_DIR/approve_resp.json"
APPROVE_HTTP=$(curl -s -o "$APPROVE_RESP" -w '%{http_code}' \
    -X POST "http://127.0.0.1:8081/admin/api/key-requests/${REQ1_ID}/approve" \
    -H "Content-Type: application/json; charset=utf-8" \
    -H "Authorization: Bearer $CONSOLE_KEY" \
    -H "X-Forwarded-For: 127.0.0.1" \
    --data-binary @"$TEMP_DIR/approve.json")

APPROVED_KEY=$(grep -o -E 'mtpu_[A-Za-z0-9_-]{20,}' "$APPROVE_RESP" | head -1 || true)
APPROVED_CLIENT_ID=$(grep -o -E 'cli_[0-9a-f]+' "$APPROVE_RESP" | head -1 || true)

if [ "$APPROVE_HTTP" != "200" ] || [ -z "$APPROVED_KEY" ]; then
    echo "Ошибка одобрения заявки $REQ1_ID (HTTP $APPROVE_HTTP): $(cat "$APPROVE_RESP")" >&2
    exit 1
fi
printf '%s' "$APPROVED_KEY" > "$KEYS_DIR/approved.key"
chmod 600 "$KEYS_DIR/approved.key"
echo "    Заявка $REQ1_ID одобрена -> ключ сохранён в $KEYS_DIR/approved.key (id: $APPROVED_CLIENT_ID)"

echo "==> Отклонение заявки $REQ2_ID с указанием причины..."
cat << 'EOF' > "$TEMP_DIR/reject.json"
{
  "reason": "Для внешних подрядчиков доступ без фиксированных сетей запрещён политикой безопасности"
}
EOF

REJECT_RESP="$TEMP_DIR/reject_resp.json"
REJECT_HTTP=$(curl -s -o "$REJECT_RESP" -w '%{http_code}' \
    -X POST "http://127.0.0.1:8081/admin/api/key-requests/${REQ2_ID}/reject" \
    -H "Content-Type: application/json; charset=utf-8" \
    -H "Authorization: Bearer $CONSOLE_KEY" \
    -H "X-Forwarded-For: 127.0.0.1" \
    --data-binary @"$TEMP_DIR/reject.json")

if [ "$REJECT_HTTP" != "200" ]; then
    echo "Ошибка отклонения заявки $REQ2_ID (HTTP $REJECT_HTTP): $(cat "$REJECT_RESP")" >&2
    exit 1
fi
echo "    Заявка $REQ2_ID отклонена (HTTP $REJECT_HTTP)"

# 6. Итоговая таблица результатов
echo
echo "=========================================================================================================="
echo "                                 ИТОГИ НАПОЛНЕНИЯ СТЕНДА (SEED)"
echo "=========================================================================================================="
printf "%-18s | %-16s | %-16s | %s\n" "Объект" "ID" "Код / Статус" "Описание / Результат"
echo "-------------------+------------------+------------------+------------------------------------------------"
printf "%-18s | %-16s | %-16s | %s\n" "Ключ person 1" "$PERSON1_ID" "$P1_HTTP1,$P1_HTTP2,$P1_HTTP3,$P1_HTTP4" "3 вызова (176.108...), 1 с новой сети РФ (95.165...)"
printf "%-18s | %-16s | %-16s | %s\n" "Ключ person 2" "$PERSON2_ID" "$P2_HTTP" "Политика geo=any, вызов с 8.8.8.8 разрешён"
printf "%-18s | %-16s | %-16s | %s\n" "Ключ server"   "$SERVER_ID"   "$SERVER_HTTP" "Сеть 203.0.113.0/24; вызов с 198.51.100.7 запрещён"
printf "%-18s | %-16s | %-16s | %s\n" "Ключ demo"     "$DEMO_ID"     "$DEMO_HTTP" "ru_only по умолчанию; вызов с 8.8.4.4 -> foreign"
printf "%-18s | %-16s | %-16s | %s\n" "Ключ viewer"   "$VIEWER_ID"   "выдан" "Роль console.viewer -> ~/keys/seed/viewer.key"
printf "%-18s | %-16s | %-16s | %s\n" "Ловушка decoy" "$DECOY_ID"    "$DECOY_HTTP" "Вызов с 203.0.113.99 -> группа приостановлена"
echo "-------------------+------------------+------------------+------------------------------------------------"
printf "%-18s | %-16s | %-16s | %s\n" "Заявка 1 (person)" "$REQ1_ID" "одобрена ($APPROVE_HTTP)" "Выпущен ключ $APPROVED_CLIENT_ID -> approved.key"
printf "%-18s | %-16s | %-16s | %s\n" "Заявка 2 (person)" "$REQ2_ID" "отклонена ($REJECT_HTTP)" "Причина: запрет доступа без сетей подрядчикам"
printf "%-18s | %-16s | %-16s | %s\n" "Заявка 3 (server)" "$REQ3_ID" "новая (201)" "Ожидает решения: server с сетью 198.51.100.0/24"
printf "%-18s | %-16s | %-16s | %s\n" "Заявка 4 (demo)"   "$REQ4_ID" "новая (201)" "Ожидает решения: demo на 7 дней"
printf "%-18s | %-16s | %-16s | %s\n" "Заявка 5 (person)" "$REQ5_ID" "новая (201)" "Ожидает решения: person на 14 дней"
echo "=========================================================================================================="
echo
echo "Ключи доступа к консоли администратора:"
echo "  - Роль console.admin:  ~/keys/console.out (или ~/keys/console.key)"
echo "  - Роль console.viewer: ~/keys/seed/viewer.key"
echo
echo "Наполнение завершено успешно."
