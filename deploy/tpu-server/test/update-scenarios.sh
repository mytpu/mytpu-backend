#!/usr/bin/env bash
# Сценарии обновления стенда ТПУ без Docker и без сети: настоящий mytpu-update.sh с заглушками docker, caddy,
# systemctl, flock и curl. Проверяет первый запуск, повтор, новую версию, гонку, обрыв загрузки, ошибку
# проверок, откат и ручной откат. Запускается локально и в CI:
#   bash deploy/tpu-server/test/update-scenarios.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TPU="$(cd "$HERE/.." && pwd)"
WORK="$(mktemp -d)"
PASS=0
FAIL=0
RUNS=0

cleanup() {
    if [ "${UPDATE_SCENARIOS_KEEP:-0}" = 1 ]; then
        echo "update-scenarios: каталог сценариев сохранён: $WORK" >&2
        return
    fi
    rm -rf "$WORK"
}
trap cleanup EXIT

ok() { echo "ok: $*"; PASS=$((PASS + 1)); }
bad() { echo "FAIL: $*" >&2; FAIL=$((FAIL + 1)); }
expect_eq() { [ "$2" = "$3" ] && ok "$1" || bad "$1 — ожидалось «$2», получено «$3»"; }
expect_contains() { case "$3" in *"$2"*) ok "$1" ;; *) bad "$1 — нет «$2» в «$3»" ;; esac; }

# ---------- заглушки команд ----------
STUBS="$WORK/bin"
mkdir -p "$STUBS"

cat > "$STUBS/id" <<'SH'
#!/usr/bin/env bash
echo 0
SH

cat > "$STUBS/sleep" <<'SH'
#!/usr/bin/env bash
# Паузы ожидания не нужны: сценарии проверяют логику, а не время
exit 0
SH

cat > "$STUBS/flock" <<'SH'
#!/usr/bin/env bash
# Настоящий flock держит блокировку на дескрипторе. Заглушка изображает «блокировку держит другой запуск»,
# когда сценарий создаёт файл blocked: mytpu-update.sh должен в этом случае пропустить запуск
[ -f "${STUB_DIR:?}/blocked" ] && exit 1
exit 0
SH

cat > "$STUBS/systemctl" <<'SH'
#!/usr/bin/env bash
echo "systemctl $*" >> "${STUB_DIR:?}/commands.log"
SH

cat > "$STUBS/caddy" <<'SH'
#!/usr/bin/env bash
case "${1:-}" in
    validate) echo "Valid configuration" ;;
    version) echo "v2.11.9 test" ;;
    *) echo "caddy $*" >> "${STUB_DIR:?}/commands.log" ;;
esac
SH

cat > "$STUBS/docker" <<'SH'
#!/usr/bin/env bash
log() { echo "docker $*" >> "${STUB_DIR:?}/commands.log"; }
log "$@"
case "${1:-}" in
    login) cat >/dev/null; exit 0 ;;
    logout) exit 0 ;;
    pull)
        # Обрыв загрузки: недоступный образ
        if [ -f "$STUB_DIR/fail-pull" ] && grep -q "$(basename "${2:-}")" "$STUB_DIR/fail-pull"; then
            echo "manifest unknown" >&2; exit 1
        fi
        exit 0 ;;
    image) exit 0 ;;
    compose)
        shift
        # Строгая проверка как у настоящего docker: без существующего файла -f compose не принимается
        cfile=""
        prev_arg=""
        for arg in "$@"; do
            [ "$prev_arg" = "-f" ] && cfile="$arg"
            prev_arg="$arg"
        done
        init="no"
        [ -f "${MYTPU_CONFIG_DIR:?}/postgres-init/01-databases.sh" ] && init="yes"
        case " $* " in
            *" config "*)
                [ -n "$cfile" ] && [ -f "$cfile" ] || { echo "no configuration file provided: $cfile" >&2; exit 1; }
                echo "compose config file=$cfile init=$init" >> "$STUB_DIR/commands.log"
                exit 0 ;;
            *" up "*)
                [ -n "$cfile" ] && [ -f "$cfile" ] || { echo "no configuration file provided: $cfile" >&2; exit 1; }
                echo "compose up file=$cfile init=$init" >> "$STUB_DIR/commands.log"
                # Ошибка запуска нового релиза
                if [ -f "$STUB_DIR/fail-up" ]; then echo "container failed" >&2; exit 1; fi
                exit 0 ;;
            *" exec "*)
                # rag_health спрашивает actuator изнутри контейнера — отвечаем как живой сервис
                case " $* " in *" rag "*) echo '{"status":"UP"}' ;; esac
                exit 0 ;;
            *) exit 0 ;;
        esac ;;
    *) exit 0 ;;
