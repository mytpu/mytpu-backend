#!/usr/bin/env bash
# Канал релизов GitHub для стенда ТПУ: выбор версии, загрузка артефактов, проверка SHA256SUMS и манифеста,
# подготовка каталога релиза. Подключается из mytpu-update.sh (source release.sh). Меняет только каталог
# загрузки и каталог релиза; контейнеры и Caddy не трогает.
# Окружение: MYTPU_REPO, MYTPU_CHANNEL, SECRETS_DIR, CACHE_DIR, RELEASES_DIR.

# API-запрос с токеном из файла: токен не попадает в аргументы процесса (curl --config /dev/fd)
github_curl() { # github_curl <url> <файл-назначение>
    local url="$1" out="$2" token_file="$SECRETS_DIR/github_token"
    if [ -s "$token_file" ]; then
        curl -fsS --max-time 120 --config <(printf 'header = "Authorization: Bearer %s"\n' "$(tr -d '\r\n' < "$token_file")") \
            -H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28' -o "$out" "$url"
    else
        curl -fsS --max-time 120 -H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28' -o "$out" "$url"
    fi
}

# Имя и адрес файлов релиза: строки «имя<TAB>url»
release_assets() { # release_assets <api.json>
    python3 - "$1" <<'PY'
import json, sys
release = json.load(open(sys.argv[1], encoding="utf-8"))
for asset in release.get("assets", []):
    print("%s\t%s" % (asset["name"], asset["browser_download_url"]))
PY
}

json_field() { # json_field <файл> <путь через точку>
    python3 - "$1" "$2" <<'PY'
import json, sys
value = json.load(open(sys.argv[1], encoding="utf-8"))
for part in sys.argv[2].split("."):
    value = value[int(part)] if isinstance(value, list) else value[part]
print(value)
PY
}

verify_digest_ref() { # verify_digest_ref <имя> <ссылка на образ>
    local name="$1" ref="$2"
    case "$ref" in
        *"@sha256:"*) ;;
        *) echo "release: $name — ссылка без digest: $ref" >&2; return 1 ;;
    esac
    printf '%s' "$ref" | grep -Eq '@sha256:[0-9a-f]{64}$' \
        || { echo "release: $name — неверный digest: $ref" >&2; return 1; }
}

# Выбранная версия: последний релиз канала или явный тег из MYTPU_CHANNEL/аргумента
resolve_release_tag() { # resolve_release_tag [тег] → печатает тег, оставляет $CACHE_DIR/api.json
    local want="${1:-${MYTPU_CHANNEL:-latest}}" url
    case "$want" in
        latest|"") url="https://api.github.com/repos/$MYTPU_REPO/releases/latest" ;;
        *) url="https://api.github.com/repos/$MYTPU_REPO/releases/tags/$want" ;;
    esac
    github_curl "$url" "$CACHE_DIR/api.json" || {
        echo "release: GitHub недоступен или релиз не найден ($url) — текущая версия продолжает работать" >&2
        return 1
    }
    json_field "$CACHE_DIR/api.json" tag_name
}

# Загрузка всех файлов релиза в каталог с проверкой SHA256SUMS
download_release() { # download_release <каталог>
    local dir="$1" name url token_file="$SECRETS_DIR/github_token"
    local -a auth=()
    mkdir -p "$dir"
    release_assets "$CACHE_DIR/api.json" > "$dir/.assets" || return 1
    while IFS="$(printf '\t')" read -r name url; do
        [ -n "$name" ] || continue
        if [ -s "$token_file" ]; then
            curl -fsSL --max-time 600 --config <(printf 'header = "Authorization: Bearer %s"\n' "$(tr -d '\r\n' < "$token_file")") \
                -o "$dir/$name" "$url" || { echo "release: не скачан $name" >&2; return 1; }
        else
            curl -fsSL --max-time 600 -o "$dir/$name" "$url" || { echo "release: не скачан $name" >&2; return 1; }
        fi
    done < "$dir/.assets"
    rm -f "$dir/.assets"
    for name in release.json SHA256SUMS compose.yml Caddyfile console.tar.gz 01-databases.sh; do
        [ -s "$dir/$name" ] || { echo "release: в релизе нет файла $name" >&2; return 1; }
    done
    ( cd "$dir" && sha256sum --quiet -c SHA256SUMS ) || {
        echo "release: контрольные суммы не совпали — релиз неполный или подменён" >&2
        return 1
    }
}

# Сверка манифеста: версия совпадает с тегом, у всех образов digest, статика сходится с SHA256SUMS
verify_release_manifest() { # verify_release_manifest <тег> <каталог> → IMG_GATEWAY/IMG_RAG/IMG_ASSISTANT
    local tag="$1" dir="$2" version console_sum
    version="$(json_field "$dir/release.json" version)"
    [ "$version" = "$tag" ] || { echo "release: манифест версии $version не совпадает с тегом $tag" >&2; return 1; }

    IMG_GATEWAY="$(json_field "$dir/release.json" images.gateway)"
    IMG_RAG="$(json_field "$dir/release.json" images.rag)"
    IMG_ASSISTANT="$(json_field "$dir/release.json" images.assistant)"
    verify_digest_ref gateway "$IMG_GATEWAY" || return 1
    verify_digest_ref rag "$IMG_RAG" || return 1
    verify_digest_ref assistant "$IMG_ASSISTANT" || return 1

    console_sum="$(json_field "$dir/release.json" console.sha256)"
    # Формат SHA256SUMS: «хеш  файл» в Linux и «хеш *файл» в Git Bash — принимаем оба
    local sums_console
    sums_console="$(awk '$2 ~ /console\.tar\.gz$/ { print $1 }' "$dir/SHA256SUMS")"
    [ -n "$sums_console" ] || { echo "release: в SHA256SUMS нет console.tar.gz" >&2; return 1; }
    [ "$sums_console" = "$console_sum" ] \
        || { echo "release: хеш статики в манифесте не совпадает с SHA256SUMS" >&2; return 1; }
    export IMG_GATEWAY IMG_RAG IMG_ASSISTANT
}

# Каталог релиза: compose, шаблон Caddy, init БД и распакованная статика консоли
stage_release() { # stage_release <тег> <каталог загрузки> → печатает путь каталога релиза
    local tag="$1" src="$2" dst="$RELEASES_DIR/$tag"
    rm -rf "$dst"
    mkdir -p "$dst/postgres/init" "$dst/console"
    cp "$src/compose.yml" "$dst/compose.yml"
    cp "$src/Caddyfile" "$dst/Caddyfile"
    cp "$src/release.json" "$dst/release.json"
    cp "$src/SHA256SUMS" "$dst/SHA256SUMS"
    cp "$src/01-databases.sh" "$dst/postgres/init/01-databases.sh"
    tar -xzf "$src/console.tar.gz" -C "$dst/console" || { echo "release: не распаковалась статика консоли" >&2; return 1; }
    [ -s "$dst/console/index.html" ] || { echo "release: в архиве статики нет index.html" >&2; return 1; }
    grep -q 'base href="/console/"' "$dst/console/index.html" \
        || { echo "release: сборка консоли без baseHref /console/ — проверьте angular.json и CI" >&2; return 1; }
    # Статику читает пользователь caddy (не root)
    chmod -R a+rX "$dst"
    printf '%s\n' "$dst"
}
