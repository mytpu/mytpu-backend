#!/usr/bin/env bash
# Создаёт случайные секреты для стенда в deploy/secrets/ (в git не попадают).
# Уже существующие файлы не перезаписываются. Имена — в deploy/secrets.example/README.md.
# На сервере ТПУ каталог и права другие: SECRETS_DIR=/etc/mytpu/secrets SECRETS_MODE=600 (install.sh).
set -euo pipefail
cd "$(dirname "$0")"
SECRETS_DIR="${SECRETS_DIR:-secrets}"
SECRETS_MODE="${SECRETS_MODE:-644}"
mkdir -p "$SECRETS_DIR"
# Каталог закрыт для других пользователей хоста; файлы читаемы, чтобы их прочитали контейнеры (uid 10001, postgres)
chmod 700 "$SECRETS_DIR"

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
    file="$SECRETS_DIR/$name"
    if [ ! -f "$file" ]; then
        # 32 случайных байта в base64url без «=»
        head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n' > "$file"
        chmod "$SECRETS_MODE" "$file"
        echo "создан $file"
    fi
done

# Ключ помощника к шлюзу (роль chat.service) выдаёт шлюз; пустой файл — инструменты помощника выключены
if [ ! -f "$SECRETS_DIR/assistant_gateway_key" ]; then
    touch "$SECRETS_DIR/assistant_gateway_key"
    chmod "$SECRETS_MODE" "$SECRETS_DIR/assistant_gateway_key"
    echo "создан $SECRETS_DIR/assistant_gateway_key (пустой: выдайте ключ chat.service — deploy/test-server/README.md)"
fi

# Адрес вебхука оповещений: пустой файл = выключено (не случайное значение)
if [ ! -f "$SECRETS_DIR/key_alert_webhook" ]; then
    touch "$SECRETS_DIR/key_alert_webhook"
    chmod "$SECRETS_MODE" "$SECRETS_DIR/key_alert_webhook"
    echo "создан $SECRETS_DIR/key_alert_webhook"
fi
