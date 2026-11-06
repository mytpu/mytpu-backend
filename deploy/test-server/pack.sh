#!/usr/bin/env bash
# Сборка jar-файлов и docker-образов стенда на ПК разработчика.
# Запуск из Git Bash на Windows в корне проекта или из deploy/test-server:
#   bash deploy/test-server/pack.sh [--skip-build] [--skip-scan]
# Образы проверяет trivy (в Docker, без установки): критичная уязвимость с исправлением — стоп, HIGH — отчёт.
# Рядом с архивом — mytpu-images.tar.gz.sha256; upload.sh и up.sh сверяют по нему архив на сервере.
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
# Кэш базы trivy — на D:, не внутри Docker на C:
TRIVY_CACHE_DIR="${TRIVY_CACHE_DIR:-/d/claude-tmp/trivy-cache}"

# pgvector закреплён по digest индекса образа (тот же образ при каждой упаковке), а в архив попадает под привычным
# тегом: после docker load digest не сохраняется, и compose с image@sha256 полез бы в сеть. Обновлять осознанно
PGVECTOR_TAG="pgvector/pgvector:0.8.7-pg17-bookworm"
PGVECTOR_DIGEST="sha256:7d0a41464797aeb9862dfdc0bbf1526b82cfd324cdd8036df19e3545f4a9ccb8"
# Тот же образ trivy, что в CI (.github/workflows)
TRIVY_IMAGE="aquasec/trivy:0.76.1@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c"
IMAGES=("$PGVECTOR_TAG" mytpu/rag:dev mytpu/gateway:dev mytpu/assistant:dev)

SKIP_BUILD=false
SKIP_SCAN=false
for arg in "$@"; do
    case "$arg" in
        --skip-build)
            SKIP_BUILD=true
            ;;
        --skip-scan)
            SKIP_SCAN=true
            ;;
        *)
            echo "Неизвестный параметр: $arg" >&2
            echo "Использование: $0 [--skip-build] [--skip-scan]" >&2
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

fi

# И при --skip-build: под тегом мог остаться образ, скачанный раньше по тегу, а не по digest
if ! docker image inspect --format '{{join .RepoDigests " "}}' "$PGVECTOR_TAG" 2>/dev/null | grep -q "$PGVECTOR_DIGEST"; then
    echo "==> Загрузка образа postgres по digest..."
    docker pull "pgvector/pgvector@$PGVECTOR_DIGEST"
    docker tag "pgvector/pgvector@$PGVECTOR_DIGEST" "$PGVECTOR_TAG"
fi

if [ "$SKIP_SCAN" = false ]; then
    mkdir -p "$TRIVY_CACHE_DIR"
    # Docker Desktop принимает путь вида D:/...; MSYS_NO_PATHCONV — чтобы Git Bash не переписал путь к сокету
    CACHE_MOUNT="$(cygpath -m "$TRIVY_CACHE_DIR" 2>/dev/null || printf '%s' "$TRIVY_CACHE_DIR")"
    trivy_image() {
        MSYS_NO_PATHCONV=1 docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
            -v "$CACHE_MOUNT:/root/.cache/trivy" "$TRIVY_IMAGE" image --no-progress --ignore-unfixed "$@"
    }
    for image in "${IMAGES[@]}"; do
        echo "==> trivy: $image"
        trivy_image --severity HIGH --exit-code 0 "$image"
        if ! trivy_image --skip-db-update --severity CRITICAL --exit-code 1 "$image"; then
            echo "Ошибка: в $image критичная уязвимость с исправлением — обновите зависимость или базовый образ." >&2
            exit 1
        fi
    done
else
    echo "==> Предупреждение: сканирование trivy пропущено (--skip-scan)."
fi

mkdir -p "$PACK_DIR"
ARCHIVE="$PACK_DIR/mytpu-images.tar.gz"

echo "==> Сохранение четырёх образов в архив $ARCHIVE..."
docker save "${IMAGES[@]}" | gzip > "$ARCHIVE"
(cd "$PACK_DIR" && sha256sum mytpu-images.tar.gz > mytpu-images.tar.gz.sha256)

echo "==> Архив успешно создан:"
ls -lh "$ARCHIVE" | awk '{print $5, $9}'
cat "$ARCHIVE.sha256"
