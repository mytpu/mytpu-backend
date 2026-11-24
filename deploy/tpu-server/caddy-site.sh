#!/usr/bin/env bash
# Подключение сайта «Мой ТПУ» к действующему Caddy: чужие сайты, импорты и глобальные настройки не теряются.
# Повторный запуск не дублирует импорт, а невалидный итоговый конфиг или отказ reload возвращают прежние файлы.
#   bash caddy-site.sh --main /etc/caddy/Caddyfile --site /etc/caddy/mytpu.caddy \
#        --env /etc/mytpu/server.env --template /usr/local/lib/mytpu/Caddyfile.example \
#        --render /usr/local/lib/mytpu/render-caddyfile.sh --reload 'systemctl reload caddy'
set -euo pipefail

MAIN="${MYTPU_MAIN_CADDYFILE:-/etc/caddy/Caddyfile}"
SITE="${MYTPU_CADDY_FILE:-/etc/caddy/mytpu.caddy}"
ENV_FILE="${MYTPU_CONFIG_DIR:-/etc/mytpu}/server.env"
TEMPLATE=""
RENDER=""
RELOAD="systemctl reload caddy"

while [ $# -gt 0 ]; do
    case "$1" in
        --main) MAIN="$2"; shift 2 ;;
        --site) SITE="$2"; shift 2 ;;
        --env) ENV_FILE="$2"; shift 2 ;;
        --template) TEMPLATE="$2"; shift 2 ;;
        --render) RENDER="$2"; shift 2 ;;
        --reload) RELOAD="$2"; shift 2 ;;
        *) echo "caddy-site: неизвестный аргумент $1" >&2; exit 2 ;;
    esac
done

[ -n "$TEMPLATE" ] && [ -n "$RENDER" ] || { echo "caddy-site: нужны --template и --render" >&2; exit 2; }

say() { echo "    $*"; }
IMPORT_LINE='import mytpu.caddy'
STAMP="$(date -u +%Y%m%d%H%M%S)"
MAIN_BAK="$MAIN.bak.$STAMP"
SITE_BAK="$SITE.bak.$STAMP"
MAIN_EXISTED=0
SITE_EXISTED=0
CHANGED=0

# Резервные копии до любых изменений: по ним restore() возвращает ровно прежнее состояние
if [ -f "$MAIN" ]; then MAIN_EXISTED=1; cp -p "$MAIN" "$MAIN_BAK"; fi
if [ -f "$SITE" ]; then SITE_EXISTED=1; cp -p "$SITE" "$SITE_BAK"; fi

restore() {
    [ "$CHANGED" = 1 ] || return 0
    if [ "$MAIN_EXISTED" = 1 ]; then
        cp -p "$MAIN_BAK" "$MAIN"
    else
        rm -f "$MAIN"
    fi
    if [ "$SITE_EXISTED" = 1 ]; then
        cp -p "$SITE_BAK" "$SITE"
    else
        rm -f "$SITE"
    fi
    # Прежний конфиг возвращён: Caddy перечитывает именно его
    $RELOAD >/dev/null 2>&1 || true
    say "прежние файлы Caddy возвращены"
}

fail() { say "ОШИБКА: $*"; restore; exit 1; }

# 1. Файл маршрутов стенда. Если server.env ещё не заполнен, рендер не удаётся — тогда Caddy не трогаем вовсе
if [ "$SITE_EXISTED" = 0 ]; then
    tmp="$(mktemp)"
    if ! bash "$RENDER" "$ENV_FILE" "$TEMPLATE" > "$tmp" 2>/dev/null; then
        rm -f "$tmp"
        say "конфиг маршрутов пока не создан: заполните $ENV_FILE и запустите обновление"
        exit 0
    fi
    install -m 0644 "$tmp" "$SITE"
    rm -f "$tmp"
    CHANGED=1
fi

# 2. Основной конфиг: одна строка import (повторно не добавляется)
if [ "$MAIN_EXISTED" = 0 ]; then
    mkdir -p "$(dirname "$MAIN")"
    : > "$MAIN"
fi
if grep -Eq "^[[:space:]]*import[[:space:]]+mytpu\.caddy[[:space:]]*$" "$MAIN"; then
    say "$MAIN уже подключает $SITE"
else
    # Перевод строки в конце обязателен: файл мог не заканчиваться им
    printf '\n# Стенд «Мой ТПУ» (deploy/tpu-server/README.md)\n%s\n' "$IMPORT_LINE" >> "$MAIN"
    CHANGED=1
    say "в $MAIN добавлена строка: $IMPORT_LINE"
fi

# 3. Проверка итогового конфига целиком: чужой сайт и глобальные настройки тоже должны остаться валидными
if ! caddy validate --config "$MAIN" >/tmp/caddy-validate.log 2>&1; then
    fail "Caddy не принял итоговый конфиг $MAIN: $(tail -2 /tmp/caddy-validate.log | tr '\n' ' ')"
fi
say "итоговый конфиг $MAIN прошёл caddy validate"

# 4. Перечитывание: отказ reload — тоже повод вернуть прежние файлы
if ! $RELOAD >/dev/null 2>&1; then
    fail "Caddy не перечитал конфиг ($RELOAD)"
fi
say "Caddy перечитан, сайт «Мой ТПУ» подключён"
# Резервные копии основного конфига остаются рядом: их видно в /etc/caddy и легко вернуть вручную
if [ "$MAIN_EXISTED" = 1 ]; then say "копия прежнего конфига: $MAIN_BAK"; fi
