#!/usr/bin/env bash
# Установщик на фикстурах: права файлов, подключение к действующему Caddy без потери чужих сайтов,
# повторная установка, отказ caddy validate и отказ reload. Docker не нужен.
#   bash deploy/tpu-server/test/install-scenarios.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TPU="$(cd "$HERE/.." && pwd)"
WORK="$(mktemp -d)"
PASS=0
FAIL=0
cleanup() {
    if [ "${INSTALL_SCENARIOS_KEEP:-0}" = 1 ]; then echo "install-scenarios: каталог сохранён: $WORK" >&2; return; fi
    rm -rf "$WORK"
}
trap cleanup EXIT
ok() { echo "ok: $*"; PASS=$((PASS + 1)); }
bad() { echo "FAIL: $*" >&2; FAIL=$((FAIL + 1)); }
expect_eq() { [ "$2" = "$3" ] && ok "$1" || bad "$1 — ожидалось «$2», получено «$3»"; }
expect_contains() { case "$3" in *"$2"*) ok "$1" ;; *) bad "$1 — нет «$2» в «$3»" ;; esac; }

# ---------- заглушки ----------
STUBS="$WORK/bin"
mkdir -p "$STUBS"
printf '#!/usr/bin/env bash\necho 0\n' > "$STUBS/id"
cat > "$STUBS/docker" <<'SH'
#!/usr/bin/env bash
case "$*" in
    "compose version") echo "Docker Compose version v2.30.0" ;;
    "version --format"*) echo "27.3.1" ;;
    "volume ls"*) : ;;
    *) exit 0 ;;
esac
SH
printf '#!/usr/bin/env bash\nexit 0\n' > "$STUBS/flock"
printf '#!/usr/bin/env bash\necho "caddy $*" >> "${STUB_DIR:?}/log"; exit 0\n' > "$STUBS/caddy"
# caddy validate учитывает режим: файл fail-validate в каталоге заглушек
cat > "$STUBS/caddy" <<'SH'
#!/usr/bin/env bash
echo "caddy $*" >> "${STUB_DIR:?}/log"
if [ "${1:-}" = "validate" ] && [ -f "$STUB_DIR/fail-validate" ]; then
    echo "Error: adapting config using caddyfile: unrecognized directive" >&2
    exit 1
fi
[ "${1:-}" = "validate" ] && echo "Valid configuration"
exit 0
SH
cat > "$STUBS/systemctl" <<'SH'
#!/usr/bin/env bash
echo "systemctl $*" >> "${STUB_DIR:?}/log"
case "$*" in
    *reload*)
        [ -f "$STUB_DIR/fail-reload" ] && exit 1
        exit 0 ;;
    *) exit 0 ;;
