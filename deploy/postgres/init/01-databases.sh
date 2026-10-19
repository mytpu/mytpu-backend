#!/usr/bin/env bash
# Первый запуск Postgres: у каждого сервиса своя БД и свой пользователь, чужие БД ему недоступны.
# Пароли — из файлов-секретов, в логи и аргументы командной строки не попадают.
set -euo pipefail

create_service_db() {
    local name="$1"
    # Пароль читает сам psql из файла (\set с командой), он не попадает в аргументы процесса
    psql -v ON_ERROR_STOP=1 -U postgres -d postgres -v name="$name" <<SQL
\set password \`cat /run/secrets/${name}_db_password\`
CREATE ROLE :"name" LOGIN PASSWORD :'password';
CREATE DATABASE :"name" OWNER :"name";
REVOKE ALL ON DATABASE :"name" FROM PUBLIC;
SQL
}

create_service_db gateway
create_service_db rag
create_service_db assistant

# pgvector ставит только суперпользователь; дальше rag работает с ним под своим пользователем
psql -v ON_ERROR_STOP=1 -U postgres -d rag -c "CREATE EXTENSION IF NOT EXISTS vector;"
