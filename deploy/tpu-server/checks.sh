#!/usr/bin/env bash
# Вспомогательные проверки стенда ТПУ: HTTP, здоровье контейнеров, маршруты. Подключается из mytpu-update.sh
# (source checks.sh). Ничего не меняет — только читает и возвращает код 0/1.
# Ожидаемые значения приходят из окружения: COMPOSE_FILE, COMPOSE_ENV, BASE_URL, CONSOLE_OPEN, MYTPU_HOST.

log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" >&2; }

http_code() { # http_code <url> [curl-args...]
    local url="$1" code; shift
    # Код ответа печатается и при ошибке соединения: curl без -f возвращает 0, но сеть может не ответить
    code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 20 "$@" "$url" 2>/dev/null)" || true
    printf '%s' "${code:-000}"
}
http_body() { # http_body <url> [curl-args...]
    local url="$1"; shift
    curl -sS --max-time 20 "$@" "$url" 2>/dev/null || true
}
compose() { docker compose -f "$COMPOSE_FILE" --env-file "$COMPOSE_ENV" "$@"; }

wait_until() { # wait_until <попыток> <пауза, с> <команда...>
    local tries="$1" pause="$2"; shift 2
    local i
    for i in $(seq 1 "$tries"); do
        if "$@" >/dev/null 2>&1; then return 0; fi
        sleep "$pause"
    done
    return 1
}

# Gateway и assistant опубликованы на loopback, поэтому их здоровье видно с хоста
gateway_health() { curl -fsS --max-time 10 "http://127.0.0.1:8081/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; }
assistant_health() { curl -fsS --max-time 10 "http://127.0.0.1:8082/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; }
# У rag порт наружу не публикуется: спрашиваем actuator изнутри контейнера (bash есть в базовом образе)
rag_health() {
    compose exec -T rag bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 || exit 1;
        printf "GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" >&3;
        timeout 10 cat <&3' 2>/dev/null | grep -q '"status":"UP"'
}
postgres_health() { compose exec -T postgres pg_isready -U postgres >/dev/null 2>&1; }

# Базовый адрес стенда глазами самого сервера: в режиме upstream — loopback Caddy, иначе — публичный домен
stand_base_url() {
    case "${MYTPU_TLS:-upstream}" in
        upstream) printf 'http://%s' "${MYTPU_LISTEN:-127.0.0.1:8443}" ;;
        *) printf 'https://%s' "${MYTPU_HOST:?}" ;;
    esac
}
# Для caddy-cert адрес домена резолвится наружу: проверяем свой сервер, а не DNS
stand_curl_opts() {
    if [ "${MYTPU_TLS:-upstream}" = "caddy-cert" ]; then
        printf '%s\n' "-k" "--resolve" "$MYTPU_HOST:443:127.0.0.1"
    fi
}

# Консоль открыта с этого хоста, только если её сети разрешают 127.0.0.1: иначе маршрут отвечает 404 и
# правильность настройки сетей проверяет администратор с разрешённого адреса (README.md)
console_open_locally() {
    case " ${CONSOLE_ALLOWED_NETWORKS:-} " in
        *" 127.0.0.1"*|*"/0 "*) return 0 ;;
        *) return 1 ;;
    esac
}

# Маршруты после переключения. Публичные пути обязаны работать всегда, консоль — по сетям.
check_routes() {
    local base fails=0 code opts=()
    # shellcheck disable=SC2207
    opts=($(stand_curl_opts))
    base="$(stand_base_url)"

    expect_code() { # expect_code <описание> <ожидаемое> <url> [curl-args...]
        local what="$1" want="$2" url="$3"; shift 3
        code="$(http_code "$url" "${opts[@]}" "$@")"
        case "|$want|" in
            *"|$code|"*) log "ok: $what ($code)" ;;
            *) log "FAIL: $what — ожидалось $want, получено $code"; fails=$((fails + 1)) ;;
        esac
    }

    expect_code "health через Caddy" 200 "$base/actuator/health"
    http_body "$base/actuator/health" "${opts[@]}" | grep -q '"status":"UP"' \
        && log "ok: health UP" \
        || { log "FAIL: health не UP"; fails=$((fails + 1)); }
    # Публичные маршруты без ключа отвечают отказом доступа, а не 404: значит проксирование работает
    expect_code "/mcp без ключа" 401 "$base/mcp" -X POST -H 'Content-Type: application/json' --data '{}'
    expect_code "/api/ai/chat без ключа" 401 "$base/api/ai/chat" -X POST -H 'Content-Type: application/json' --data '{}'
    expect_code "/internal/tools закрыт" 404 "$base/internal/tools"
    expect_code "корень сайта закрыт" 404 "$base/"
    expect_code "обход через ; отклонён" 400 "$base/mcp/..;/admin/api/keys" --path-as-is
    # Обход через %2f Caddy нормализует и отдаёт 404 — важно, что файл не отдан
    expect_code "обход через %2f отклонён" "400|404" "$base/console/..%2f..%2fetc/passwd"
    expect_code "админский API на основном порту закрыт" 404 "http://127.0.0.1:8081/admin/api/keys"

    if console_open_locally; then
        expect_code "/console/ с разрешённого адреса" 200 "$base/console/"
        http_body "$base/console/" "${opts[@]}" | grep -q '<base href="/console/' \
            && log "ok: /console/ отдаёт сборку с baseHref /console/" \
            || { log "FAIL: /console/ без baseHref /console/"; fails=$((fails + 1)); }
        expect_code "вложенная страница консоли" 200 "$base/console/keys"
        expect_code "API консоли без ключа" 401 "$base/admin/api/keys"
    else
        log "консоль закрыта для 127.0.0.1 — маршруты /console и /admin/api проверяет администратор с разрешённой сети"
        expect_code "/console/ закрыт для внешнего адреса" 404 "$base/console/"
        expect_code "/admin/api закрыт для внешнего адреса" 404 "$base/admin/api/keys"
    fi

    return "$([ "$fails" -eq 0 ] && echo 0 || echo 1)"
}