esac
SH

cat > "$STUBS/curl" <<'SH'
#!/usr/bin/env bash
# Заглушка curl: GitHub API и файлы релиза — из фикстуры; проверки маршрутов — по режиму health
url="" out="" write_out=""
while [ $# -gt 0 ]; do
    case "$1" in
        -o) out="$2"; shift 2 ;;
        -w) write_out="$2"; shift 2 ;;
        --config|-H|--max-time|-m|--data|--data-binary|--resolve) shift 2 ;;
        -*) shift ;;
        *) url="$1"; shift ;;
    esac
done
url="${url%$'\r'}"
out="${out%$'\r'}"

case "$url" in
    https://api.github.com/*/releases/assets/*)
        # Скачивание файла релиза через API (Accept: application/octet-stream): соответствие url → файл
        if [ -f "${STUB_FIXTURE:?}/assets.map" ]; then
            name="$(awk -v u="$url" '$1 == u { print $2 }' "$STUB_FIXTURE/assets.map")"
            if [ -n "$name" ] && [ -f "$STUB_FIXTURE/$name" ]; then cp "$STUB_FIXTURE/$name" "$out"; exit 0; fi
        fi
        echo "404: Not Found" >&2; exit 22 ;;
    https://api.github.com/*)
        cp "${STUB_FIXTURE:?}/api.json" "$out"; exit 0 ;;
    https://github.com/*/releases/download/*/*)
        name="$(basename "$url")"
        [ -f "$STUB_FIXTURE/$name" ] || { echo "404: Not Found" >&2; exit 22; }
        cp "$STUB_FIXTURE/$name" "$out"; exit 0 ;;
esac

health="ok"
[ -f "$STUB_DIR/health" ] && health="$(cat "$STUB_DIR/health")"
# Режим «маршруты не проходят»: health отвечает UP, остальные маршруты — 500 (сбой после переключения)
if [ -f "$STUB_DIR/routes" ]; then
    case "$url" in
        *"/actuator/health") : ;;
        *)
            if [ -n "$out" ] && [ "$out" != "/dev/null" ]; then printf '%s' "routes broken" > "$out"; fi
            if [ -z "$out" ]; then printf '%s' "routes broken"; fi
            [ -n "$write_out" ] && printf '%s' "500"
            exit 22 ;;
    esac
fi
case "$url" in
    *"/actuator/health")
        if [ "$health" = fail ]; then code=503; body='{"status":"DOWN"}'; else code=200; body='{"status":"UP"}'; fi ;;
    *"/mcp/..;/admin/api/keys") code=400; body="bad request" ;;
    *":8081/admin/api/keys") code=404; body="not found" ;;
    *"/console/") code=200; body='<html><base href="/console/"></html>' ;;
    *"/console/keys") code=200; body='<app-root></app-root>' ;;
    *"/admin/api/keys") code=401; body='{"error":"unauthorized"}' ;;
    *"/mcp"|*"/api/ai/chat") code=401; body='{"error":"unauthorized"}' ;;
    *) code=404; body="not found" ;;
esac
if [ -n "$out" ] && [ "$out" != "/dev/null" ]; then
    printf '%s' "$body" > "$out"
elif [ -z "$out" ]; then
    printf '%s' "$body"
