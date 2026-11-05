#!/usr/bin/env bash
# Start Ashore Prospect. Requires Docker Engine + Compose plugin.
set -e
cd "$(dirname "$0")"
docker compose up -d --build
echo
echo "Ashore Prospect is running at http://localhost:3456"
