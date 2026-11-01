#!/usr/bin/env bash
# Консоль тестового стенда на ПК одной командой (Git Bash, из корня репозитория):
#   bash deploy/test-server/console.sh            # ключ консоли admin
#   bash deploy/test-server/console.sh --viewer   # ключ «только чтение» (после seed.sh)
# Открывает SSH-туннель 18081 → 127.0.0.1:8081 на сервере, кладёт ключ входа в буфер обмена (на экран не выводит)
# и запускает dev-сервер консоли с proxy.tunnel.json: http://localhost:4200/console. Ctrl+C — закрыть всё.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="$ROOT_DIR/deploy/test-server/server.env"
[ -f "$ENV_FILE" ] || { echo "Нет $ENV_FILE — скопируйте server.env.example и заполните." >&2; exit 1; }
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

KEY_SOURCE="~/keys/console.out"
[ "${1:-}" = "--viewer" ] && KEY_SOURCE="~/keys/seed/viewer.key"

# Ключ — сразу в буфер обмена Windows, мимо экрана и истории
if ssh -o BatchMode=yes "$SERVER" "grep -o -E 'mtpu_[A-Za-z0-9_-]{20,}' $KEY_SOURCE | head -1" | tr -d '\r\n' | clip.exe; then
    echo "Ключ входа скопирован в буфер обмена — вставьте его на экране входа (Ctrl+V)."
else
    echo "Ключ не найден ($KEY_SOURCE на сервере) — выдайте его: bash test-server/issue-key.sh ..." >&2
fi

echo "==> Туннель к шлюзу на сервере (127.0.0.1:18081)..."
# Соединение до сервера иногда сбрасывается: проверка связи раз в 15 с и переподключение, пока открыта консоль
(
    while true; do
        ssh -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
            -o ConnectTimeout=15 -N -L 18081:127.0.0.1:8081 "$SERVER" || true
        echo "Туннель оборвался — переподключение через 3 с" >&2
        sleep 3
    done
) &
TUNNEL_PID=$!
trap 'pkill -P $TUNNEL_PID 2>/dev/null; kill $TUNNEL_PID 2>/dev/null || true' EXIT
sleep 3
curl -s -o /dev/null --max-time 10 http://127.0.0.1:18081/actuator/health \
    || echo "Туннель пока не отвечает (порт 18081 занят или нет связи с сервером) — повторяю в фоне" >&2

echo "==> Консоль: http://localhost:4200/console"
cd "$ROOT_DIR/console"
[ -d node_modules ] || npm ci
npm run start:tunnel