fi
[ -n "$write_out" ] && printf '%s' "$code"
[ "$code" -lt 400 ] && exit 0 || exit 22
SH

chmod +x "$STUBS"/*

# ---------- окружение сценария ----------
ROOT=""
new_scenario() { # new_scenario <имя>
    ROOT="$WORK/$1"
    rm -rf "$ROOT"
    mkdir -p "$ROOT/config/secrets" "$ROOT/state" "$ROOT/releases" "$ROOT/cache" "$ROOT/caddy" "$ROOT/stub"
    STUB_DIR="$ROOT/stub"
    STUB_FIXTURE="$WORK/fixture"
    for name in pg_superuser_password gateway_db_password rag_db_password assistant_db_password \
                rag_internal_key gateway_audit_key connector_vault_key rag_admin_key ml_api_key; do
        printf 'x' > "$ROOT/config/secrets/$name"
    done
    : > "$ROOT/config/secrets/assistant_gateway_key"
    : > "$ROOT/config/secrets/assistant_client_key"
    : > "$ROOT/config/secrets/key_alert_webhook"
    cat > "$ROOT/config/server.env" <<EOF
MYTPU_HOST=mytpu.test
MYTPU_TLS=upstream
MYTPU_LISTEN=127.0.0.1:8443
CONSOLE_ALLOWED_NETWORKS="127.0.0.1/32"
MYTPU_REPO=mytpu/mytpu-backend
MYTPU_CHANNEL=latest
GHCR_REGISTRY=ghcr.io
GHCR_USER=tester
MYTPU_CONFIG_DIR=$ROOT/config
MYTPU_LOCK_FILE=$ROOT/state/update.lock
EOF
}

run_update() { # run_update [аргументы] → код возврата в UPDATE_RC, вывод в $ROOT/update.log
    local rc=0
    RUNS=$((RUNS + 1))
    (
        export PATH="$STUBS:$PATH"
        export STUB_DIR="$STUB_DIR" STUB_FIXTURE="$STUB_FIXTURE"
        export MYTPU_LIB_DIR="$TPU"
        export MYTPU_CONFIG_DIR="$ROOT/config"
        export MYTPU_DATA_DIR="$ROOT"
        export MYTPU_STATE_DIR="$ROOT/state"
        export MYTPU_RELEASES_DIR="$ROOT/releases"
        export MYTPU_CONSOLE_ROOT="$ROOT/console"
        export MYTPU_CACHE_DIR="$ROOT/cache"
        export MYTPU_LOCK_FILE="$ROOT/state/update.lock"
        export MYTPU_LOCK_CMD="$STUBS/flock"
        export MYTPU_CADDY_FILE="$ROOT/caddy/mytpu.caddy"
        export MYTPU_MAIN_CADDYFILE="$ROOT/caddy/Caddyfile"
        export MYTPU_CADDY_RELOAD="true"
        bash "$TPU/mytpu-update.sh" "$@"
    ) > "$ROOT/update.log" 2>&1 || rc=$?
    UPDATE_RC=$rc
    # Журнал каждого прогона сохраняется: по нему видно, где сценарий разошёлся с ожиданием
    mkdir -p "$ROOT/logs"
    cp "$ROOT/update.log" "$ROOT/logs/run-$RUNS.log"
}

# ---------- фикстура релиза ----------
make_release() { # make_release <тег> <образ-суффикс> [портить-манифест]
    local tag="$1" suffix="$2" broken="${3:-}" dir="$WORK/fixture" rel
    rm -rf "$dir"
    mkdir -p "$dir"
    rel="$dir/build"
    mkdir -p "$rel"
    cp "$TPU/compose.yml" "$TPU/Caddyfile" "$rel/"
    # Метка релиза в compose и init: по ней видно, какой комплект реально применён
    printf '\n# release: %s\n' "$tag" >> "$rel/compose.yml"
    printf '\n# release: %s\n' "$tag" >> "$rel/Caddyfile"
    cp "$TPU/../postgres/init/01-databases.sh" "$rel/"
    printf '\n# release: %s\n' "$tag" >> "$rel/01-databases.sh"
    mkdir -p "$rel/console"
    printf '<html data-build="%s"><base href="/console/"></html>\n' "$suffix" > "$rel/console/index.html"
    tar -czf "$rel/console.tar.gz" -C "$rel/console" .
    local gw="ghcr.io/mytpu/mytpu-backend/gateway@sha256:$(printf '%064d' 1)"
    local rag="ghcr.io/mytpu/mytpu-backend/rag@sha256:$(printf '%064d' 2)"
    local asst="ghcr.io/mytpu/mytpu-backend/assistant@sha256:$(printf '%064d' 3)"
    bash "$TPU/release-manifest.sh" --out "$rel" --version "$tag" --source-sha "$suffix" \
        --gateway "$gw" --rag "$rag" --assistant "$asst" >/dev/null
    if [ "$broken" = "no-digest" ]; then
        # Портим манифест после сборки: ссылка на образ без digest (как при ручной правке манифеста)
        python3 - "$rel/release.json" "$suffix" <<'PY'
import json, sys
path, suffix = sys.argv[1], sys.argv[2]
manifest = json.load(open(path, encoding="utf-8"))
manifest["images"]["gateway"] = "ghcr.io/mytpu/mytpu-backend/gateway:sha-" + suffix
json.dump(manifest, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=2, sort_keys=True)
PY
        ( cd "$rel" && sha256sum compose.yml Caddyfile console.tar.gz 01-databases.sh release.json > SHA256SUMS )
    fi
    if [ "$broken" = "checksum" ]; then
        printf 'подмена' >> "$rel/console.tar.gz"
    fi
    cp "$rel"/{release.json,SHA256SUMS,compose.yml,Caddyfile,console.tar.gz,01-databases.sh} "$dir/"
    python3 - "$tag" "$dir" <<'PY'
import json, sys
tag, out = sys.argv[1], sys.argv[2]
assets = ["release.json", "SHA256SUMS", "compose.yml", "Caddyfile", "console.tar.gz", "01-databases.sh"]
api = {"tag_name": tag, "assets": [
    {
        "name": name,
        # Обновлятор качает через API release assets (Accept: application/octet-stream),
        # поэтому в фикстуре есть и url, и browser_download_url
        "url": "https://api.github.com/repos/mytpu/mytpu-backend/releases/assets/%d" % index,
        "browser_download_url": "https://github.com/mytpu/mytpu-backend/releases/download/%s/%s" % (tag, name),
    }
    for index, name in enumerate(assets, start=1)]}
json.dump(api, open(out + "/api.json", "w", encoding="utf-8"))
with open(out + "/assets.map", "w", encoding="utf-8") as handle:
    for index, name in enumerate(assets, start=1):
        handle.write("https://api.github.com/repos/mytpu/mytpu-backend/releases/assets/%d %s\n" % (index, name))
PY
}

active() { # активная версия по состоянию обновлятора
    if [ -f "$ROOT/state/current-version" ]; then tr -d '\r\n' < "$ROOT/state/current-version"; fi
}
# Переключение комплекта видно по файлам: compose активного релиза и статика в каталоге раздачи
compose_image() { grep -o 'GATEWAY_IMAGE=[^ ]*' "$ROOT/state/compose.env" 2>/dev/null | head -1; }
static_build() { sed -n 's/.*data-build="\([^"]*\)".*/\1/p' "$ROOT/console/index.html" 2>/dev/null; }
compose_ups() { grep -c 'docker compose .* up ' "$ROOT/stub/commands.log" 2>/dev/null || true; }

