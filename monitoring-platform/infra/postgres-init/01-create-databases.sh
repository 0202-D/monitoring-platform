#!/bin/bash
set -e

# Каждый сервис получает свою БД и своего пользователя — это подготовка
# к database-per-service, даже на этапе, когда физически контейнер Postgres один.
# Разделение на уровне логических БД уже даёт изоляцию прав доступа:
# notification-service физически не сможет прочитать таблицы core_api.

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE USER core_api WITH PASSWORD 'core_api';
    CREATE DATABASE core_api OWNER core_api;

    CREATE USER notification_service WITH PASSWORD 'notification_service';
    CREATE DATABASE notification_service OWNER notification_service;

    CREATE USER keycloak WITH PASSWORD 'keycloak';
    CREATE DATABASE keycloak OWNER keycloak;
EOSQL