esac
SH
chmod +x "$STUBS"/*

ROOT=""
new_root() { # new_root <имя> → чистые каталоги установки и действующий конфиг Caddy с чужим сайтом
    ROOT="$WORK/$1"
    rm -rf "$ROOT"
    mkdir -p "$ROOT/config" "$ROOT/caddy" "$ROOT/stub" "$ROOT/lib" "$ROOT/bin" "$ROOT/systemd" "$ROOT/data" "$ROOT/cache"
    cat > "$ROOT/caddy/Caddyfile" <<'EOF'
{
	email admin@example.ru
}

ml.example.ru {
	reverse_proxy 127.0.0.1:8080
}

import other-sites.caddy
EOF
    printf 'other.example.ru {\n\trespond "ok"\n}\n' > "$ROOT/caddy/other-sites.caddy"
    # Параметры площадки: установщик рендерит из них файл маршрутов
    cat > "$ROOT/config/server.env" <<EOF
MYTPU_HOST=mytpu.test
MYTPU_TLS=upstream
MYTPU_LISTEN=127.0.0.1:8443
CONSOLE_ALLOWED_NETWORKS="10.1.0.0/16"
MYTPU_CONFIG_DIR=$ROOT/config
EOF
    STUB_DIR="$ROOT/stub"
}

run_install() { # run_install → код в INSTALL_RC, вывод в $ROOT/install.log
    local rc=0
    (
        export PATH="$STUBS:$PATH"
        export STUB_DIR="$STUB_DIR"
        export MYTPU_CONFIG_DIR="$ROOT/config"
        export MYTPU_LIB_DIR="$ROOT/lib"
        export MYTPU_BIN_DIR="$ROOT/bin"
        export MYTPU_SYSTEMD_DIR="$ROOT/systemd"
        export MYTPU_DATA_DIR="$ROOT/data"
        export MYTPU_CACHE_DIR="$ROOT/cache"
        export MYTPU_CADDY_FILE="$ROOT/caddy/mytpu.caddy"
        export MYTPU_MAIN_CADDYFILE="$ROOT/caddy/Caddyfile"
        bash "$TPU/install.sh"
    ) > "$ROOT/install.log" 2>&1 || rc=$?
    INSTALL_RC=$rc
}

echo "== сценарий 1: установка рядом с работающим Caddy"
new_root first
run_install
expect_eq "установка завершилась успешно" 0 "$INSTALL_RC"
expect_contains "чужой сайт сохранён" "ml.example.ru" "$(cat "$ROOT/caddy/Caddyfile")"
expect_contains "глобальные настройки сохранены" "email admin@example.ru" "$(cat "$ROOT/caddy/Caddyfile")"
expect_contains "прежний импорт сохранён" "import other-sites.caddy" "$(cat "$ROOT/caddy/Caddyfile")"
expect_eq "импорт стенда добавлен один раз" 1 "$(grep -c 'import mytpu.caddy' "$ROOT/caddy/Caddyfile")"
[ -f "$ROOT/caddy/mytpu.caddy" ] && ok "файл маршрутов установлен" || bad "файл маршрутов не установлен"
expect_contains "итоговый конфиг проверен caddy validate" "caddy validate --config $ROOT/caddy/Caddyfile" "$(cat "$ROOT/stub/log")"
expect_contains "Caddy перечитан" "systemctl reload caddy" "$(cat "$ROOT/stub/log")"
# Режимы POSIX: в Git Bash на Windows chmod не действует, поэтому права проверяются в CI (Linux)
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) ok "права файлов проверяются в CI (Windows не хранит режимы POSIX)" ;;
    *)
        expect_eq "каталог параметров закрыт (700)" "700" "$(stat -c '%a' "$ROOT/config")"
        expect_eq "каталог секретов закрыт (700)" "700" "$(stat -c '%a' "$ROOT/config/secrets")"
        expect_eq "секрет только для владельца (600)" "600" "$(stat -c '%a' "$ROOT/config/secrets/gateway_db_password")"
        ;;
esac
[ -d "$ROOT/config/postgres-init" ] && ok "каталог инициализации БД создан" || bad "нет каталога postgres-init"
[ -f "$ROOT/bin/mytpu-update.sh" ] && ok "обновлятор установлен" || bad "обновлятор не установлен"
[ -f "$ROOT/systemd/mytpu-update.timer" ] && ok "таймер установлен" || bad "таймер не установлен"

echo "== сценарий 2: повторная установка не дублирует импорт"
before="$(cat "$ROOT/caddy/Caddyfile")"
run_install
expect_eq "повторная установка успешна" 0 "$INSTALL_RC"
expect_eq "импорт по-прежнему один" 1 "$(grep -c 'import mytpu.caddy' "$ROOT/caddy/Caddyfile")"
expect_eq "основной конфиг не переписан" "$before" "$(cat "$ROOT/caddy/Caddyfile")"

echo "== сценарий 3: caddy validate не принимает итоговый конфиг — файлы возвращаются"
new_root invalid
: > "$ROOT/stub/fail-validate"
main_before="$(cat "$ROOT/caddy/Caddyfile")"
run_install
[ "$INSTALL_RC" -ne 0 ] && ok "установка остановлена" || bad "установка прошла при невалидном конфиге"
expect_eq "основной конфиг восстановлен" "$main_before" "$(cat "$ROOT/caddy/Caddyfile")"
[ ! -f "$ROOT/caddy/mytpu.caddy" ] && ok "новый файл маршрутов убран" || bad "остался файл маршрутов при отказе"
expect_contains "журнал: итоговый конфиг не принят" "не принял итоговый конфиг" "$(cat "$ROOT/install.log")"

echo "== сценарий 4: Caddy не перечитал конфиг — файлы возвращаются"
new_root reload
: > "$ROOT/stub/fail-reload"
main_before="$(cat "$ROOT/caddy/Caddyfile")"
run_install
[ "$INSTALL_RC" -ne 0 ] && ok "установка остановлена" || bad "установка прошла без перечитывания Caddy"
expect_eq "основной конфиг восстановлен" "$main_before" "$(cat "$ROOT/caddy/Caddyfile")"
expect_contains "журнал: Caddy не перечитал" "не перечитал конфиг" "$(cat "$ROOT/install.log")"

echo "== сценарий 5: серверные параметры не заполнены — Caddy не трогаем"
new_root unfilled
sed -i 's/^MYTPU_HOST=.*/MYTPU_HOST=/' "$ROOT/config/server.env" 2>/dev/null || true
printf 'MYTPU_TLS=upstream\n' > "$ROOT/config/server.env"
main_before="$(cat "$ROOT/caddy/Caddyfile")"
run_install
expect_eq "установка не падает" 0 "$INSTALL_RC"
expect_eq "основной конфиг не изменён" "$main_before" "$(cat "$ROOT/caddy/Caddyfile")"
[ ! -f "$ROOT/caddy/mytpu.caddy" ] && ok "файл маршрутов не создан" || bad "создан файл маршрутов без параметров"

echo
if [ "$FAIL" -ne 0 ]; then
    echo "install-scenarios: провалено $FAIL, пройдено $PASS" >&2
    exit 1
fi
echo "install-scenarios: все $PASS проверок пройдены"
