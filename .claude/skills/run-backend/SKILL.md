---
name: run-backend
description: Use when running, starting, or smoke-testing the jaram-be Spring Boot app locally (from Zed or a terminal) — covers the Docker Postgres dependency, the bootRun launch, and how to drive the auth endpoints.
---

# Run jaram-be Locally

## Overview

jaram-be is a Spring Boot (Java 21, Gradle) app that needs a Postgres
database. To avoid clashing with any host Postgres on `:5432`, local runs
use a throwaway Docker Postgres on host port **5433**, and the app is
pointed at it via `DB_URL`/`DB_USER`/`DB_PASSWORD` env vars (see
`src/main/resources/application.yml`).

`scripts/dev-run.sh` automates the whole thing; Zed tasks wrap that script.

## Quick Start

**From Zed:** command palette → `task: spawn` → **"Backend: Run (Docker DB)"**.
(Other tasks: *Start DB*, *Stop DB*, *Tests* — defined in `.zed/tasks.json`.)

**From a terminal:**
```bash
./scripts/dev-run.sh          # ensure DB is up, then ./gradlew bootRun
```

App comes up at `http://localhost:8080`. Wait for the log line
`Started JaramBeApplication`.

## Script Commands

| Command | Does |
|---|---|
| `./scripts/dev-run.sh` (or `up`) | Start DB if needed, then run the app (foreground) |
| `./scripts/dev-run.sh db:up` | Start the Postgres container only |
| `./scripts/dev-run.sh db:down` | Stop and remove the container |
| `./scripts/dev-run.sh db:logs` | Tail Postgres logs |

DB schema is created automatically (`spring.jpa.hibernate.ddl-auto: update`).

## Smoke Test

```bash
# Signup (201)
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"name":"홍길동","studentId":"20231234","email":"test@hanyang.ac.kr","password":"abcd1234!"}'

# Login for an unapproved member -> 403 PENDING
curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"test@hanyang.ac.kr","password":"abcd1234!"}'
```

Auth routes live under `/api/auth` (`signup`, `login`,
`password/reset-request`, `password/reset`).

## Common Issues

| Symptom | Fix |
|---|---|
| `password authentication failed for "jaram"` | App hit host Postgres on :5432 instead of the container. Use the script (it sets `DB_URL` to :5433); don't run bare `./gradlew bootRun`. |
| Port 8080 already in use | A previous run is still alive: `pkill -f JaramBeApplication`. |
| `pg_isready ... timed out` | Docker not running, or image still pulling — check `docker ps` / `./scripts/dev-run.sh db:logs`. |

## Stopping

`Ctrl-C` the app, then `./scripts/dev-run.sh db:down` to remove the
Postgres container (its data is ephemeral).
