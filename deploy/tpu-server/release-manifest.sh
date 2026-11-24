#!/usr/bin/env bash
# Манифест релиза: release.json с digest образов, SHA исходников и хешами файлов выкладки, плюс SHA256SUMS.
# Вызывается из CI после сборки образов и статики; сервер сверяет по нему релиз (mytpu-update.sh).
#   bash deploy/tpu-server/release-manifest.sh --out dist --version v20261125-742d583 \
#        --source-sha 742d583... --gateway ghcr.io/...@sha256:... --rag ... --assistant ...
set -euo pipefail

OUT="" VERSION="" SOURCE_SHA="" GW="" RAG="" AS=""
die() { echo "release-manifest: $*" >&2; exit 2; }

while [ $# -gt 0 ]; do
    case "$1" in
        --out) OUT="$2"; shift 2 ;;
        --version) VERSION="$2"; shift 2 ;;
        --source-sha) SOURCE_SHA="$2"; shift 2 ;;
        --gateway) GW="$2"; shift 2 ;;
        --rag) RAG="$2"; shift 2 ;;
        --assistant) AS="$2"; shift 2 ;;
        *) die "неизвестный аргумент $1" ;;
    esac
done

[ -n "$OUT" ] && [ -d "$OUT" ] || die "--out — существующий каталог с файлами релиза"
[ -n "$VERSION" ] || die "--version обязателен"
[ -n "$SOURCE_SHA" ] || die "--source-sha обязателен"
for ref in "$GW" "$RAG" "$AS"; do
    printf '%s' "$ref" | grep -Eq '@sha256:[0-9a-f]{64}$' || die "образ без digest: '$ref'"
done
for name in compose.yml Caddyfile console.tar.gz 01-databases.sh; do
    [ -s "$OUT/$name" ] || die "нет файла $OUT/$name"
done

python3 - "$OUT" "$VERSION" "$SOURCE_SHA" "$GW" "$RAG" "$AS" <<'PY'
import datetime, hashlib, json, os, sys

out, version, source_sha, gateway, rag, assistant = sys.argv[1:7]

def sha256(name):
    digest = hashlib.sha256()
    with open(os.path.join(out, name), "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()

manifest = {
    "version": version,
    "source_sha": source_sha,
    "created_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "images": {"gateway": gateway, "rag": rag, "assistant": assistant},
    # Статика консоли — архив с SHA-256: обновляется и откатывается вместе с образами API
    "console": {"file": "console.tar.gz", "sha256": sha256("console.tar.gz")},
    "files": {
        "compose.yml": sha256("compose.yml"),
        "Caddyfile": sha256("Caddyfile"),
        "01-databases.sh": sha256("01-databases.sh"),
    },
}
with open(os.path.join(out, "release.json"), "w", encoding="utf-8") as handle:
    json.dump(manifest, handle, ensure_ascii=False, indent=2, sort_keys=True)
    handle.write("\n")
print("release.json: %s (%s)" % (version, source_sha[:12]))
PY

# SHA256SUMS — то, что сервер проверяет до распаковки и переключения
( cd "$OUT" && sha256sum compose.yml Caddyfile console.tar.gz 01-databases.sh release.json > SHA256SUMS )
echo "SHA256SUMS: $(wc -l < "$OUT/SHA256SUMS") файлов"
