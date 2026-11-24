#!/usr/bin/env bash
# Обновление стенда «Мой ТПУ» на сервере ТПУ. Сервер только скачивает готовое (исходящий HTTPS):
# релиз GitHub (манифест, compose, конфиг маршрутов, статика консоли) и образы из GHCR по digest.
# Ставится install.sh в /usr/local/bin; запускается таймером systemd и вручную:
#   systemctl start mytpu-update.service      # обновить до выбранного канала
#   mytpu-update.sh --check                   # что в канале и что активно, без изменений
#   mytpu-update.sh --tag v20261125-742d583 # конкретная версия
#   mytpu-update.sh --rollback                # вернуть предыдущий активный комплект
#   mytpu-update.sh --config                  # пересобрать конфиг маршрутов из активного релиза (сети, TLS)
# Один запуск = один комплект версий: загрузка → проверки → переключение → проверка маршрутов.
# Ошибка на любом шаге возвращает предыдущий комплект: образы, статику и конфигурацию маршрутов.
set -euo pipefail

CONFIG_DIR="${MYTPU_CONFIG_DIR:-/etc/mytpu}"
SERVER_ENV="$CONFIG_DIR/server.env"
SECRETS_DIR="$CONFIG_DIR/secrets"
LIB_DIR="${MYTPU_LIB_DIR:-/usr/local/lib/mytpu}"
DATA_DIR="${MYTPU_DATA_DIR:-/var/lib/mytpu}"
STATE_DIR="$DATA_DIR/state"
RELEASES_DIR="$DATA_DIR/releases"
CONSOLE_DIR="${MYTPU_CONSOLE_ROOT:-$DATA_DIR/console}"
CACHE_DIR="${MYTPU_CACHE_DIR:-/var/cache/mytpu}"
CADDY_FILE="${MYTPU_CADDY_FILE:-/etc/caddy/mytpu.caddy}"
MAIN_CADDY="${MYTPU_MAIN_CADDYFILE:-/etc/caddy/Caddyfile}"
LOCK_FILE="${MYTPU_LOCK_FILE:-/run/lock/mytpu-update.lock}"
CADDY_RELOAD="${MYTPU_CADDY_RELOAD:-systemctl reload caddy}"
KEEP_RELEASES=3

# Активный compose и его параметры — по постоянным путям: команды администратора не зависят от версии
COMPOSE_FILE="$STATE_DIR/compose.yml"
ACTIVE_ENV="$STATE_DIR/compose.env"
COMPOSE_ENV="$ACTIVE_ENV"
RENDERED=""
CHECK_MAIN=""
CADDY_CHANGED=0
CADDY_EXISTED=0
ATTEMPT=""

MODE=update
ARG_TAG=""
SWITCHED=0
PREV_TAG=""
PREV_DIR=""
LOGGED_IN=0

# shellcheck source=/dev/null
. "$LIB_DIR/checks.sh"
# shellcheck source=/dev/null
. "$LIB_DIR/release.sh"

die() { log "ОШИБКА: $*"; exit 1; }

usage() {
    sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'
}

# Состояние: активная версия, прошлая версия и compose активного релиза — обычными файлами, без символических ссылок
active_tag() {
    if [ -f "$STATE_DIR/current-version" ]; then
        tr -d '\r\n' < "$STATE_DIR/current-version"
    fi
    return 0
}

compose_up() {
    compose up -d --remove-orphans postgres rag gateway assistant
}

# Ссылки на образы релиза кладутся в один файл вместе с параметрами сервера: одна --env-file на все команды
write_compose_env() { # write_compose_env <каталог релиза>
    local rel="$1" tmp="$COMPOSE_ENV.tmp"
    {
        echo "# Сгенерировано mytpu-update.sh $(date -u +%FT%TZ) для релиза $(basename "$rel"). Правки перезаписываются."
        cat "$SERVER_ENV"
        printf 'GATEWAY_IMAGE=%s\nRAG_IMAGE=%s\nASSISTANT_IMAGE=%s\n' "$IMG_GATEWAY" "$IMG_RAG" "$IMG_ASSISTANT"
    } > "$tmp"
    chmod 600 "$tmp"
    mv -f "$tmp" "$COMPOSE_ENV"
}

