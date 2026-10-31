#!/usr/bin/env bash
# Остановка стенда на тестовом сервере Сбера.
# Запускается на сервере из каталога ~/mytpu/deploy:
#   bash test-server/down.sh [--purge]
set -euo pipefail

cd "$(dirname "$0")/.."

PURGE=false
for arg in "$@"; do
    case "$arg" in
        --purge)
            PURGE=true
            ;;
        *)
            echo "Неизвестный параметр: $arg" >&2
            echo "Использование: $0 [--purge]" >&2
            exit 1
            ;;
    esac
done

if [ "$PURGE" = true ]; then
    echo "==> Остановка сервисов и удаление томов Docker..."
    docker compose --env-file test-server/server.env \
        -f compose.dev.yml -f compose.test-server.yml \
        down -v
    echo "==> Удаление архива образов и метки загрузки..."
    rm -f ../mytpu-images.tar.gz .images_loaded
    echo "==> Стенд полностью остановлен, данные томов и архив очищены."
else
    echo "==> Остановка сервисов (тома БД и файлов сохраняются)..."
    docker compose --env-file test-server/server.env \
        -f compose.dev.yml -f compose.test-server.yml \
        down
    echo "==> Сервисы остановлены."
fi

echo ""
echo "------------------------------------------------------------------"
echo "Владельцу сервера (с правами sudo) для отключения сайта Caddy:"
echo "1. Удалите строку 'import mytpu.caddy' из /etc/caddy/Caddyfile"
echo "2. Проверьте синтаксис: sudo caddy validate --config /etc/caddy/Caddyfile"
echo "3. Перезагрузите Caddy: sudo systemctl reload caddy"
echo "4. При необходимости удалите файл: sudo rm -f /etc/caddy/mytpu.caddy"
echo "------------------------------------------------------------------"
