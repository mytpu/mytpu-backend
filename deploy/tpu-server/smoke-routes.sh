#!/usr/bin/env bash
# Проверка маршрутов Caddy стенда ТПУ без Docker: поднимает настоящий конфиг из deploy/tpu-server/Caddyfile
# и заглушки на месте шлюза (8081), порта консоли (18090) и assistant (8082), затем прогоняет маршруты
# из спецификации (SPA, API консоли, публичные пути, отказы, ограничение по сетям).
#   bash deploy/tpu-server/smoke-routes.sh --caddy /usr/bin/caddy --console-dir console/dist/console/browser
# Печатает «ok: <проверка>» по каждой строке; при любом расхождении — «FAIL» и код 1.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
CADDY="${CADDY:-caddy}"
PYTHON="${PYTHON:-python3}"
CONSOLE_DIR=""
ENV_FILE="$HERE/server.env.example"
TEMPLATE="$HERE/Caddyfile"
PORT=18443

while [ $# -gt 0 ]; do
    case "$1" in
        --caddy) CADDY="$2"; shift 2 ;;
        --python) PYTHON="$2"; shift 2 ;;
        --console-dir) CONSOLE_DIR="$2"; shift 2 ;;
        --env) ENV_FILE="$2"; shift 2 ;;
        --template) TEMPLATE="$2"; shift 2 ;;
        --port) PORT="$2"; shift 2 ;;
        *) echo "smoke-routes: неизвестный аргумент $1" >&2; exit 2 ;;
    esac
done

[ -n "$CONSOLE_DIR" ] && [ -d "$CONSOLE_DIR" ] || { echo "smoke-routes: --console-dir — каталог сборки Angular" >&2; exit 2; }
command -v "$CADDY" >/dev/null || { echo "smoke-routes: нет caddy ($CADDY)" >&2; exit 2; }
command -v "$PYTHON" >/dev/null || { echo "smoke-routes: нет $PYTHON" >&2; exit 2; }

WORK="$(mktemp -d)"
FAILS=0
PIDS=()
cleanup() {
    for pid in "${PIDS[@]:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null || true; done
    wait 2>/dev/null || true
    rm -rf "$WORK"
}
trap cleanup EXIT

# Заглушка сервиса: любой путь — 200 и тело «proxied:<порт>», чтобы отличить проксирование от ответа Caddy
cat > "$WORK/backend.py" <<'PY'
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

port = int(sys.argv[1])
class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def _reply(self):
        body = ("proxied:%d" % port).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)
    do_GET = do_POST = do_HEAD = _reply
    def log_message(self, *args):
        pass

class Server(ThreadingHTTPServer):
    # Caddy закрывает соединения при остановке — шумные трассировки не нужны
    def handle_error(self, request, client_address):
        pass

Server(("127.0.0.1", port), Handler).serve_forever()
PY

for port in 8081 8082 18090; do
    "$PYTHON" "$WORK/backend.py" "$port" & PIDS+=("$!")
done

status() { # status <url> [curl-args...] → код ответа
    local url="$1" code; shift
    code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 "$@" "$url" 2>/dev/null)" || true
    printf '%s' "${code:-000}"
}
body() { # body <url> [curl-args...]
    local url="$1"; shift
    curl -sS --max-time 5 "$@" "$url" 2>/dev/null || true
}
header() { # header <url> <имя-заголовка> [curl-args...]
    local url="$1" name="$2"; shift 2
    curl -sS -o /dev/null -D - --max-time 5 "$@" "$url" 2>/dev/null | tr -d '\r' | awk -v n="$name" 'tolower($0) ~ "^" tolower(n) ":" {sub(/^[^:]+:[ ]*/, ""); print; exit}'
}
expect() { # expect <описание> <ожидаемое> <фактическое>
    if [ "$2" = "$3" ]; then
        echo "ok: $1"
    else
        echo "FAIL: $1 — ожидалось «$2», получено «$3»" >&2
        FAILS=$((FAILS + 1))
    fi
}
expect_any() { # expect_any <описание> <варианты через |> <фактическое>
    case "|$2|" in
        *"|$3|"*) echo "ok: $1" ;;
        *) echo "FAIL: $1 — ожидалось одно из «$2», получено «$3»" >&2; FAILS=$((FAILS + 1)) ;;
    esac
}
expect_contains() {
    case "$3" in
        *"$2"*) echo "ok: $1" ;;
        *) echo "FAIL: $1 — в ответе нет «$2» (получено «$3»)" >&2; FAILS=$((FAILS + 1)) ;;
    esac
}

