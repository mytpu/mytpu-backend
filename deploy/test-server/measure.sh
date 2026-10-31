#!/usr/bin/env bash
# Замер потребления ресурсов стенда на сервере в покое и под нагрузкой.
# Запускается на сервере:
#   bash deploy/test-server/measure.sh [--load <файл-с-ключом>]
set -euo pipefail

KEY_FILE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --load)
            shift
            if [ $# -eq 0 ]; then
                echo "Ошибка: параметр --load требует путь к файлу с мастер-ключом." >&2
                exit 1
            fi
            KEY_FILE="$1"
            ;;
        *)
            echo "Неизвестный параметр: $1" >&2
            echo "Использование: $0 [--load <файл-с-ключом>]" >&2
            exit 1
            ;;
    esac
    shift
done

echo "=================================================================="
echo "  Замер потребления ресурсов: состояние покоя (idle)"
echo "=================================================================="
docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}"

if [ -n "$KEY_FILE" ]; then
    if [ ! -f "$KEY_FILE" ]; then
        echo "Ошибка: файл ключа $KEY_FILE не найден." >&2
        exit 1
    fi

    # Секрет считывается в переменную без вывода в консоль
    API_KEY="$(tr -d '\r\n' < "$KEY_FILE")"

    echo ""
    echo "=================================================================="
    echo "  Запуск 20 параллельных вызовов docs_search к http://127.0.0.1:8081/mcp"
    echo "=================================================================="

    QUERIES=(
        "как получить справку об обучении"
        "как восстановиться после отчисления"
        "как уйти в академический отпуск"
        "какие есть стипендии"
        "как заселиться в общежитие"
        "как поменять пароль от учётной записи"
        "где найти учебный план"
        "как подать апелляцию"
        "есть ли бассейн"
        "как получить материальную помощь"
    )

    # Запуск 20 параллельных запросов curl в фоновом режиме
    for i in $(seq 1 20); do
        q_idx=$(( (i - 1) % ${#QUERIES[@]} ))
        query="${QUERIES[$q_idx]} $i"
        payload=$(printf '{"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"docs_search","arguments":{"query":"%s"}}}' "$i" "$query")

        (
            curl -s -o /dev/null -X POST "http://127.0.0.1:8081/mcp" \
                -H "Content-Type: application/json" \
                -H "Accept: application/json, text/event-stream" \
                -H "Authorization: Bearer $API_KEY" \
                -d "$payload"
        ) &
    done

    # 3 снимка docker stats во время обработки нагрузки
    for s in 1 2 3; do
        sleep 1
        echo ""
        echo "--- Снимок нагрузки #$s ---"
        docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}"
    done

    # Ожидание завершения всех параллельных вызовов
    wait

    echo ""
    echo "=================================================================="
    echo "  Нагрузочный замер завершён"
    echo "=================================================================="
fi
