#!/usr/bin/env bash
# Streams a consistent, self-describing snapshot of production to stdout.
# Invoked only through deploy/ssh-wrapper.sh's `backup` verb — never a shell.
#
# Usage: backup.sh [reason]

set -euo pipefail

# The tarball is the ONLY thing allowed on stdout: the CI runner pipes this
# straight into a file, so a stray echo would corrupt the archive. Save the
# real stdout on fd 3 and send every diagnostic to stderr.
exec 3>&1 1>&2

ROOT=/opt/movo-platform
DATA=/var/lib/docker/volumes/movo-platform_movo-data/_data
UPLOADS=/var/lib/docker/volumes/movo-platform_movo-uploads/_data
REASON="${1:-manual}"

for p in "$DATA/movo.db" "$ROOT/.env" "$ROOT/Caddyfile" "$ROOT/.deploy-state"; do
  [[ -e "$p" ]] || { echo "[backup] FATAL: missing $p" >&2; exit 1; }
done

STAGE=$(mktemp -d); trap 'rm -rf "$STAGE"' EXIT

echo "[backup] snapshotting database"
# movo.db, -wal and -shm must be captured in ONE tar invocation. The WAL on this
# host is routinely larger than the database itself, so a snapshot of movo.db
# alone restores cleanly, passes integrity_check, and is silently missing the
# most recent writes. Capturing all three lets SQLite replay the WAL on open.
tar cz -C "$DATA" . > "$STAGE/movo-db.tar.gz"
tar cz -C "$UPLOADS" . > "$STAGE/uploads.tar.gz"

cp "$ROOT/.env"          "$STAGE/env.backup"
cp "$ROOT/Caddyfile"     "$STAGE/Caddyfile"
cp "$ROOT/.deploy-state" "$STAGE/deploy-state"

cat > "$STAGE/SNAPSHOT_INFO" <<INFO
taken_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
stamp=$(date -u +%Y%m%dT%H%M%SZ)
reason=$REASON
host=$(hostname)
deployed_sha=$(cut -d= -f2 "$ROOT/.deploy-state")
db_bytes=$(stat -c%s "$DATA/movo.db")
wal_bytes=$(stat -c%s "$DATA/movo.db-wal" 2>/dev/null || echo 0)
uploads_files=$(find "$UPLOADS" -type f | wc -l)
INFO
cat "$STAGE/SNAPSHOT_INFO"

echo "[backup] streaming $(du -sh "$STAGE" | cut -f1)"
tar cz -C "$STAGE" . >&3
echo "[backup] ok"