# Активный compose — по постоянному пути: администратору не нужен номер версии в команде
install_compose() { install -m 0644 "$1/compose.yml" "$STATE_DIR/compose.yml"; }

# Инициализация БД нужна только на пустом томе; обновляется вместе с релизом
install_postgres_init() { install -m 0644 "$1/postgres/init/01-databases.sh" "$CONFIG_DIR/postgres-init/01-databases.sh"; }

# Статика консоли переключается вместе с образами: каталог заменяется целиком, старый остаётся для отката
switch_static() { # switch_static <каталог релиза>
    local rel="$1" new="$DATA_DIR/.console.new" old="$DATA_DIR/.console.old"
    rm -rf "$new" "$old"
    cp -a "$rel/console" "$new"
    if [ -d "$CONSOLE_DIR" ]; then mv "$CONSOLE_DIR" "$old"; fi
    mv "$new" "$CONSOLE_DIR"
    rm -rf "$old"
    # Статику читает пользователь caddy, а не root
    chmod -R a+rX "$CONSOLE_DIR"
}

pull_images() {
    local token_file="$SECRETS_DIR/github_token" ref
    if [ -s "$token_file" ] && [ -n "${GHCR_USER:-}" ]; then
        # Пароль читает docker из stdin: в аргументах и истории процесса его нет
        tr -d '\r\n' < "$token_file" | docker login "${GHCR_REGISTRY:-ghcr.io}" -u "$GHCR_USER" --password-stdin >/dev/null || return 1
        LOGGED_IN=1
    fi
    for ref in "$IMG_GATEWAY" "$IMG_RAG" "$IMG_ASSISTANT"; do
        log "загрузка образа $ref"
        docker pull "$ref" >/dev/null || return 1
    done
}

prepare_caddyfile() {
    local template="$1" validation
    local import_pattern='^[[:space:]]*import[[:space:]]+mytpu\.caddy([[:space:]]+#.*|[[:space:]]*)$'
    grep -Eq "$import_pattern" "$MAIN_CADDY" || die "в $MAIN_CADDY нет импорта mytpu.caddy — запустите install.sh"
    # Соседний файл сохраняет базовый путь остальных относительных импортов.
    RENDERED="$(mktemp "$(dirname "$MAIN_CADDY")/.mytpu-site.XXXXXX")"
    CHECK_MAIN="$(mktemp "$(dirname "$MAIN_CADDY")/.mytpu-main.XXXXXX")"
    bash "$LIB_DIR/render-caddyfile.sh" "$SERVER_ENV" "$template" > "$RENDERED" \
        || die "не удалось собрать конфиг маршрутов из шаблона"
    sed -E "s/$import_pattern/import $(basename "$RENDERED")/" "$MAIN_CADDY" > "$CHECK_MAIN"
    if ! validation="$(caddy validate --config "$CHECK_MAIN" --adapter caddyfile 2>&1)"; then
        die "Caddy не принял итоговый конфиг: $validation"
    fi
}

install_caddyfile() {
    if [ -f "$CADDY_FILE" ] && cmp -s "$RENDERED" "$CADDY_FILE"; then
        log "конфиг маршрутов не изменился"
        return 0
    fi
    if [ -f "$CADDY_FILE" ]; then
        cp -p "$CADDY_FILE" "$ATTEMPT/caddyfile.previous"
        CADDY_EXISTED=1
    fi
    CADDY_CHANGED=1
    install -m 0644 "$RENDERED" "$CADDY_FILE"
    $CADDY_RELOAD || die "Caddy не перечитал конфиг"
    log "конфиг маршрутов обновлён, Caddy перечитан"
}