echo "== сценарий 1: первый запуск"
new_scenario first
make_release v20261125-aaaaaaa aaaaaaa
run_update
expect_eq "первый запуск завершился успешно" 0 "$UPDATE_RC"
expect_eq "активна версия релиза" "v20261125-aaaaaaa" "$(active)"
expect_contains "журнал: активна версия" "активна версия v20261125-aaaaaaa" "$(cat "$ROOT/update.log")"
expect_contains "статика консоли переключена" "aaaaaaa" "$(static_build)"
[ -f "$ROOT/caddy/mytpu.caddy" ] && ok "конфиг маршрутов установлен" || bad "конфиг маршрутов не установлен"
expect_contains "конфиг маршрутов содержит /console" "handle /console/*" "$(cat "$ROOT/caddy/mytpu.caddy" 2>/dev/null || true)"
[ -f "$ROOT/state/current-version" ] && ok "версия записана в состояние" || bad "версия не записана"

echo "== сценарий 2: повтор активной версии"
before="$(compose_ups)"
run_update
expect_eq "повтор завершился успешно" 0 "$UPDATE_RC"
expect_contains "журнал: обновление пропущено" "уже активна" "$(cat "$ROOT/update.log")"
expect_eq "контейнеры повторно не поднимались" "$before" "$(compose_ups)"

