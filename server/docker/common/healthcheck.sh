#!/bin/sh

set -e

ENDPOINT=$(cat /tmp/filebrowser-health-endpoint 2>/dev/null || true)
ENDPOINT=${ENDPOINT:-http://127.0.0.1:${FB_PORT:-80}/health}

wget -q --spider "$ENDPOINT" || exit 1
