#!/usr/bin/env bash
# Запуск стенда на тестовом сервере Сбера.
# Запускается на сервере из каталога ~/mytpu/deploy:
#   bash test-server/up.sh [--ingest]
set -euo pipefail

cd "$(dirname "$0")/.."

# 1. Проверка доступности Docker без sudo
if ! docker info >/dev/null 2>&1; then
    echo "Ошибка: docker недоступен для текущего пользователя без прав администратора." >&2
    echo "Владелец сервера: см. README.md, раздел «Один раз (владелец, с правами администратора)»." >&2
    exit 1
fi

# 2. Проверка наличия файла конфигурации
if [ ! -f "test-server/server.env" ]; then
    echo "Ошибка: test-server/server.env не найден." >&2
    echo "Скопируйте test-server/server.env.example в test-server/server.env и настройте переменные." >&2
    exit 1
fi

# 3. Загрузка docker-образов, если архив новее метки
ARCHIVE="../mytpu-images.tar.gz"
MARKER=".images_loaded"

if [ -f "$ARCHIVE" ]; then
    if [ ! -f "$MARKER" ] || [ "$ARCHIVE" -nt "$MARKER" ]; then
        # Архив без суммы или с несовпадающей суммой не загружаем: недокачанный или подменённый архив
        if [ ! -f "$ARCHIVE.sha256" ]; then
            echo "Ошибка: нет $ARCHIVE.sha256 — перенесите архив заново (pack.sh, затем upload.sh --images-only)." >&2
            exit 1
        fi
        if ! (cd "$(dirname "$ARCHIVE")" && sha256sum -c --quiet "$(basename "$ARCHIVE").sha256"); then
            echo "Ошибка: контрольная сумма $ARCHIVE не совпала — архив не загружен." >&2
            exit 1
        fi
        echo "==> Загрузка docker-образов из $ARCHIVE..."
        docker load < "$ARCHIVE"
        touch "$MARKER"
        echo "==> Образы успешно загружены."
    else
        echo "==> Архив образов не изменялся с момента последней загрузки (пропуск)."
    fi
else
    echo "==> Предупреждение: архив $ARCHIVE не найден, предполагаются предзагруженные образы."
fi

# 4. Создание секретов (если ещё не созданы)
bash dev-secrets.sh

# Ключ клиента чата: чат открыт наружу, без ключа он доступен всем. Пустой файл выключил бы проверку — не допускаем
if [ ! -s secrets/assistant_client_key ]; then
    head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n' > secrets/assistant_client_key
    chmod 644 secrets/assistant_client_key
    echo "создан secrets/assistant_client_key (заголовок X-Assistant-Key для /api/ai/*)"
fi

# 5. Проверка ключа ml-server
if [ ! -f "secrets/.ml_api_key_set" ]; then
    echo "------------------------------------------------------------------"
    echo "НАПОМИНАНИЕ: файл secrets/ml_api_key содержит случайное dev-значение."
    echo "Для взаимодействия assistant с ml-server впишите ключ ml-server:"
    echo "  printf '%s' '<ключ ml-server>' > secrets/ml_api_key"
    echo "  touch secrets/.ml_api_key_set"
    echo "------------------------------------------------------------------"
fi

# 6. Запуск основных сервисов
DO_INGEST=false
for arg in "$@"; do
    case "$arg" in
        --ingest)
            DO_INGEST=true
            ;;
        *)
            echo "Неизвестный параметр: $arg" >&2
            echo "Использование: $0 [--ingest]" >&2
            exit 1
            ;;
    esac
done

echo "==> Запуск сервисов postgres, rag, gateway, assistant..."
docker compose --env-file test-server/server.env \
    -f compose.dev.yml -f compose.test-server.yml \
    up -d --no-build postgres rag gateway assistant

# 7. Ожидание готовности gateway (до 120 с)
echo "==> Ожидание готовности gateway (до 120 с)..."
READY=false
for i in $(seq 1 60); do
    if curl -fsS http://127.0.0.1:8081/actuator/health >/dev/null 2>&1; then
        READY=true
        break
    fi
    sleep 2
done

if [ "$READY" = false ]; then
    echo "Ошибка: gateway не стал healthy за 120 с." >&2
    docker compose --env-file test-server/server.env -f compose.dev.yml -f compose.test-server.yml ps >&2
    exit 1
fi

echo "==> Стенд успешно поднят и готов к работе."
docker compose --env-file test-server/server.env -f compose.dev.yml -f compose.test-server.yml ps

# 8. Загрузка корпуса rag (если запрошена)
if [ "$DO_INGEST" = true ]; then
    echo "==> Запуск загрузки корпуса (rag-ingest)..."
    docker compose --env-file test-server/server.env \
        -f compose.dev.yml -f compose.test-server.yml \
        --profile ingest run --rm rag-ingest
    echo "==> Загрузка корпуса завершена."
fi