restore_caddyfile() {
    [ "$CADDY_CHANGED" = 1 ] || return 0
    if [ "$CADDY_EXISTED" = 1 ]; then
        install -m 0644 "$ATTEMPT/caddyfile.previous" "$CADDY_FILE"
    else
        rm -f "$CADDY_FILE"
    fi
    $CADDY_RELOAD || log "ОТКАТ: Caddy не перечитал прежний конфиг — нужен администратор"
}

wait_healthy() { # wait_healthy <сервис> <функция проверки> <попыток>
    local name="$1" check="$2" tries="$3"
    log "ожидание готовности $name (до $((tries * 2)) с)"
    wait_until "$tries" 2 "$check" || die "$name не стал healthy за $((tries * 2)) с"
    log "$name готов"
}

ROLLBACK_DONE=0
save_active_state() {
    local file
    mkdir "$ATTEMPT/active"
    for file in compose.yml compose.env current-version previous; do
        if [ -f "$STATE_DIR/$file" ]; then cp -p "$STATE_DIR/$file" "$ATTEMPT/active/$file"; fi
    done
}

restore_active_state() {
    local file
    for file in compose.yml compose.env current-version previous; do
        if [ -f "$ATTEMPT/active/$file" ]; then
            cp -p "$ATTEMPT/active/$file" "$STATE_DIR/$file"
        else
            rm -f "$STATE_DIR/$file"
        fi
    done
}

rollback() {
    [ "$SWITCHED" = 1 ] || return 0
    if [ "$ROLLBACK_DONE" = 1 ]; then return 0; fi
    ROLLBACK_DONE=1
    log "ОТКАТ: возвращаю ${PREV_TAG:-предыдущий комплект}"
    if [ -n "$PREV_DIR" ] && [ -d "$PREV_DIR" ] && [ -f "$PREV_DIR/compose.yml" ]; then
        restore_active_state
        COMPOSE_FILE="$STATE_DIR/compose.yml"
        install_postgres_init "$PREV_DIR"
        switch_static "$PREV_DIR"
        COMPOSE_ENV="$ACTIVE_ENV"
        if compose_up; then
            wait_until 30 2 postgres_health && wait_until 90 2 rag_health \
                && wait_until 45 2 gateway_health && wait_until 45 2 assistant_health && check_routes \
                && log "ОТКАТ: ${PREV_TAG} поднят" \
                || log "ОТКАТ: ${PREV_TAG} запущен, но здоровье не подтверждено — нужен администратор"
        else
            log "ОТКАТ: не удалось поднять ${PREV_TAG} — нужен администратор"
        fi
    else
        compose stop postgres rag gateway assistant || log "ОТКАТ: не удалось остановить первый релиз"
        restore_active_state
    fi
}

on_exit() {
    local rc=$?
    if [ "$rc" -ne 0 ]; then
        restore_caddyfile
        rollback
    fi
    if [ -n "$RENDERED" ]; then rm -f "$RENDERED"; fi
    if [ -n "$CHECK_MAIN" ]; then rm -f "$CHECK_MAIN"; fi
    if [ -n "$ATTEMPT" ]; then rm -rf "$ATTEMPT"; fi
    if [ "$LOGGED_IN" = 1 ]; then docker logout "${GHCR_REGISTRY:-ghcr.io}" >/dev/null 2>&1 || true; fi
    exit "$rc"
}
trap on_exit EXIT

