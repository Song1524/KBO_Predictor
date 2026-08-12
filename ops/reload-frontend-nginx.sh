#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR=/home/ubuntu/KBO_Predictor

cd "$PROJECT_DIR"
docker compose exec -T frontend /usr/local/bin/select-playball-nginx-config
docker compose exec -T frontend nginx -t
docker compose exec -T frontend nginx -s reload

echo "Frontend Nginx configuration reloaded."