start_caddy() { # start_caddy <конфиг> → pid
    "$CADDY" run --config "$1" --adapter caddyfile >/dev/null 2>"$WORK/caddy.err" &
    local pid=$!
    PIDS+=("$pid")
    for _ in $(seq 1 50); do
        if [ "$(status "http://127.0.0.1:$PORT/actuator/health")" != "000" ]; then
            echo "$pid"
            return 0
        fi
        sleep 0.2
    done
    echo "smoke-routes: Caddy не поднялся: $(cat "$WORK/caddy.err")" >&2
    return 1
}

stop_caddy() { # stop_caddy <pid>
    kill "$1" 2>/dev/null || true
    for _ in $(seq 1 25); do
        if [ "$(status "http://127.0.0.1:$PORT/actuator/health")" = "000" ]; then return 0; fi
        sleep 0.2
    done
}

render() { # render <networks> <файл> — печатает путь к конфигу, готовому к запуску
    local nets="$1" out="$2"
    {
        # shellcheck disable=SC1090
        set -a; . "$ENV_FILE"; set +a
        printf 'MYTPU_HOST=%s\n' "${MYTPU_HOST:-mytpu.red.tpu.ru}"
        printf 'MYTPU_TLS=upstream\n'
        printf 'MYTPU_LISTEN=127.0.0.1:%s\n' "$PORT"
        printf 'MYTPU_CONSOLE_ROOT=%s\n' "$CONSOLE_DIR"
        printf 'CONSOLE_ALLOWED_NETWORKS="%s"\n' "$nets"
    } > "$WORK/render.env"
    bash "$HERE/render-caddyfile.sh" "$WORK/render.env" "$TEMPLATE" > "$out"
    # Как на сервере: файл маршрутов подключается к основному конфигу. admin off — чтобы прогоны не спорили
    # за административный порт Caddy и не мешали друг другу
    printf '{\n\tadmin off\n}\nimport %s\n' "$(basename "$out")" > "$out.run"
    "$CADDY" validate --config "$out.run" --adapter caddyfile 2>&1 | grep -q 'Valid configuration' \
        || { echo "smoke-routes: caddy validate не принял $out" >&2; return 1; }
    printf '%s\n' "$out.run"
}

asset="$(cd "$CONSOLE_DIR" && ls | grep -E -- '-[A-Za-z0-9]{6,}\.js$' | head -1 || true)"

echo "== Маршруты: консоль открыта разрешённой сети"
open_run="$(render "127.0.0.1/32" "$WORK/open.caddy")" || exit 1
pid="$(start_caddy "$open_run")"; PIDS+=("$pid")
B="http://127.0.0.1:$PORT"

expect "GET /console/ — 200" "200" "$(status "$B/console/")"
expect_contains "GET /console/ — base href" '<base href="/console/' "$(body "$B/console/")"
expect "GET /console/keys — вложенная страница 200" "200" "$(status "$B/console/keys")"
expect_contains "GET /console/keys — отдана SPA" 'app-root' "$(body "$B/console/keys")"
expect "GET /console — редирект 308" "308" "$(status "$B/console")"
expect_contains "GET /console — Location /console/" '/console/' "$(header "$B/console" Location)"
expect "GET /console/index.html — 200" "200" "$(status "$B/console/index.html")"
expect_contains "GET /console/index.html — no-cache" 'no-cache' "$(header "$B/console/index.html" Cache-Control)"
if [ -n "$asset" ]; then
    expect "GET /console/$asset — 200" "200" "$(status "$B/console/$asset")"
    expect_contains "GET /console/$asset — immutable" 'immutable' "$(header "$B/console/$asset" Cache-Control)"
else
    echo "FAIL: в сборке нет файла с хешем в имени — проверка кэша не выполнена" >&2
    FAILS=$((FAILS + 1))
fi
expect "GET /admin/api/keys — через порт консоли" "proxied:18090" "$(body "$B/admin/api/keys")"
expect "POST /admin/api/files — через порт консоли" "proxied:18090" "$(curl -sS --max-time 5 -X POST -H 'Content-Type: application/octet-stream' --data-binary 'x' "$B/admin/api/files" 2>/dev/null || true)"
expect "GET /mcp — MCP-шлюз" "proxied:8081" "$(body "$B/mcp")"
expect "GET /actuator/health — шлюз" "proxied:8081" "$(body "$B/actuator/health")"
expect "GET /api/ai/chat/stream — assistant" "proxied:8082" "$(body "$B/api/ai/chat/stream")"
expect "GET /internal/tools — 404" "404" "$(status "$B/internal/tools")"
expect "GET /admin — 404 (нет API без /api)" "404" "$(status "$B/admin")"
expect "GET / — 404" "404" "$(status "$B/")"
expect_any "GET /console/..%2f..%2fetc/passwd — отказ (400 или 404)" "400|404" "$(status "$B/console/..%2f..%2fetc/passwd")"
expect "GET /mcp/..;/admin/api/keys — 400" "400" "$(status --path-as-is "$B/mcp/..;/admin/api/keys")"
stop_caddy "$pid"

echo "== Маршруты: внешняя сеть (10.0.0.0/8) не входит в разрешённые"
deny_run="$(render "10.0.0.0/8" "$WORK/deny.caddy")" || exit 1
pid="$(start_caddy "$deny_run")"; PIDS+=("$pid")
expect "GET /console/ — 404 чужой сети" "404" "$(status "$B/console/")"
expect "GET /admin/api/keys — 404 чужой сети" "404" "$(status "$B/admin/api/keys")"
expect "GET /mcp — публичный путь не затронут" "proxied:8081" "$(body "$B/mcp")"
stop_caddy "$pid"

echo "== Маршруты: консоль закрыта (CONSOLE_ALLOWED_NETWORKS пуст)"
closed_run="$(render "" "$WORK/closed.caddy")" || exit 1
pid="$(start_caddy "$closed_run")"; PIDS+=("$pid")
expect "GET /console/ — 404 закрытой консоли" "404" "$(status "$B/console/")"
expect "GET /admin/api/keys — 404 закрытой консоли" "404" "$(status "$B/admin/api/keys")"
expect "GET /api/ai/chat/stream — публичный путь не затронут" "proxied:8082" "$(body "$B/api/ai/chat/stream")"
stop_caddy "$pid"

if [ "$FAILS" -ne 0 ]; then
    echo "smoke-routes: провалено проверок — $FAILS" >&2
    exit 1
fi
echo "smoke-routes: все маршруты в порядке"
