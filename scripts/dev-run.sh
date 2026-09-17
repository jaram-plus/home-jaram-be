#!/usr/bin/env bash
set -euo pipefail

# Local dev runner for jaram-be.
#
# Runs the Spring Boot app against a throwaway Docker Postgres mapped to
# host port 5433, so it never touches a host Postgres already on :5432.
# The app reads DB_URL/DB_USER/DB_PASSWORD (see application.yml); we set
# them here to point at the container.

CONTAINER=jaram-pg
PG_IMAGE=postgres:16-alpine
HOST_PORT=5433
DB_NAME=jaram
DB_USER=jaram
DB_PASSWORD=jaram

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

db_up() {
  if [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" = "true" ]; then
    echo "DB already running ($CONTAINER on :$HOST_PORT)"
  else
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    echo "Starting Postgres ($PG_IMAGE) on :$HOST_PORT ..."
    docker run -d --name "$CONTAINER" \
      -e POSTGRES_USER="$DB_USER" \
      -e POSTGRES_PASSWORD="$DB_PASSWORD" \
      -e POSTGRES_DB="$DB_NAME" \
      -p "$HOST_PORT:5432" "$PG_IMAGE" >/dev/null
  fi
  printf 'Waiting for Postgres '
  for _ in $(seq 1 30); do
    if docker exec "$CONTAINER" pg_isready -U "$DB_USER" >/dev/null 2>&1; then
      echo "ready."
      return 0
    fi
    printf '.'
    sleep 1
  done
  echo "timed out." >&2
  exit 1
}

db_down() {
  if docker rm -f "$CONTAINER" >/dev/null 2>&1; then
    echo "Removed $CONTAINER"
  else
    echo "No $CONTAINER container"
  fi
}

run_app() {
  db_up
  export DB_URL="jdbc:postgresql://localhost:$HOST_PORT/$DB_NAME"
  export DB_USER DB_PASSWORD
  echo "Starting app -> http://localhost:8080 (DB_URL=$DB_URL)"
  exec ./gradlew bootRun
}

case "${1:-up}" in
  up | run | "") run_app ;;
  db:up)         db_up ;;
  db:down)       db_down ;;
  db:logs)       docker logs -f "$CONTAINER" ;;
  *)
    echo "usage: $0 [up|db:up|db:down|db:logs]" >&2
    exit 2
    ;;
esac
