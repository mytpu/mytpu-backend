#!/usr/bin/env bash
# Замер памяти и CPU стенда одной командой (на ПК — ориентир, на сервере 4 ядра / 4 ГБ — факт):
#   bash deploy/load/measure.sh [клиентов=50] [секунд=300]
# Нужно: собранные jar-файлы, корпус в rag (rag-ingest), RAG_MODEL_HOST_DIR. Итог — в deploy/load/results/<время>/.
set -euo pipefail
umask 077
cd "$(dirname "$0")/../.."

WORKERS="${1:-50}"
SECONDS_TOTAL="${2:-300}"
PYTHON="${PYTHON:-python3}"
OUT="deploy/load/results/$(date +%Y%m%d-%H%M%S)"
COMPOSE=(docker compose -f deploy/compose.dev.yml -f deploy/compose.measure.yml)
mkdir -p "$OUT"

echo "== Запуск стенда с NMT"
"${COMPOSE[@]}" up -d --build postgres rag gateway assistant
for _ in $(seq 1 60); do
    curl -fs -m 5 -o /dev/null http://127.0.0.1:8081/actuator/health && break
    sleep 2
done
"${COMPOSE[@]}" logs gateway rag assistant 2>&1 | grep -E "Started .* in" > "$OUT/startup.txt" || true

echo "== Мастер-ключ для нагрузки (высокие лимиты, срок 1 день)"
"${COMPOSE[@]}" run --rm --no-deps -T gateway --spring.profiles.active=issue-key --owner="Нагрузочный тест" \
    --scopes=docs.read,rasp.read --rate=docs_search:100000,rasp_find:100000 --days=1 2>/dev/null \
    | grep -o 'mtpu_[A-Za-z0-9_-]*' | tail -1 > "$OUT/key"
chmod 600 "$OUT/key"

echo "== Простой (CPU и память до нагрузки)"
docker stats --no-stream --format '{{.Name}};{{.CPUPerc}};{{.MemUsage}}' > "$OUT/idle.csv"

echo "== Нагрузка: $WORKERS клиентов, $SECONDS_TOTAL с"
( while true; do docker stats --no-stream --format '{{.Name}};{{.CPUPerc}};{{.MemUsage}}' >> "$OUT/load.csv"; sleep 10; done ) &
SAMPLER=$!
"$PYTHON" deploy/load/mcp-load.py --key-file "$OUT/key" --workers "$WORKERS" --seconds "$SECONDS_TOTAL" | tee "$OUT/load.txt"
kill "$SAMPLER" 2>/dev/null || true

echo "== OOM и рестарты"
for c in mytpu-dev-gateway-1 mytpu-dev-rag-1 mytpu-dev-assistant-1 mytpu-dev-postgres-1; do
    docker inspect -f "$c oom={{.State.OOMKilled}} restarts={{.RestartCount}}" "$c"
done | tee "$OUT/oom.txt"

echo "== Секреты в логах (должно быть пусто)"
"${COMPOSE[@]}" logs gateway rag assistant 2>&1 \
    | grep -E 'mtpu_[A-Za-z0-9_-]{12,}|Bearer [A-Za-z0-9._~+/=-]{12,}|X-Internal-Key: ' > "$OUT/secrets-in-logs.txt" || true
wc -l < "$OUT/secrets-in-logs.txt"

echo "== Остановка: сводка NMT от JVM"
"${COMPOSE[@]}" stop gateway rag assistant
"${COMPOSE[@]}" logs gateway rag assistant 2>&1 | grep -E "Native Memory Tracking|Total: reserved|Java Heap|Class \(|Thread \(|Code \(|GC \(|Internal \(|Other \(|Symbol \(|Metaspace|Arena" > "$OUT/nmt.txt" || true
"${COMPOSE[@]}" up -d gateway rag assistant > /dev/null

echo "== Пик памяти под нагрузкой (МиБ)"
awk -F';' '{split($3, m, " / "); v=m[1]; if (v ~ /GiB/) {sub(/GiB/, "", v); v*=1024} else {sub(/MiB/, "", v)}; if (v+0 > max[$1]) max[$1]=v+0} END {for (c in max) printf "%s %.0f\n", c, max[c]}' "$OUT/load.csv" | sort | tee "$OUT/peak.txt"
echo "== Ключ нагрузки отключается"
docker exec mytpu-dev-postgres-1 psql -U postgres -d gateway -qc \
    "UPDATE gateway.api_clients SET enabled = false WHERE owner = 'Нагрузочный тест'" > /dev/null
echo "Итоги: $OUT"
