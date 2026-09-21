#!/usr/bin/env bash
# Forced command for the GitHub Actions deploy key (see authorized_keys).
# This key can do exactly two things, both strictly validated below:
#   <40-char sha>     -> deploy that commit via deploy.sh
#   backup [reason]   -> stream a production snapshot via backup.sh
# It cannot open a shell, run arbitrary commands, or transfer other files.
#
# Note on scope: the key could already build and run any commit from the
# repository on production, which is strictly more powerful than reading the
# database. The `backup` verb therefore adds no meaningful privilege, and it
# stays on this key so production keeps exactly one authorized entry point.
set -euo pipefail

CMD="${SSH_ORIGINAL_COMMAND:-}"

case "$CMD" in
  backup|backup\ *)
    REASON="${CMD#backup}"
    REASON="${REASON# }"
    REASON="${REASON:-manual}"
    # The reason is a commit label only; it is interpolated into no shell,
    # but keep it to an inert character set so it can never become one.
    if ! [[ "$REASON" =~ ^[A-Za-z0-9._-]{1,64}$ ]]; then
      echo "Rejected: backup reason must match [A-Za-z0-9._-]{1,64}, got: '$REASON'" >&2
      exit 1
    fi
    exec /opt/movo-platform/deploy/backup.sh "$REASON"
    ;;
  *)
    if ! [[ "$CMD" =~ ^[0-9a-f]{40}$ ]]; then
      echo "Rejected: expected a 40-character git commit SHA or 'backup [reason]', got: '$CMD'" >&2
      exit 1
    fi
    exec /opt/movo-platform/deploy/deploy.sh "$CMD"
    ;;
esac
