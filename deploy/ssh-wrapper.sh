#!/usr/bin/env bash
# Forced command for the GitHub Actions deploy key (see authorized_keys).
# This key can do exactly one thing: trigger deploy.sh for a specific,
# strictly-validated commit SHA. It cannot open a shell, run arbitrary
# commands, or transfer files.
set -euo pipefail

SHA="${SSH_ORIGINAL_COMMAND:-}"

if ! [[ "$SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Rejected: expected a 40-character git commit SHA as the command, got: '$SHA'" >&2
  exit 1
fi

exec /opt/movo-platform/deploy/deploy.sh "$SHA"
