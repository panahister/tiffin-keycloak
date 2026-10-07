#!/bin/sh
set -eu

: "${DATABASE_MIGRATION_URL:?DATABASE_MIGRATION_URL is required}"
: "${DATABASE_MIGRATION_USERNAME_FILE:?DATABASE_MIGRATION_USERNAME_FILE is required}"
: "${DATABASE_MIGRATION_PASSWORD_FILE:?DATABASE_MIGRATION_PASSWORD_FILE is required}"

case "${DATABASE_MIGRATION_MODE:-external}" in
  bundled) ;;
  external)
    case "$DATABASE_MIGRATION_URL" in
      *sslmode=verify-full*|*sslmode=verify-ca*) ;;
      *) echo "external migration URL requires sslmode=verify-full or verify-ca" >&2; exit 64 ;;
    esac
    ;;
  *) echo "DATABASE_MIGRATION_MODE must be bundled or external" >&2; exit 64 ;;
esac

[ -r "$DATABASE_MIGRATION_USERNAME_FILE" ] || { echo "migration username file is unreadable" >&2; exit 66; }
[ -r "$DATABASE_MIGRATION_PASSWORD_FILE" ] || { echo "migration password file is unreadable" >&2; exit 66; }
PGUSER=$(sed -e 's/[[:space:]]*$//' "$DATABASE_MIGRATION_USERNAME_FILE")
PGPASSWORD=$(sed -e 's/[[:space:]]*$//' "$DATABASE_MIGRATION_PASSWORD_FILE")
export PGUSER PGPASSWORD
[ -n "$PGUSER" ] && [ -n "$PGPASSWORD" ] || { echo "migration credentials are empty" >&2; exit 65; }

migration_root=${DATABASE_MIGRATION_ROOT:-/migrations}
[ -d "$migration_root" ] || { echo "migration directory is missing" >&2; exit 66; }
work_file=$(mktemp)
trap 'rm -f "$work_file"' EXIT HUP INT TERM

{
  echo 'BEGIN;'
  echo "SELECT pg_advisory_xact_lock(764129083117);"
  echo "CREATE TABLE IF NOT EXISTS kc_schema_history (version varchar(32) PRIMARY KEY, description varchar(160) NOT NULL, checksum_sha256 char(64) NOT NULL, installed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP);"
  for migration in "$migration_root"/V[0-9][0-9][0-9]__*.sql; do
    [ -f "$migration" ] || { echo "no versioned migrations found" >&2; exit 66; }
    filename=$(basename "$migration")
    version=${filename%%__*}
    description=${filename#*__}
    description=${description%.sql}
    checksum=$(sha256sum "$migration" | awk '{print $1}')
    echo "DO \$history\$ BEGIN IF EXISTS (SELECT 1 FROM kc_schema_history WHERE version='$version' AND checksum_sha256<>'$checksum') THEN RAISE EXCEPTION 'migration checksum mismatch for $version'; END IF; END \$history\$;"
    echo "SELECT EXISTS (SELECT 1 FROM kc_schema_history WHERE version='$version') AS already_applied \\gset"
    echo '\if :already_applied'
    echo "\\echo migration $version already applied"
    echo '\else'
    echo "\\ir $migration"
    echo "INSERT INTO kc_schema_history(version,description,checksum_sha256) VALUES ('$version','$description','$checksum') ON CONFLICT (version) DO NOTHING;"
    echo '\endif'
  done
  echo 'COMMIT;'
} > "$work_file"

psql "$DATABASE_MIGRATION_URL" --no-psqlrc --set=ON_ERROR_STOP=1 --file="$work_file"
echo "database migrations applied with locked schema history"
