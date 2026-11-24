#!/usr/bin/env bash
# Смоук готового стенда на настоящих контейнерах: поднимает compose релиза с тестовыми секретами, рендерит
# конфиг маршрутов, поднимает Caddy и проверяет health, публичные маршруты, консоль и отказы.
# Запускается в CI перед публикацией релиза; на сервере ту же проверку делает mytpu-update.sh.
#   bash deploy/tpu-server/smoke-stack.sh --release <каталог релиза> --console-dir <сборка Angular>
# Образы берутся из GATEWAY_IMAGE/ASSISTANT_IMAGE (в CI — только что собранные), postgres — из compose.
# rag в смоуке не поднимается: ему нужна модель векторов (каталог RAG_MODEL_HOST_DIR) — её запуск проверяется
# на сервере ТПУ при первом обновлении.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
RELEASE=""
CONSOLE_DIR=""
PORT=18443
CADDY="${CADDY:-caddy}"
PROJECT="mytpu-smoke"

while [ $# -gt 0 ]; do
    case "$1" in
        --release) RELEASE="$2"; shift 2 ;;
        --console-dir) CONSOLE_DIR="$2"; shift 2 ;;
        --port) PORT="$2"; shift 2 ;;
        *) echo "smoke-stack: неизвестный аргумент $1" >&2; exit 2 ;;
    esac
done

[ -d "$RELEASE" ] || { echo "smoke-stack: --release — каталог релиза с compose.yml" >&2; exit 2; }
[ -d "$CONSOLE_DIR" ] || { echo "smoke-stack: --console-dir — сборка Angular" >&2; exit 2; }
command -v "$CADDY" >/dev/null || { echo "smoke-stack: нет caddy ($CADDY)" >&2; exit 2; }
for ref in "${GATEWAY_IMAGE:-}" "${ASSISTANT_IMAGE:-}"; do
    case "$ref" in *@sha256:*|*:*) ;; *) echo "smoke-stack: задайте GATEWAY_IMAGE и ASSISTANT_IMAGE" >&2; exit 2 ;; esac
done

WORK="$(mktemp -d)"
CONFIG="$WORK/config"
mkdir -p "$CONFIG/secrets" "$CONFIG/postgres-init" "$WORK/console"
cp -a "$CONSOLE_DIR/." "$WORK/console/"
# Скрипт инициализации БД в compose монтируется из MYTPU_CONFIG_DIR/postgres-init
cp "$RELEASE/postgres/init/01-databases.sh" "$CONFIG/postgres-init/" 2>/dev/null \
    || cp "$HERE/../postgres/init/01-databases.sh" "$CONFIG/postgres-init/"

cleanup() {
    docker compose -p "$PROJECT" -f "$RELEASE/compose.yml" --env-file "$CONFIG/compose.env" down -v \
        >/dev/null 2>&1 || true
    pkill -f "caddy run --config" 2>/dev/null || true
    rm -rf "$WORK"
}
trap cleanup EXIT

# Тестовые секреты: значения случайные, в стенд не попадают
for name in pg_superuser_password gateway_db_password rag_db_password assistant_db_password \
            rag_internal_key gateway_audit_key connector_vault_key rag_admin_key ml_api_key; do
    head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n' > "$CONFIG/secrets/$name"
done
: > "$CONFIG/secrets/assistant_gateway_key"   # без ключа chat.service инструменты помощника выключены
: > "$CONFIG/secrets/assistant_client_key"    # ключ клиента чата в смоуке не проверяем
: > "$CONFIG/secrets/key_alert_webhook"       # оповещения выключены

cat > "$CONFIG/server.env" <<EOF
MYTPU_HOST=mytpu.test
MYTPU_TLS=upstream
MYTPU_LISTEN=127.0.0.1:$PORT
MYTPU_CONSOLE_ROOT=$WORK/console
CONSOLE_ALLOWED_NETWORKS="127.0.0.1/32"
MYTPU_CONFIG_DIR=$CONFIG
MYTPU_PROJECT=$PROJECT
EDGE_SUBNET=172.31.250.0/24
CORE_SUBNET=172.31.251.0/24
TRUSTED_PROXIES=172.31.250.1/32
ADMIN_ALLOWED_NETWORKS=172.31.250.1/32
GATEWAY_PUBLIC_URL=http://127.0.0.1:$PORT
# Модель и база стран в смоуке не нужны: rag не поднимается, проверка страны выключена
RAG_MODEL_HOST_DIR=$WORK/console
GEO_DB_HOST_DIR=$WORK/console
RAG_DATASET_HOST_DIR=$WORK/console
ML_BASE_URL=http://127.0.0.1:9/v1
KEY_GEO_ENABLED=false
# Модели векторов в CI нет: проверяется запуск контейнера rag, поиск выключен
MYTPU_RAG_ENABLED=false
EOF
# compose.env: параметры сервера + ссылки на образы (как это делает mytpu-update.sh)
{
    cat "$CONFIG/server.env"
    printf 'GATEWAY_IMAGE=%s\nASSISTANT_IMAGE=%s\n' "$GATEWAY_IMAGE" "$ASSISTANT_IMAGE"
} > "$CONFIG/compose.env"

