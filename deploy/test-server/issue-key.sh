#!/usr/bin/env bash
# Выдача мастер-ключа на тестовом сервере (из каталога ~/mytpu/deploy). Параметры — как у issue-key в ApiKeyIssuer:
#   bash test-server/issue-key.sh --owner="Тестовый агент" --scopes=docs.read,rasp.read
#   bash test-server/issue-key.sh --owner=console-admin --roles=console.admin
# Ключ печатается один раз; сохраняйте вывод в файл с правами 600, а не в историю терминала.
set -euo pipefail
# Файл, в который перенаправлен вывод с ключом, создаётся только для владельца
umask 077
cd "$(dirname "$0")/.."

docker compose --env-file test-server/server.env -f compose.dev.yml -f compose.test-server.yml \
    run --rm --no-deps -T gateway --spring.profiles.active=issue-key "$@"
