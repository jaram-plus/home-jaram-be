#!/usr/bin/env bash
set -euo pipefail

# Sync the OpenAPI contract into the test resources.
#
# The source of truth is docs/api/openapi.yaml — a symlink into the FE repo
# (home-jaram-fe). FE authors the contract there. The backend's contract
# tests (AuthContractTest etc., via swagger-request-validator) load a *copy*
# at src/test/resources/openapi/openapi.yaml, because a symlink outside the
# build tree isn't reliably packaged onto the test classpath.
#
# Run this whenever FE has changed the contract, before implementing against
# it or running contract tests, so the copy matches the source of truth.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/docs/api/openapi.yaml"          # symlink -> FE repo (resolved by cp)
DEST="$ROOT/src/test/resources/openapi/openapi.yaml"

if [[ ! -e "$SRC" ]]; then
  echo "error: $SRC does not resolve. Is the FE repo (home-jaram-fe) checked out as a sibling?" >&2
  exit 1
fi

if diff -q "$SRC" "$DEST" >/dev/null 2>&1; then
  echo "Already in sync: $DEST"
  exit 0
fi

cp "$SRC" "$DEST"
echo "Synced contract -> $DEST"
echo "Review the diff (git diff src/test/resources/openapi/openapi.yaml) and re-run contract tests:"
echo "  ./gradlew test --tests '*ContractTest'"
