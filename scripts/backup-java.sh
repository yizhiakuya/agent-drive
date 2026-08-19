#!/usr/bin/env bash
# Create a Java-era Agent Drive backup: PostgreSQL dump plus owner file data.
set -euo pipefail

umask 077

BACKUP_DIR="${AGENT_DRIVE_BACKUP_DIR:-/opt/agent-drive-java/backups}"
DATA_DIR="${AGENT_DRIVE_DATA_DIR:-/opt/agent-drive-java/data}"
POSTGRES_CONTAINER="${AGENT_DRIVE_POSTGRES_CONTAINER:-agent-drive-java-postgres}"
DATABASE_USER="${AGENT_DRIVE_DATABASE_USERNAME:-agent_drive}"
DATABASE_NAME="${AGENT_DRIVE_DATABASE_NAME:-agent_drive}"
DATABASE_PASSWORD="${AGENT_DRIVE_DATABASE_PASSWORD:-}"
TIMESTAMP="$(date -u +%Y%m%d-%H%M%S)"
ARCHIVE="$BACKUP_DIR/agent-drive-java-$TIMESTAMP.tar.gz"
STAGING_DIR=""

cleanup() {
  if [[ -n "$STAGING_DIR" && -d "$STAGING_DIR" ]]; then
    rm -rf -- "$STAGING_DIR"
  fi
}
trap cleanup EXIT

command -v docker >/dev/null
command -v gzip >/dev/null
command -v sha256sum >/dev/null
command -v tar >/dev/null
[[ -d "$DATA_DIR" ]] || { echo "data directory does not exist: $DATA_DIR" >&2; exit 1; }

mkdir -p "$BACKUP_DIR"
STAGING_DIR="$(mktemp -d "$BACKUP_DIR/.agent-drive-java-backup.XXXXXX")"
DUMP="$STAGING_DIR/postgres.sql.gz"

docker_args=()
if [[ -n "$DATABASE_PASSWORD" ]]; then
  docker_args=(-e "PGPASSWORD=$DATABASE_PASSWORD")
fi

docker exec "${docker_args[@]}" "$POSTGRES_CONTAINER" \
  pg_dump --clean --if-exists --no-owner --no-privileges \
  --format=plain --username="$DATABASE_USER" --dbname="$DATABASE_NAME" \
  | gzip -9 > "$DUMP"

{
  printf 'created_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'data_dir=%s\n' "$DATA_DIR"
  printf 'postgres_container=%s\n' "$POSTGRES_CONTAINER"
  printf 'postgres_database=%s\n' "$DATABASE_NAME"
  printf 'postgres_user=%s\n' "$DATABASE_USER"
} > "$STAGING_DIR/backup-manifest.txt"

# Atomic file publication is handled by the application; tar captures the owner tree
# while excluding transient locks and staging namespaces.
tar -czf "$ARCHIVE" \
  --exclude='.storage.lock' \
  --exclude='.upload.*' \
  --exclude='.copy.*' \
  --exclude='.copy-old.*' \
  -C "$DATA_DIR" . \
  -C "$STAGING_DIR" backup-manifest.txt postgres.sql.gz

sha256sum "$ARCHIVE" > "$ARCHIVE.sha256"

mapfile -t old_archives < <(
  find "$BACKUP_DIR" -maxdepth 1 -type f -name 'agent-drive-java-*.tar.gz' \
    -printf '%T@ %p\n' | sort -nr | tail -n +8 | cut -d' ' -f2-
)
for old_archive in "${old_archives[@]}"; do
  rm -f -- "$old_archive" "$old_archive.sha256"
done

echo "backup complete: $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1))"
echo "retained Java backups: $(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'agent-drive-java-*.tar.gz' | wc -l)"
