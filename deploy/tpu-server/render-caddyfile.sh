#!/usr/bin/env bash
# Готовит конфиг Caddy для стенда ТПУ из шаблона deploy/tpu-server/Caddyfile и параметров сервера.
#   bash render-caddyfile.sh /etc/mytpu/server.env deploy/tpu-server/Caddyfile > /tmp/mytpu.caddy
# Печатает готовый конфиг в stdout; ошибки — в stderr и код 2 (вызывающий не ставит такой файл).
# Незаполненный CONSOLE_ALLOWED_NETWORKS закрывает консоль (безопасное начальное состояние), а не открывает её.
set -euo pipefail

ENV_FILE="${1:?первый аргумент — файл параметров сервера (server.env)}"
TEMPLATE="${2:?второй аргумент — шаблон Caddyfile}"

die() { echo "render-caddyfile: $*" >&2; exit 2; }

[ -r "$ENV_FILE" ] || die "нет файла параметров $ENV_FILE"
[ -r "$TEMPLATE" ] || die "нет шаблона $TEMPLATE"

set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

HOST="${MYTPU_HOST:?MYTPU_HOST не задан в $ENV_FILE}"
console_root="${MYTPU_CONSOLE_ROOT:-/var/lib/mytpu/console}"
tls_mode="${MYTPU_TLS:-upstream}"

case "$tls_mode" in
    # HTTPS завершается на входном прокси ТПУ: Caddy слушает только loopback, клиентский адрес видит прокси
    upstream)
        listen="${MYTPU_LISTEN:-127.0.0.1:8443}"
        site="http://$listen"
        tls_line=""
        ;;
    # Сертификат ТПУ: файлы кладёт администратор (пути — MYTPU_TLS_CERT и MYTPU_TLS_KEY)
    caddy-cert)
        cert="${MYTPU_TLS_CERT:?MYTPU_TLS_CERT не задан (режим caddy-cert)}"
        key="${MYTPU_TLS_KEY:?MYTPU_TLS_KEY не задан (режим caddy-cert)}"
        [ -r "$cert" ] || die "нет файла сертификата $cert"
        [ -r "$key" ] || die "нет файла ключа $key"
        site="https://$HOST"
        tls_line=$'\ttls '"$cert $key"
        ;;
    # Домен резолвится в этот сервер и открыты 80/443 — Caddy сам берёт сертификат Let's Encrypt
    letsencrypt)
        site="$HOST"
        tls_line=""
        ;;
    *)
        die "MYTPU_TLS=$tls_mode — ожидается upstream, caddy-cert или letsencrypt"
        ;;
esac

# Сети, которым открыта консоль. Пусто — консоль и её API закрыты ответом 404.
networks="${CONSOLE_ALLOWED_NETWORKS:-}"
if [ -n "$networks" ]; then
    for net in $networks; do
        case "$net" in
            *[!0-9A-Fa-f:./]*) die "CONSOLE_ALLOWED_NETWORKS: '$net' — не адрес и не сеть (CIDR)" ;;
        esac
    done
    console_section=$(cat <<EOF
	# Консоль: только сети администраторов ТПУ; ключ и роли проверяет шлюз на порту консоли
	@console_closed not remote_ip $networks
	handle @console_closed {
		respond 404
	}

	# Без завершающего слеша Angular берёт неверный baseHref (/console/). Звёздочка — matcher «любой»:
	# без неё Caddy принимает первый аргумент redir за matcher и редирект не срабатывает
	handle /console {
		redir * /console/ 308
	}

	handle /console/* {
		root * "$console_root"
		uri strip_prefix /console
		try_files {path} /index.html
		file_server
		header /console/index.html Cache-Control "no-cache"
		@immutable path_regexp (?i)-[a-z0-9]{6,}\.(?:js|css|woff2?|ttf|svg|png|ico)\$
		header @immutable Cache-Control "public, max-age=31536000, immutable"
	}

	# API консоли — на порт консоли шлюза. X-Forwarded-For там не читается: адрес — адрес соединения
	handle /admin/api/* {
		request_body {
			max_size 32MB
		}
		reverse_proxy 127.0.0.1:18090
	}
EOF
)
else
    console_section=$(cat <<'EOF'
	# Консоль закрыта: CONSOLE_ALLOWED_NETWORKS не задан. Безопасное начальное состояние —
	# открывать только по инструкции deploy/tpu-server/README.md после согласования сетей
	@console_off path /console /console/*
	handle @console_off {
		respond 404
	}
	handle /admin/api/* {
		respond 404
	}
EOF
)
fi

section_file="$(mktemp)"
trap 'rm -f "$section_file"' EXIT
printf '%s\n' "$console_section" > "$section_file"

out="$(awk -v site="$site" -v tls_line="$tls_line" -v root="$console_root" -v section="$section_file" '
    /^\{\{CONSOLE_SECTION\}\}/ { while ((getline line < section) > 0) print line; close(section); next }
    /^\{\{TLS_LINE\}\}/       { if (tls_line != "") print tls_line; next }
    {
        gsub(/\{\{SITE_ADDRESS\}\}/, site)
        gsub(/\{\{CONSOLE_ROOT\}\}/, root)
        print
    }
' "$TEMPLATE")" || die "не удалось разобрать шаблон $TEMPLATE"

# Незаполненная подстановка означает шаблон новее установленного скрипта: такой файл не ставим
if printf '%s\n' "$out" | grep -q '{{'; then
    die "в шаблоне осталась незаполненная подстановка: $(printf '%s\n' "$out" | grep -m1 '{{')"
fi

# Простая проверка структуры до caddy validate (её делает вызывающий на сервере и CI)
open="$(printf '%s\n' "$out" | tr -cd '{' | wc -c)"
close="$(printf '%s\n' "$out" | tr -cd '}' | wc -c)"
[ "$open" -eq "$close" ] || die "не совпадает число скобок: { = $open, } = $close"

printf '%s\n' "$out"
