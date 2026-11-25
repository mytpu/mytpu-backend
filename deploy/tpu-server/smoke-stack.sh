#!/usr/bin/env bash
# Смоук готового стенда на настоящих контейнерах: поднимает compose релиза с тестовыми секретами, рендерит
# конфиг маршрутов, поднимает Caddy и проверяет health, публичные маршруты, консоль и отказы.
# Запускается в CI перед публикацией релиза; на сервере ту же проверку делает mytpu-update.sh.
#   bash deploy/tpu-server/smoke-stack.sh --release <каталог> --console-dir <сборка Angular> --model-dir <модель e5>
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
RELEASE=""
CONSOLE_DIR=""
MODEL_DIR=""
PORT=18443
CADDY="${CADDY:-caddy}"
PROJECT="mytpu-smoke-$$"

while [ $# -gt 0 ]; do
    case "$1" in
        --release) RELEASE="$2"; shift 2 ;;
        --console-dir) CONSOLE_DIR="$2"; shift 2 ;;
        --model-dir) MODEL_DIR="$2"; shift 2 ;;
        --port) PORT="$2"; shift 2 ;;
        *) echo "smoke-stack: неизвестный аргумент $1" >&2; exit 2 ;;
    esac
done

[ -d "$RELEASE" ] || { echo "smoke-stack: --release — каталог релиза с compose.yml" >&2; exit 2; }
[ -d "$CONSOLE_DIR" ] || { echo "smoke-stack: --console-dir — сборка Angular" >&2; exit 2; }
[ -s "$MODEL_DIR/model.onnx" ] && [ -s "$MODEL_DIR/tokenizer.json" ] \
    || { echo "smoke-stack: --model-dir — каталог с моделью и токенизатором" >&2; exit 2; }
MODEL_DIR="$(cd "$MODEL_DIR" && pwd)"
command -v "$CADDY" >/dev/null || { echo "smoke-stack: нет caddy ($CADDY)" >&2; exit 2; }
for ref in "${GATEWAY_IMAGE:-}" "${RAG_IMAGE:-}" "${ASSISTANT_IMAGE:-}"; do
    case "$ref" in *@sha256:*|*:*) ;; *) echo "smoke-stack: задайте GATEWAY_IMAGE, RAG_IMAGE и ASSISTANT_IMAGE" >&2; exit 2 ;; esac
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
    if [ -n "${CADDY_PID:-}" ]; then kill "$CADDY_PID" 2>/dev/null || true; fi
    rm -rf "$WORK"
}
trap cleanup EXIT

# CI скачивает исходную модель Xenova. Лимит памяти стенда рассчитан на версию с урезанным словарём.
if [ "${GITHUB_ACTIONS:-}" = true ]; then
    vocab_url=https://huggingface.co/d0rj/e5-small-en-ru/resolve/8d9d26ce7447cedb9dba7c33587dbed883e293bd/tokenizer.json
    curl -fsSL --retry 3 --max-time 60 "$vocab_url" -o "$WORK/vocab.json"
    printf '%s  %s\n' df7609bacb8869a7fd698625aa65f6f1bcbbc4d621432d286e631ed0efe02dc6 "$WORK/vocab.json" | sha256sum -c -
    python3 -m venv "$WORK/model-tools"
    "$WORK/model-tools/bin/python" -m pip install --only-binary=:all: 'numpy==2.5.4' 'onnx==1.23.2'
    "$WORK/model-tools/bin/python" "$HERE/../../tools/rag/trim_e5_vocab.py" \
        --model "$MODEL_DIR" --pruned-tokenizer "$WORK/vocab.json" --out "$WORK/model" --no-check
    MODEL_DIR="$WORK/model"
fi

# Тестовые секреты: значения случайные, в стенд не попадают
for name in pg_superuser_password gateway_db_password rag_db_password assistant_db_password \
            rag_internal_key gateway_audit_key connector_vault_key rag_admin_key ml_api_key; do
    head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n' > "$CONFIG/secrets/$name"