prune_releases() {
    local old active prev
    active="$(active_tag)"
    prev="$(cat "$STATE_DIR/previous" 2>/dev/null || true)"
    # Активный и предыдущий комплекты не удаляются: иначе ручной откат некуда делать
    # shellcheck disable=SC2012
    ls -1dt "$RELEASES_DIR"/*/ 2>/dev/null | tail -n +$((KEEP_RELEASES + 1)) | while read -r old; do
        case "$(basename "$old")" in
            "$active"|"$prev") continue ;;
        esac
        log "удаляю старый релиз $(basename "$old")"
        rm -rf "$old"
    done
    docker image prune -f >/dev/null 2>&1 || true
}

remember_previous() {
    # Только для восстановления при сбое: файл previous здесь не трогаем, иначе неудачная попытка
    # отката потеряет указатель на настоящий предыдущий комплект
    PREV_TAG="$(active_tag)"
    PREV_DIR=""
    if [ -n "$PREV_TAG" ] && [ -d "$RELEASES_DIR/$PREV_TAG" ]; then PREV_DIR="$RELEASES_DIR/$PREV_TAG"; fi
}

write_previous() {
    if [ -n "$PREV_TAG" ]; then printf '%s\n' "$PREV_TAG" > "$STATE_DIR/previous"; fi
}

apply_release() { # apply_release <тег>
    local tag="$1" dir="$CACHE_DIR/$tag" rel
    # Тег приходит из сети: он же становится именем каталога, поэтому формат проверяется
    [[ "$tag" =~ ^v[0-9]{8}-[0-9a-f]{7}$ ]] \
        || die "неожиданный тег релиза: '$tag' (ожидается v<ГГГГММДД>-<sha>)"
    rm -rf "$dir"
    log "загрузка релиза $tag из github.com/$MYTPU_REPO"
    download_release "$dir" || die "загрузка прервана — текущий релиз продолжает работать"
    verify_release_manifest "$tag" "$dir" || die "манифест релиза не сходится — переключение не начато"
    rel="$(stage_release "$tag" "$dir")" || die "релиз $tag не подготовлен — переключение не начато"
    log "релиз $tag подготовлен: $rel"
    # Проверки и запуск — по compose целевого релиза: на чистой установке активного compose ещё нет
    COMPOSE_FILE="$rel/compose.yml"
    COMPOSE_ENV="$ATTEMPT/compose.env"
    [ -f "$COMPOSE_FILE" ] || die "в релизе $tag нет compose.yml — переключение не начато"
    write_compose_env "$rel"
    compose config --quiet || die "Compose не принял конфигурацию релиза — переключение не начато"
    pull_images || die "образы не загружены — текущий релиз продолжает работать"
    prepare_caddyfile "$rel/Caddyfile"
    remember_previous
    save_active_state
    # Скрипт инициализации БД должен лежать на месте до первого старта postgres на новом томе
    SWITCHED=1
    install_postgres_init "$rel"
    log "запуск контейнеров релиза $tag"
    compose_up || die "контейнеры релиза $tag не запустились"
    wait_healthy postgres postgres_health 30
    wait_healthy rag rag_health 90
    wait_healthy gateway gateway_health 45
    wait_healthy assistant assistant_health 45

    log "переключение статики и конфига маршрутов"
    switch_static "$rel"
    install_caddyfile

    log "версия $tag переключена, проверка маршрутов"
    check_routes || die "маршруты после переключения не в порядке"
    install_compose "$rel"
    mv -f "$COMPOSE_ENV" "$ACTIVE_ENV"
    printf '%s\n' "$tag" > "$STATE_DIR/current-version"
    write_previous
    SWITCHED=0
    log "активна версия $tag"
    prune_releases
}

manual_rollback() {
    local prev; prev="$(cat "$STATE_DIR/previous" 2>/dev/null || true)"
    [ -n "$prev" ] && [ -d "$RELEASES_DIR/$prev" ] || die "предыдущего релиза нет"
    [ -f "$RELEASES_DIR/$prev/compose.yml" ] || die "в релизе $prev нет compose.yml"
    log "ручной откат на $prev"
    remember_previous
    verify_release_manifest "$prev" "$RELEASES_DIR/$prev" || die "манифест $prev не сходится"
    COMPOSE_FILE="$RELEASES_DIR/$prev/compose.yml"
    COMPOSE_ENV="$ATTEMPT/compose.env"
    write_compose_env "$RELEASES_DIR/$prev"
    compose config --quiet || die "Compose не принял конфигурацию отката"
    prepare_caddyfile "$RELEASES_DIR/$prev/Caddyfile"
    save_active_state
    SWITCHED=1
    install_postgres_init "$RELEASES_DIR/$prev"
    switch_static "$RELEASES_DIR/$prev"
    install_caddyfile
    # Версия записывается только после здоровья и маршрутов: иначе состояние соврёт о том, что запущено
    compose_up || die "откат на $prev не поднялся — нужен администратор"
    wait_healthy postgres postgres_health 30
    wait_healthy rag rag_health 90
    wait_healthy gateway gateway_health 45
    wait_healthy assistant assistant_health 45
    check_routes || die "после отката на $prev маршруты не в порядке — нужен администратор"
    install_compose "$RELEASES_DIR/$prev"
    mv -f "$COMPOSE_ENV" "$ACTIVE_ENV"
    printf '%s\n' "$prev" > "$STATE_DIR/current-version"
    write_previous
    SWITCHED=0
    log "активна версия $prev (откат вручную)"
}

main() {
    while [ $# -gt 0 ]; do
        case "$1" in
            --check) MODE=check; shift ;;
            --tag) ARG_TAG="${2:?--tag требует значение}"; shift 2 ;;
            --rollback) MODE=rollback; shift ;;
            --config) MODE=config; shift ;;
            -h|--help) usage; exit 0 ;;
            *) echo "mytpu-update: неизвестный аргумент $1" >&2; usage >&2; exit 2 ;;
        esac
    done

    [ "$(id -u)" -eq 0 ] || die "запускать от root (systemd-таймер или sudo)"
    [ -r "$SERVER_ENV" ] || die "нет $SERVER_ENV (install.sh, раздел «Первичная установка»)"
    mkdir -p "$STATE_DIR" "$RELEASES_DIR" "$CACHE_DIR" "$CONFIG_DIR/postgres-init"
    exec 9>"$LOCK_FILE" || die "нет доступа к $LOCK_FILE"
    # Второй запуск (таймер и ручной) не идёт параллельно и ничего не портит
    "${MYTPU_LOCK_CMD:-flock}" -n 9 || { log "обновление уже идёт — этот запуск пропущен"; exit 0; }

    set -a
    # shellcheck disable=SC1090
    . "$SERVER_ENV"
    set +a
    [ -n "${MYTPU_HOST:-}" ] || die "MYTPU_HOST не задан в $SERVER_ENV"

    case "$MODE" in
        rollback)
            ATTEMPT="$(mktemp -d "$STATE_DIR/.update.XXXXXX")"
            manual_rollback
            return 0 ;;
        config)
            ATTEMPT="$(mktemp -d "$STATE_DIR/.update.XXXXXX")"
            # Изменились сети консоли, режим TLS или пути: маршруты пересобираются из активного релиза
            local active_release; active_release="$(active_tag)"
            [ -n "$active_release" ] && [ -d "$RELEASES_DIR/$active_release" ] || die "нет активного релиза"
            prepare_caddyfile "$RELEASES_DIR/$active_release/Caddyfile"
            install_caddyfile
            if ! check_routes; then
                die "маршруты после пересборки конфига не в порядке — прежний конфиг возвращён"
            fi
            log "конфиг маршрутов пересобран из релиза $active_release"
            return 0 ;;
    esac

    local tag active
    tag="$(resolve_release_tag "$ARG_TAG")" || die "канал релизов недоступен"
    active="$(active_tag)"
    if [ "$MODE" = check ]; then
        log "канал: $tag; активна: ${active:-нет}"
        return 0
    fi
    if [ "$tag" = "$active" ]; then
        log "версия $tag уже активна — обновление пропущено"
        return 0
    fi
    ATTEMPT="$(mktemp -d "$STATE_DIR/.update.XXXXXX")"
    apply_release "$tag"
}

main "$@"
