#!/usr/bin/env bash
# Перенос образов, конфигураций и данных на тестовый сервер.
# Запуск из Git Bash на Windows в корне проекта:
#   bash deploy/test-server/upload.sh [--images-only | --files-only] [--force-data]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT_DIR"

ENV_FILE="$ROOT_DIR/deploy/test-server/server.env"
if [ ! -f "$ENV_FILE" ]; then
    echo "Ошибка: файл $ENV_FILE не найден." >&2
    echo "Создайте его на основе deploy/test-server/server.env.example." >&2
    exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

# Путь от домашнего каталога на сервере (ssh и scp считают относительный путь от него); «~» здесь раскрылся бы на ПК
REMOTE_DIR="${REMOTE_DIR:-mytpu}"
PACK_DIR="${PACK_DIR:-/d/claude-tmp/mytpu-pack}"

IMAGES_ONLY=false
FILES_ONLY=false
FORCE_DATA=false

for arg in "$@"; do
    case "$arg" in
        --images-only)
            IMAGES_ONLY=true
            ;;
        --files-only)
            FILES_ONLY=true
            ;;
        --force-data)
            FORCE_DATA=true
            ;;
        *)
            echo "Неизвестный параметр: $arg" >&2
            echo "Использование: $0 [--images-only | --files-only] [--force-data]" >&2
            exit 1
            ;;
    esac
done

if [ "$IMAGES_ONLY" = true ] && [ "$FILES_ONLY" = true ]; then
    echo "Ошибка: нельзя указывать одновременно --images-only и --files-only." >&2
    exit 1
fi

echo "==> Создание каталогов на сервере $SERVER..."
ssh "$SERVER" "mkdir -p $REMOTE_DIR/deploy/postgres/init $REMOTE_DIR/deploy/test-server $REMOTE_DIR/models $REMOTE_DIR/dataset $REMOTE_DIR/geo"

# 1. Перенос архива образов
if [ "$FILES_ONLY" = false ]; then
    ARCHIVE="$PACK_DIR/mytpu-images.tar.gz"
    if [ ! -f "$ARCHIVE" ]; then
        echo "Ошибка: архив $ARCHIVE не найден. Запустите сначала pack.sh." >&2
        exit 1
    fi
    echo "==> Перенос архива образов в $REMOTE_DIR/mytpu-images.tar.gz..."
    scp "$ARCHIVE" "$SERVER:$REMOTE_DIR/mytpu-images.tar.gz"
fi

# 2. Перенос файлов конфигурации, скриптов и данных
if [ "$IMAGES_ONLY" = false ]; then
    echo "==> Перенос конфигураций Compose и скриптов..."
    scp "$ROOT_DIR/deploy/compose.dev.yml" \
        "$ROOT_DIR/deploy/compose.test-server.yml" \
        "$ROOT_DIR/deploy/dev-secrets.sh" \
        "$SERVER:$REMOTE_DIR/deploy/"

    scp "$ROOT_DIR/deploy/postgres/init/"* "$SERVER:$REMOTE_DIR/deploy/postgres/init/"

    scp "$ROOT_DIR/deploy/test-server/up.sh" \
        "$ROOT_DIR/deploy/test-server/down.sh" \
        "$ROOT_DIR/deploy/test-server/measure.sh" \
        "$ROOT_DIR/deploy/test-server/issue-key.sh" \
        "$ROOT_DIR/deploy/test-server/seed.sh" \
        "$ROOT_DIR/deploy/load/mcp-load.py" \
        "$SERVER:$REMOTE_DIR/deploy/test-server/"

    scp "$ENV_FILE" "$SERVER:$REMOTE_DIR/deploy/test-server/server.env"

    echo "==> Рендеринг Caddyfile для $MYTPU_HOST..."
    TMP_CADDY="$(mktemp)"
    sed "s/{{MYTPU_HOST}}/${MYTPU_HOST}/g" "$ROOT_DIR/deploy/test-server/Caddyfile.mytpu" > "$TMP_CADDY"
    scp "$TMP_CADDY" "$SERVER:$REMOTE_DIR/deploy/test-server/mytpu.caddy"
    rm -f "$TMP_CADDY"

    ssh "$SERVER" "chmod +x $REMOTE_DIR/deploy/dev-secrets.sh $REMOTE_DIR/deploy/test-server/*.sh"

    # 3. Перенос модели
    if [ "$FORCE_DATA" = true ] || ! ssh "$SERVER" "test -e $REMOTE_DIR/models/e5-small-en-ru-int8"; then
        if [ -n "${MODEL_DIR:-}" ] && [ -d "$MODEL_DIR" ]; then
            echo "==> Перенос модели e5 из $MODEL_DIR..."
            scp -r "$MODEL_DIR" "$SERVER:$REMOTE_DIR/models/e5-small-en-ru-int8"
        else
            echo "Предупреждение: MODEL_DIR не задан или не найден, пропуск переноса модели."
        fi
    else
        echo "Модель уже существует на сервере (пропуск; для перезаписи используйте --force-data)."
    fi

    # 4. Перенос датасета
    if [ "$FORCE_DATA" = true ] || ! ssh "$SERVER" "test -e $REMOTE_DIR/dataset/help-tpu.json"; then
        if [ -n "${DATASET_DIR:-}" ] && [ -d "$DATASET_DIR" ]; then
            echo "==> Перенос датасета из $DATASET_DIR..."
            scp -r "$DATASET_DIR"/* "$SERVER:$REMOTE_DIR/dataset/"
        else
            echo "Предупреждение: DATASET_DIR не задан или не найден, пропуск переноса датасета."
        fi
    else
        echo "Корпус документов уже существует на сервере (пропуск; для перезаписи используйте --force-data)."
    fi

    # 5. Перенос базы стран
    if [ "$FORCE_DATA" = true ] || ! ssh "$SERVER" "test -e $REMOTE_DIR/geo/dbip-country-lite.mmdb"; then
        if [ -n "${GEO_DB:-}" ] && [ -f "$GEO_DB" ]; then
            echo "==> Перенос базы стран GeoIP из $GEO_DB..."
            scp "$GEO_DB" "$SERVER:$REMOTE_DIR/geo/dbip-country-lite.mmdb"
        else
            echo "Предупреждение: GEO_DB не задан или не найден, пропуск переноса базы стран."
        fi
    else
        echo "База стран уже существует на сервере (пропуск; для перезаписи используйте --force-data)."
    fi
fi

echo "==> Перенос завершён успешно."
