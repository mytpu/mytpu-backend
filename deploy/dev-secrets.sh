#!/usr/bin/env bash
# Создаёт случайные секреты для локального стенда в deploy/secrets/ (в git не попадают).
# Уже существующие файлы не перезаписываются. Имена — в deploy/secrets.example/README.md.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p secrets
# Каталог закрыт для других пользователей хоста; файлы читаемы, чтобы их прочитали контейнеры (uid 10001, postgres)
chmod 700 secrets

names=(
    pg_superuser_password
    gateway_db_password
    rag_db_password
    assistant_db_password
    rag_internal_key
    gateway_audit_key
    connector_vault_key
    rag_admin_key
    ml_api_key
)

for name in "${names[@]}"; do
    file="secrets/$name"
    if [ ! -f "$file" ]; then
        # 32 случайных байта в base64url без «=»
        head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n' > "$file"
        chmod 644 "$file"
        echo "создан $file"
    fi
done

# Ключ помощника к шлюзу (роль chat.service) выдаёт шлюз; пустой файл — инструменты помощника выключены
if [ ! -f "secrets/assistant_gateway_key" ]; then
    touch "secrets/assistant_gateway_key"
    chmod 644 "secrets/assistant_gateway_key"
    echo "создан secrets/assistant_gateway_key (пустой: выдайте ключ chat.service — deploy/test-server/README.md)"
fi

# Адрес вебхука оповещений: пустой файл = выключено (не случайное значение)
if [ ! -f "secrets/key_alert_webhook" ]; then
    touch "secrets/key_alert_webhook"
    chmod 644 "secrets/key_alert_webhook"
    echo "создан secrets/key_alert_webhook"
fi