COMPOSE_FILE="$RELEASE/compose.yml"
COMPOSE_ENV="$CONFIG/compose.env"
# shellcheck source=/dev/null
. "$HERE/checks.sh"

echo "== поднимаю postgres, rag, gateway и assistant"
# rag без модели: поиск выключен (MYTPU_RAG_ENABLED=false), проверяется запуск контейнера и actuator.
# Полноценный старт rag с моделью векторов подтверждается на сервере ТПУ при первом обновлении
compose up -d postgres rag
wait_until 60 2 postgres_health || { echo "smoke-stack: postgres не поднялся" >&2; exit 1; }
wait_until 90 2 rag_health || { echo "smoke-stack: rag не стал healthy" >&2; exit 1; }
compose up -d --no-deps gateway assistant
wait_until 60 2 gateway_health || { echo "smoke-stack: gateway не стал healthy" >&2; exit 1; }
wait_until 60 2 assistant_health || { echo "smoke-stack: assistant не стал healthy" >&2; exit 1; }

# Секреты контейнерам читают их собственные пользователи: postgres и uid 10001 у Spring-сервисов
compose exec -T rag cat /run/secrets/rag_db_password >/dev/null \
    && echo "ok: rag читает свой секрет" || { echo "smoke-stack: rag не читает секрет" >&2; exit 1; }
compose exec -T gateway cat /run/secrets/gateway_db_password >/dev/null \
    && echo "ok: gateway читает свой секрет" || { echo "smoke-stack: gateway не читает секрет" >&2; exit 1; }
compose exec -T postgres sh -c 'test -s /run/secrets/pg_superuser_password' \
    && echo "ok: postgres получает секрет" || { echo "smoke-stack: postgres не получает секрет" >&2; exit 1; }
if grep -q 'github_token' "$RELEASE/compose.yml"; then
    echo "smoke-stack: compose монтирует github_token в контейнер" >&2; exit 1
fi

echo "== конфиг маршрутов"
bash "$HERE/render-caddyfile.sh" "$CONFIG/server.env" "$RELEASE/Caddyfile" > "$WORK/Caddyfile"
# Как на сервере: файл маршрутов подключается к основному конфигу; admin off — чтобы Caddy смоука
# не спорил за административный порт с другими прогонами на той же машине
printf '{\n\tadmin off\n}\nimport Caddyfile\n' > "$WORK/run.caddy"
"$CADDY" validate --config "$WORK/run.caddy" --adapter caddyfile 2>&1 | grep -q 'Valid configuration' \
    || { echo "smoke-stack: caddy validate не принял конфиг" >&2; exit 1; }
"$CADDY" run --config "$WORK/run.caddy" --adapter caddyfile >/dev/null 2>"$WORK/caddy.err" &
wait_until 50 0.2 bash -c "curl -s -o /dev/null --max-time 2 http://127.0.0.1:$PORT/actuator/health" \
    || { echo "smoke-stack: Caddy не поднялся: $(cat "$WORK/caddy.err")" >&2; exit 1; }

echo "== маршруты и коды ответов"
# Те же значения, что в сгенерированном server.env: проверки маршрутов читают их из окружения
MYTPU_TLS=upstream
MYTPU_LISTEN="127.0.0.1:$PORT"
MYTPU_HOST=mytpu.test
CONSOLE_ALLOWED_NETWORKS="127.0.0.1/32"
BASE_URL="http://$MYTPU_LISTEN"
export MYTPU_TLS MYTPU_LISTEN MYTPU_HOST CONSOLE_ALLOWED_NETWORKS BASE_URL
check_routes || { echo "smoke-stack: маршруты не в порядке" >&2; exit 1; }

# Загрузка документа больше 2 МБ через консоль: Caddy и настоящий gateway должны принять тело
dd if=/dev/zero of="$WORK/three" bs=1M count=3 2>/dev/null \
    || dd if=/dev/zero of="$WORK/three" bs=1048576 count=3 2>/dev/null
upload_code="$(http_code "$BASE_URL/admin/api/rag/files" -X POST \
    -H 'Content-Type: application/octet-stream' --data-binary @"$WORK/three")"
if [ "$upload_code" = "413" ]; then
    echo "smoke-stack: загрузка 3 МБ отклонена по размеру тела (413)" >&2
    exit 1
fi
echo "ok: тело 3 МБ принято консольным API ($upload_code)"

echo "smoke-stack: готовые контейнеры и маршруты в порядке"