echo "== сценарий 3: новая версия"
make_release v20261126-bbbbbbb bbbbbbb
run_update
expect_eq "обновление до новой версии" 0 "$UPDATE_RC"
expect_eq "активна новая версия" "v20261126-bbbbbbb" "$(active)"
expect_contains "статика консоли переключена" "bbbbbbb" "$(static_build)"
expect_eq "предыдущая версия запомнена" "v20261125-aaaaaaa" "$(cat "$ROOT/state/previous")"

echo "== сценарий 4: гонка двух запусков"
rm -f "$ROOT/stub/fail-up" "$ROOT/stub/health"
make_release v20261127-ccccccc cccccc
: > "$ROOT/stub/blocked"                       # блокировку держит другой запуск (таймер или ручной)
run_update
expect_eq "второй запуск не падает" 0 "$UPDATE_RC"
expect_contains "журнал: параллельный запуск пропущен" "обновление уже идёт" "$(cat "$ROOT/update.log")"
expect_eq "активная версия не изменилась" "v20261126-bbbbbbb" "$(active)"
rm -f "$ROOT/stub/blocked"

echo "== сценарий 5: обрыв загрузки (контрольные суммы не сходятся)"
make_release v20261128-ddddddd ddddddd checksum
run_update
[ "$UPDATE_RC" -ne 0 ] && ok "запуск завершился ошибкой" || bad "запуск не заметил подмену файла"
expect_contains "журнал: суммы не совпали" "контрольные суммы не совпали" "$(cat "$ROOT/update.log")"
expect_eq "активная версия прежняя" "v20261126-bbbbbbb" "$(active)"

echo "== сценарий 6: ошибка проверки здоровья — откат"
make_release v20261129-eeeeeee eeeeeee
echo fail > "$ROOT/stub/health"              # новый контейнер не отвечает UP
run_update
[ "$UPDATE_RC" -ne 0 ] && ok "запуск завершился ошибкой" || bad "запуск не заметил нездоровый контейнер"
expect_contains "журнал: откат выполнен" "ОТКАТ" "$(cat "$ROOT/update.log")"
expect_eq "после отката активна прежняя версия" "v20261126-bbbbbbb" "$(active)"
rm -f "$ROOT/stub/health"

echo "== сценарий 7: манифест без digest — переключение не начинается"
make_release v20261130-fffffff fffffff no-digest
before="$(compose_ups)"
run_update
[ "$UPDATE_RC" -ne 0 ] && ok "запуск завершился ошибкой" || bad "запуск принял манифест без digest"
expect_contains "журнал: образ без digest" "ссылка без digest" "$(cat "$ROOT/update.log")"
expect_eq "контейнеры не поднимались" "$before" "$(compose_ups)"
expect_eq "активная версия прежняя" "v20261126-bbbbbbb" "$(active)"

echo "== сценарий 8: ручной откат"
make_release v20261201-d1d1d1d d1d1d1d
run_update
expect_eq "обновление до версии с откатом" 0 "$UPDATE_RC"
run_update --rollback
expect_eq "ручной откат завершился успешно" 0 "$UPDATE_RC"
expect_eq "активна предыдущая версия" "v20261126-bbbbbbb" "$(active)"

