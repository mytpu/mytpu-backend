#!/usr/bin/env bash
# Первичная установка обновлятора стенда «Мой ТПУ» на сервере ТПУ. Один раз, от root, из каталога репозитория
# (или его копии — нужны deploy/tpu-server/ и deploy/dev-secrets.sh):
#   sudo bash deploy/tpu-server/install.sh            # установить
#   sudo bash deploy/tpu-server/install.sh --check    # только проверить зависимости и показать план
# Что делает: проверяет зависимости, раскладывает скрипты и unit-файлы, создаёт каталоги, секреты и server.env
# с правами root 0600, подключает конфиг маршрутов к системному Caddy. Образы и контейнеры НЕ трогает:
# первый запуск обновления — отдельная команда (README.md, раздел «Первичная установка»).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CONFIG_DIR="${MYTPU_CONFIG_DIR:-/etc/mytpu}"
SECRETS_DIR="$CONFIG_DIR/secrets"
LIB_DIR="${MYTPU_LIB_DIR:-/usr/local/lib/mytpu}"
BIN_DIR="${MYTPU_BIN_DIR:-/usr/local/bin}"
SYSTEMD_DIR="${MYTPU_SYSTEMD_DIR:-/etc/systemd/system}"
DATA_DIR="${MYTPU_DATA_DIR:-/var/lib/mytpu}"
CACHE_DIR="${MYTPU_CACHE_DIR:-/var/cache/mytpu}"
CADDY_FILE="${MYTPU_CADDY_FILE:-/etc/caddy/mytpu.caddy}"
MAIN_CADDY="${MYTPU_MAIN_CADDYFILE:-/etc/caddy/Caddyfile}"

CHECK_ONLY=0
if [ "${1:-}" = "--check" ]; then CHECK_ONLY=1; fi

die() { echo "install: ОШИБКА: $*" >&2; exit 1; }
step() { echo "==> $*"; }
need() { command -v "$1" >/dev/null 2>&1 || die "не найдено: $1 — $2"; }

[ "$(id -u)" -eq 0 ] || die "запускать от root"

step "зависимости"
need docker "Docker Engine с плагином compose v2"
docker compose version >/dev/null 2>&1 || die "нужен плагин 'docker compose' версии 2.24 или новее"
need curl "исходящий HTTPS к api.github.com и ghcr.io"
need python3 "разбор манифеста релиза"
need tar "распаковка статики консоли"
need flock "блокировка одновременных обновлений (util-linux)"
need sha256sum "проверка контрольных сумм релиза"
need caddy "системный Caddy (проверка и перечитывание конфига маршрутов)"
need systemctl "systemd (таймер обновления)"
echo "    docker $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo '?'), compose $(docker compose version --short 2>/dev/null || echo '?'), caddy $(caddy version | awk '{print $1}')"

step "план"
cat <<EOF
    скрипты:    $BIN_DIR/mytpu-update.sh, $LIB_DIR/{checks,release,render-caddyfile}.sh
    параметры:  $CONFIG_DIR/server.env (0600 root), секреты — $SECRETS_DIR/ (0600 root)
    данные:     $DATA_DIR/{releases,state,console,models,geo,dataset}, загрузки — $CACHE_DIR
    маршруты:   $CADDY_FILE, подключается из $MAIN_CADDY строкой 'import mytpu.caddy'
    таймер:     $SYSTEMD_DIR/mytpu-update.{service,timer}, проверка раз в 15 минут
EOF
if [ "$CHECK_ONLY" = 1 ]; then echo "install: проверка пройдена, изменений нет"; exit 0; fi

step "каталоги"
mkdir -p "$CONFIG_DIR" "$CONFIG_DIR/postgres-init" "$SECRETS_DIR" "$LIB_DIR" "$DATA_DIR/releases" \
    "$DATA_DIR/state" "$DATA_DIR/console" "$DATA_DIR/models" "$DATA_DIR/geo" "$DATA_DIR/dataset" \
    "$CACHE_DIR" "$SYSTEMD_DIR"