done
: > "$CONFIG/secrets/assistant_gateway_key"   # без ключа chat.service инструменты помощника выключены
head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n' > "$CONFIG/secrets/assistant_client_key"
: > "$CONFIG/secrets/key_alert_webhook"       # оповещения выключены
chmod 700 "$CONFIG/secrets"
chmod 644 "$CONFIG/secrets"/*

cat > "$CONFIG/server.env" <<EOF
MYTPU_HOST=mytpu.test
MYTPU_TLS=upstream
MYTPU_LISTEN=127.0.0.1:$PORT
MYTPU_CONSOLE_ROOT=$WORK/console
CONSOLE_ALLOWED_NETWORKS="127.0.0.1/32"
MYTPU_CONFIG_DIR=$CONFIG
MYTPU_PROJECT=$PROJECT
MYTPU_PGDATA_VOLUME=$PROJECT-pgdata
MYTPU_RAG_FILES_VOLUME=$PROJECT-rag-files
EDGE_SUBNET=172.31.250.0/24
CORE_SUBNET=172.31.251.0/24
TRUSTED_PROXIES=172.31.250.1/32
ADMIN_ALLOWED_NETWORKS=172.31.250.1/32
GATEWAY_PUBLIC_URL=http://127.0.0.1:$PORT
RAG_MODEL_HOST_DIR="$MODEL_DIR"
GEO_DB_HOST_DIR=$WORK/console
RAG_DATASET_HOST_DIR=$WORK/console
ML_BASE_URL=http://127.0.0.1:9/v1
KEY_GEO_ENABLED=false
EOF
# compose.env: параметры сервера + ссылки на образы (как это делает mytpu-update.sh)
{
    cat "$CONFIG/server.env"
    printf 'GATEWAY_IMAGE=%s\nRAG_IMAGE=%s\nASSISTANT_IMAGE=%s\n' "$GATEWAY_IMAGE" "$RAG_IMAGE" "$ASSISTANT_IMAGE"
} > "$CONFIG/compose.env"

COMPOSE_FILE="$RELEASE/compose.yml"
COMPOSE_ENV="$CONFIG/compose.env"
# shellcheck source=/dev/null
. "$HERE/checks.sh"

echo "== поднимаю postgres, rag, gateway и assistant"
compose config --quiet
compose up -d postgres rag
wait_until 60 2 postgres_health || { echo "smoke-stack: postgres не поднялся" >&2; exit 1; }
if ! wait_until 90 2 rag_health; then
    echo "smoke-stack: rag не стал healthy" >&2
    compose ps -a >&2 || true
    compose logs --no-color --tail 120 rag postgres >&2 || true
    exit 1
fi
compose up -d --no-deps gateway assistant
wait_until 60 2 gateway_health || { echo "smoke-stack: gateway не стал healthy" >&2; exit 1; }
wait_until 60 2 assistant_health || { echo "smoke-stack: assistant не стал healthy" >&2; exit 1; }

# Секреты контейнерам читают их собственные пользователи: postgres и uid 10001 у Spring-сервисов
compose exec -T rag cat /run/secrets/rag_db_password >/dev/null \
    && echo "ok: rag читает свой секрет" || { echo "smoke-stack: rag не читает секрет" >&2; exit 1; }
compose exec -T gateway cat /run/secrets/gateway_db_password >/dev/null \
    && echo "ok: gateway читает свой секрет" || { echo "smoke-stack: gateway не читает секрет" >&2; exit 1; }
compose exec -T --user postgres postgres cat /run/secrets/pg_superuser_password >/dev/null \
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
CADDY_PID=$!
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

# Консоль отправляет multipart и получает 202 с идентификаторами источника и задания.
issue_console_key() {
    compose run --rm --no-deps -T gateway --spring.profiles.active=issue-key \
        --owner="ci-$1" --roles="$1" | grep -E '^mtpu_[A-Za-z0-9_-]+$'
}
admin_key="$(issue_console_key console.admin)"
viewer_key="$(issue_console_key console.viewer)"
python3 - "$WORK/document.html" <<'PY'
from pathlib import Path
import sys

Path(sys.argv[1]).write_text("<html><body><p>Проверка загрузки.</p><!--" + "x" * (3 * 1024**2) + "--></body></html>", encoding="utf-8")
PY
upload_code="$(http_code "$BASE_URL/admin/api/rag/sources/files" \
    --config <(printf 'header = "Authorization: Bearer %s"\n' "$admin_key") \
    -F "file=@$WORK/document.html;type=text/html")"
[ "$upload_code" = 202 ] || { echo "smoke-stack: загрузка документа вернула $upload_code, ожидался 202" >&2; exit 1; }
echo "ok: документ больше 2 МБ принят консольным API (202)"

viewer_code="$(http_code "$BASE_URL/admin/api/keys" \
    --config <(printf 'header = "Authorization: Bearer %s"\n' "$viewer_key"))"
[ "$viewer_code" = 200 ] || { echo "smoke-stack: чтение с console.viewer вернуло $viewer_code" >&2; exit 1; }
viewer_code="$(http_code "$BASE_URL/admin/api/rag/sources/files" \
    --config <(printf 'header = "Authorization: Bearer %s"\n' "$viewer_key") \
    -F "file=@$WORK/document.html;type=text/html")"
[ "$viewer_code" = 403 ] || { echo "smoke-stack: запись с console.viewer вернула $viewer_code" >&2; exit 1; }

echo "smoke-stack: готовые контейнеры и маршруты в порядке"