echo "== сценарий 9: режим --check ничего не меняет"
before="$(compose_ups)"
run_update --check
expect_eq "--check успешен" 0 "$UPDATE_RC"
expect_eq "контейнеры не тронуты" "$before" "$(compose_ups)"
expect_eq "активная версия не изменилась" "v20261126-bbbbbbb" "$(active)"

echo
echo "== сценарий 10: сбой после изменения Caddy — откат возвращает прежние файлы"
make_release v20261202-a1b2c3d a1b2c3d
caddy_before="$(cat "$ROOT/caddy/mytpu.caddy" 2>/dev/null || true)"
echo fail > "$ROOT/stub/routes"            # маршруты после переключения не проходят
run_update
[ "$UPDATE_RC" -ne 0 ] && ok "запуск завершился ошибкой" || bad "запуск не заметил плохие маршруты"
expect_contains "журнал: откат выполнен" "ОТКАТ" "$(cat "$ROOT/update.log")"
expect_eq "активная версия прежняя" "v20261126-bbbbbbb" "$(active)"
expect_contains "прежний конфиг маршрутов возвращён" "# release: v20261126-bbbbbbb" "$(cat "$ROOT/caddy/mytpu.caddy")"
if [ "$caddy_before" = "$(cat "$ROOT/caddy/mytpu.caddy")" ]; then ok "файл маршрутов совпадает с прежним"; else bad "файл маршрутов изменён после отката"; fi
rm -f "$ROOT/stub/routes"

echo "== сценарий 11: ручной откат пишет версию только после здоровья и маршрутов"
make_release v20261203-b2c3d4e b2c3d4e
run_update
expect_eq "обновление до версии перед ручным откатом" 0 "$UPDATE_RC"
echo fail > "$ROOT/stub/routes"
run_update --rollback
[ "$UPDATE_RC" -ne 0 ] && ok "откат с плохими маршрутами завершился ошибкой" || bad "откат принял плохие маршруты"
expect_eq "активная версия не переписана" "v20261203-b2c3d4e" "$(active)"
rm -f "$ROOT/stub/routes"
run_update --rollback
expect_eq "откат при здоровых маршрутах успешен" 0 "$UPDATE_RC"
expect_eq "активна предыдущая версия" "v20261126-bbbbbbb" "$(active)"

echo "== сценарий 12: провал первого запуска оставляет чистое состояние"
new_scenario fresh
make_release v20261204-c3d4e5f c3d4e5f
: > "$ROOT/stub/fail-up"
run_update
[ "$UPDATE_RC" -ne 0 ] && ok "первый запуск завершился ошибкой" || bad "первый запуск не заметил отказ контейнеров"
expect_eq "активная версия отсутствует" "" "$(active)"
[ ! -f "$ROOT/state/compose.yml" ] && ok "активный compose не установлен" || bad "после сбоя остался активный compose"
expect_eq "версия предыдущего релиза не записана" "" "$(cat "$ROOT/state/previous" 2>/dev/null || true)"
rm -f "$ROOT/stub/fail-up"
run_update
expect_eq "повтор установки проходит" 0 "$UPDATE_RC"
expect_eq "активна версия повторной установки" "v20261204-c3d4e5f" "$(active)"

echo "== сценарий 13: токен вне контейнеров и порядок инициализации БД"
if grep -q 'github_token' "$TPU/compose.yml"; then bad "compose монтирует github_token в контейнер"; else ok "github_token не монтируется в контейнеры"; fi
expect_contains "инициализация БД установлена до первого старта" "compose up file=$ROOT/releases/v20261204-c3d4e5f/compose.yml init=yes" "$(cat "$ROOT/stub/commands.log")"

echo
if [ "$FAIL" -ne 0 ]; then
    echo "update-scenarios: провалено $FAIL, пройдено $PASS" >&2
    exit 1
fi
echo "update-scenarios: все $PASS проверок пройдены"