# Секреты читает только root: демон Docker копирует их в контейнер, файлы на хосте другим не нужны
chmod 700 "$CONFIG_DIR" "$SECRETS_DIR"
# Статику консоли читает пользователь caddy, каталоги релизов должны быть проходимы
chmod 755 "$DATA_DIR"

step "скрипты"
install -m 0755 "$HERE/mytpu-update.sh" "$BIN_DIR/mytpu-update.sh"
install -m 0644 "$HERE/checks.sh" "$HERE/release.sh" "$HERE/render-caddyfile.sh" "$LIB_DIR/"
install -m 0644 "$HERE/Caddyfile" "$LIB_DIR/Caddyfile.example"
install -m 0644 "$HERE/README.md" "$CONFIG_DIR/README.md" 2>/dev/null || true

step "параметры сервера"
if [ -f "$CONFIG_DIR/server.env" ]; then
    echo "    $CONFIG_DIR/server.env уже есть — не меняю"
else
    install -m 0600 "$HERE/server.env.example" "$CONFIG_DIR/server.env"
    echo "    создан $CONFIG_DIR/server.env — заполните MYTPU_TLS, CONSOLE_ALLOWED_NETWORKS, ML_BASE_URL и пути"
fi

step "секреты"
SECRETS_DIR="$SECRETS_DIR" SECRETS_MODE=600 bash "$HERE/../dev-secrets.sh" >/dev/null || die "dev-secrets.sh не выполнился"
if [ ! -f "$SECRETS_DIR/assistant_client_key" ]; then
    # Ключ клиента чата: /api/ai/* открыт наружу, без ключа любой тратил бы квоту модели
    head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n' > "$SECRETS_DIR/assistant_client_key"
fi
chmod 600 "$SECRETS_DIR"/*
echo "    ВНИМАНИЕ: в $SECRETS_DIR/ml_api_key должен лежать ключ ml-server (одна строка, без перевода строки в конце)"
if [ ! -f "$SECRETS_DIR/github_token" ]; then
    echo "    если репозиторий и пакеты закрыты: $SECRETS_DIR/github_token — PAT только на чтение (contents, packages)"
fi

step "маршруты"
# Чужие сайты, импорты и глобальные настройки Caddy сохраняются: add-site проверяет итоговый конфиг целиком
# и при ошибке возвращает прежние файлы (проверки — deploy/tpu-server/test/install-caddy.sh)
bash "$HERE/caddy-site.sh" --main "$MAIN_CADDY" --site "$CADDY_FILE" \
    --env "$CONFIG_DIR/server.env" --template "$HERE/Caddyfile" \
    --render "$HERE/render-caddyfile.sh" --reload "systemctl reload caddy" \
    || die "Caddy не подключён — прежний конфиг сохранён, проверьте 'systemctl status caddy'"

step "systemd"
install -m 0644 "$HERE/systemd/mytpu-update.service" "$HERE/systemd/mytpu-update.timer" "$SYSTEMD_DIR/"
systemctl daemon-reload
systemctl enable --now mytpu-update.timer >/dev/null
echo "    таймер включён: systemctl list-timers mytpu-update.timer"

step "тома с данными"
docker volume ls --format '{{.Name}}' | grep -Ei 'pgdata|rag-files' | sed 's/^/    найден том: /' || true
echo "    ожидаются: ${MYTPU_PGDATA_VOLUME:-mytpu-pgdata} и ${MYTPU_RAG_FILES_VOLUME:-mytpu-rag-files} (имена — в server.env)"

cat <<EOF

Дальше (README.md, раздел «Первичная установка»):
  1. заполните $CONFIG_DIR/server.env (HTTPS-режим, сети консоли, ML_BASE_URL, пути к модели и базе стран)
  2. положите модель e5 в каталог RAG_MODEL_HOST_DIR и базу стран DB-IP в GEO_DB_HOST_DIR
  3. sudo systemctl start mytpu-update.service   # первый запуск: скачать релиз и поднять стенд
  4. journalctl -u mytpu-update.service -f       # ход обновления; systemctl stop mytpu-update.timer — пауза
EOF
