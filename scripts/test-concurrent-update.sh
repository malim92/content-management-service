#!/usr/bin/env bash
#
# Manual test: concurrent update handling (optimistic locking).
#
# Fires N parallel PUT requests with the SAME expectedVersion at one content.
# Expected outcome: exactly ONE returns 200, all others return 409 CONCURRENT_UPDATE.
#
# Usage: ./scripts/test-concurrent-update.sh [base-url]
#   e.g. ./scripts/test-concurrent-update.sh http://localhost:8080
#
# Requires: curl. Optional: jq (falls back to grep/sed for JSON parsing).

set -euo pipefail

BASE="${1:-http://localhost:8080}"
API="$BASE/api/v1/cms/contents"
ATTACKERS=10

extract() { # $1=json $2=jsonpath-like simple key
    echo "$1" | grep -o "\"$2\":[^,\"}]*" | head -1 | cut -d: -f2
}

echo "==> 1. Creating a fresh content item..."
CREATE_RESP=$(curl -sS -X POST "$API" -H 'Content-Type: application/json' -d '{
  "key": "concurrency.manual.test",
  "title": "Base",
  "content": "Base body",
  "language": "en",
  "status": "PUBLISHED"
}')
ID=$(extract "$CREATE_RESP" id)
VERSION=$(extract "$CREATE_RESP" version)
echo "    created id=$ID version=$VERSION"

echo "==> 2. Reading current version (GET)..."
GET_RESP=$(curl -sS "$API/$ID")
VERSION=$(extract "$GET_RESP" version)
echo "    current version=$VERSION — all $ATTACKERS attackers will use this version"

echo "==> 3. Firing $ATTACKERS parallel PUTs with expectedVersion=$VERSION ..."
PIDS=()
for i in $(seq 1 "$ATTACKERS"); do
    (
        CODE=$(curl -sS -o "attack_$i.json" -w '%{http_code}' -X PUT "$API/$ID" \
            -H 'Content-Type: application/json' -d "{
              \"title\": \"Attacker $i wins\",
              \"content\": \"parallel update attempt $i\",
              \"status\": \"PUBLISHED\",
              \"expectedVersion\": $VERSION
            }")
        echo "$CODE" > "attack_$i.code"
    ) &
    PIDS+=($!)
done
wait

echo "==> 4. Results:"
WINNERS=0
for i in $(seq 1 "$ATTACKERS"); do
    CODE=$(cat "attack_$i.code")
    if [ "$CODE" = "200" ]; then
        WINNERS=$((WINNERS + 1))
        WINNER_TITLE=$(extract "$(cat "attack_$i.json")" title)
        echo "    attacker $i -> 200 OK  (title: $WINNER_TITLE)"
    elif [ "$CODE" = "409" ]; then
        echo "    attacker $i -> 409 CONCURRENT_UPDATE (rejected, as required)"
    else
        echo "    attacker $i -> $CODE (UNEXPECTED!)" >&2
    fi
    rm -f "attack_$i.json" "attack_$i.code"
done

echo "==> 5. Verifying stored value..."
FINAL=$(curl -sS "$API/$ID")
echo "    stored: $(extract "$FINAL" title) (version $(extract "$FINAL" version))"

echo
if [ "$WINNERS" -eq 1 ]; then
    echo "PASS: exactly one update won the race; no silent overwrites."
    exit 0
else
    echo "FAIL: expected exactly 1 winner, got $WINNERS"
    exit 1
fi
