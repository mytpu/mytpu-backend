#!/usr/bin/env bash
# Сборка jar-файлов и docker-образов стенда на ПК разработчика.
# Запуск из Git Bash на Windows в корне проекта или из deploy/test-server:
#   bash deploy/test-server/pack.sh [--skip-build]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT_DIR"

ENV_FILE="$ROOT_DIR/deploy/test-server/server.env"
if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
fi

PACK_DIR="${PACK_DIR:-/d/claude-tmp/mytpu-pack}"

SKIP_BUILD=false
for arg in "$@"; do
    case "$arg" in
        --skip-build)
            SKIP_BUILD=true
            ;;
        *)
            echo "Неизвестный параметр: $arg" >&2
            echo "Использование: $0 [--skip-build]" >&2
            exit 1
            ;;
    esac
done

if [ "$SKIP_BUILD" = false ]; then
    echo "==> Сборка jar-пакетов Maven..."
    # Как в .claude/verify.sh: JDK 25 и Maven из IDEA, если mvn нет в PATH
    export JAVA_HOME="${JAVA_HOME_25:-$HOME/.jdks/openjdk-25.0.2}"
    if [ -z "${MVN:-}" ]; then
        if command -v mvn >/dev/null 2>&1; then
            MVN=mvn
        else
            MVN="C:/Program Files/JetBrains/IntelliJ IDEA 2025.3.1/plugins/maven/lib/maven3/bin/mvn"
        fi
    fi
    export MAVEN_OPTS="${MAVEN_OPTS:-} --sun-misc-unsafe-memory-access=allow"
    "$MVN" -q -B -ntp -Dmaven.repo.local="${M2_REPO:-D:/m2/repository}" package -DskipTests

    echo "==> Сборка образов rag, gateway, assistant..."
    docker compose -f deploy/compose.dev.yml build rag gateway assistant

    echo "==> Загрузка базового образа postgres..."
    docker pull pgvector/pgvector:0.8.1-pg17
fi

mkdir -p "$PACK_DIR"
ARCHIVE="$PACK_DIR/mytpu-images.tar.gz"

echo "==> Сохранение четырёх образов в архив $ARCHIVE..."
docker save \
    pgvector/pgvector:0.8.1-pg17 \
    mytpu/rag:dev \
    mytpu/gateway:dev \
    mytpu/assistant:dev | gzip > "$ARCHIVE"

echo "==> Архив успешно создан:"
ls -lh "$ARCHIVE" | awk '{print $5, $9}'
