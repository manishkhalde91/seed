#!/bin/sh
# Creates the seed database and its two roles on first start (empty data volume only).
#   seed_owner  owns the database and runs migrations (DDL).
#   seed_app    is used by the running application (DML only).
# Names and passwords come from the environment set in infrastructure/compose.yaml (.env).
set -eu

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v db_name="$DB_NAME" \
  -v owner_name="$DB_OWNER_USERNAME" -v owner_password="$DB_OWNER_PASSWORD" \
  -v app_name="$DB_USERNAME" -v app_password="$DB_PASSWORD" <<'SQL'
CREATE ROLE :"owner_name" LOGIN PASSWORD :'owner_password';
CREATE ROLE :"app_name" LOGIN PASSWORD :'app_password';
CREATE DATABASE :"db_name" OWNER :"owner_name";
REVOKE ALL ON DATABASE :"db_name" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db_name" TO :"app_name";
SQL

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" --dbname "$DB_NAME" \
  -v owner_name="$DB_OWNER_USERNAME" -v app_name="$DB_USERNAME" <<'SQL'
-- Only the owner creates objects; the app role reads and writes what the owner creates.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO :"owner_name";
GRANT USAGE ON SCHEMA public TO :"app_name";
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner_name" GRANT USAGE ON SCHEMAS TO :"app_name";
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner_name"
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_name";
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner_name" GRANT USAGE, SELECT ON SEQUENCES TO :"app_name";
SQL
