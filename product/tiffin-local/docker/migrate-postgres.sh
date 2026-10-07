#!/bin/sh
set -eu

: "${POSTGRES_HOST:?POSTGRES_HOST is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"

export PGPASSWORD="$POSTGRES_PASSWORD"

database_exists=$(psql \
  --host "$POSTGRES_HOST" \
  --username "$POSTGRES_USER" \
  --dbname postgres \
  --no-psqlrc \
  --tuples-only \
  --no-align \
  --command "SELECT 1 FROM pg_database WHERE datname='tiffin_keycloak'")

if [ "$database_exists" != "1" ]; then
  createdb --host "$POSTGRES_HOST" --username "$POSTGRES_USER" tiffin_keycloak
fi

secret_dir=$(mktemp -d)
trap 'rm -rf "$secret_dir"' EXIT HUP INT TERM
printf '%s' "$POSTGRES_USER" > "$secret_dir/username"
printf '%s' "$POSTGRES_PASSWORD" > "$secret_dir/password"
chmod 0600 "$secret_dir/username" "$secret_dir/password"

export DATABASE_MIGRATION_MODE=bundled
export DATABASE_MIGRATION_URL="postgresql://${POSTGRES_HOST}:5432/tiffin_keycloak?sslmode=disable"
export DATABASE_MIGRATION_USERNAME_FILE="$secret_dir/username"
export DATABASE_MIGRATION_PASSWORD_FILE="$secret_dir/password"
export DATABASE_MIGRATION_ROOT=/migrations

exec /source/scripts/migrate-postgres.sh
