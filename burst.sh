#!/bin/bash

# Usage:
# ./burst.sh <BASE_URL> [SHOW_ID] [SEAT] [USERS] [REQUESTS_PER_USER]

set -euo pipefail

BASE_URL="${1:-http://localhost:8081}"
SHOW_ID="${2:-2}"
SEAT="${3:-A6}"
USERS="${4:-100}"
REQ_PER_USER="${5:-10}"

TOTAL=$((USERS * REQ_PER_USER))

RESERVE_URL="$BASE_URL/shows/$SHOW_ID/reserve"

WORK_DIR=$(mktemp -d)
RESULTS="$WORK_DIR/results"

mkdir -p "$RESULTS"

cleanup() {
    rm -rf "$WORK_DIR"
}

trap cleanup EXIT

echo "=========================================="
echo "BURST TEST"
echo "=========================================="
echo "Target:       $BASE_URL"
echo "Show ID:      $SHOW_ID"
echo "Hot seat:     $SEAT"
echo "Users:        $USERS"
echo "Req/user:     $REQ_PER_USER"
echo "Total:        $TOTAL"
echo "=========================================="
echo ""

echo "Firing $TOTAL concurrent RESERVE requests..."
echo ""

START_TIME=$(date +%s)

# ============================================================
# RESERVE STORM
# ============================================================

for u in $(seq 1 "$USERS"); do

    for r in $(seq 1 "$REQ_PER_USER"); do

        IDEMPOTENCY_KEY="burst-u${u}-r${r}-$(date +%s%N)-$RANDOM"

        RESPONSE_FILE="$RESULTS/u${u}_r${r}.body"
        STATUS_FILE="$RESULTS/u${u}_r${r}.status"

        (
            curl -s \
                --connect-timeout 10 \
                --max-time 30 \
                -X POST "$RESERVE_URL" \
                -H "Content-Type: application/json" \
                -H "X-User-Id: $u" \
                -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
                -d "{\"seats\":[\"$SEAT\"]}" \
                -o "$RESPONSE_FILE" \
                -w "%{http_code}" \
                > "$STATUS_FILE" 2>/dev/null
        ) &

    done

done

wait

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

# ============================================================
# RESERVE RESULTS
# ============================================================

echo ""
echo "=========================================="
echo "RESERVE RESULTS"
echo "=========================================="

HTTP_201=0
HTTP_409=0
HTTP_5XX=0
OTHER=0

WINNING_RESPONSE=""

for status_file in "$RESULTS"/*.status; do

    [ -f "$status_file" ] || continue

    STATUS=$(cat "$status_file")
    BODY_FILE="${status_file%.status}.body"

    case "$STATUS" in

        201)
            HTTP_201=$((HTTP_201 + 1))

            if [ -z "$WINNING_RESPONSE" ]; then
                WINNING_RESPONSE="$BODY_FILE"
            fi
            ;;

        409)
            HTTP_409=$((HTTP_409 + 1))
            ;;

        5*)
            HTTP_5XX=$((HTTP_5XX + 1))
            ;;

        *)
            OTHER=$((OTHER + 1))
            ;;

    esac

done

COMPLETED=$((HTTP_201 + HTTP_409 + HTTP_5XX + OTHER))

echo "Completed:     $COMPLETED / $TOTAL"
echo "201 Created:   $HTTP_201  (successful HOLD)"
echo "409 Conflict:  $HTTP_409"
echo "5xx Error:     $HTTP_5XX"
echo "Other:         $OTHER"
echo "Duration:      ${DURATION}s"
echo ""

# ============================================================
# CHECK WINNER
# ============================================================

if [ "$HTTP_201" -eq 0 ]; then

    echo "ERROR: No successful reservation."
    echo "Cannot CONFIRM."

    exit 1

fi

if [ "$HTTP_201" -ne 1 ]; then

    echo "ERROR: Expected exactly one successful HOLD."
    echo "Actual: $HTTP_201"

    exit 1

fi

echo "=========================================="
echo "SUCCESSFUL RESERVE RESPONSE"
echo "=========================================="

cat "$WINNING_RESPONSE"

echo ""
echo ""

# ============================================================
# EXTRACT RESERVATION ID WITHOUT PYTHON
# ============================================================

RESERVATION_ID=$(grep -o '"reservationId"[[:space:]]*:[[:space:]]*"[^"]*"' "$WINNING_RESPONSE" \
    | head -1 \
    | sed 's/.*"reservationId"[[:space:]]*:[[:space:]]*"//' \
    | sed 's/".*//')

if [ -z "$RESERVATION_ID" ]; then

    echo "ERROR: reservationId could not be extracted."

    echo "Response was:"
    cat "$WINNING_RESPONSE"

    exit 1

fi

echo "=========================================="
echo "WINNING RESERVATION"
echo "=========================================="

echo "Reservation ID: $RESERVATION_ID"
echo ""

# ============================================================
# EXTRACT WINNING USER ID
# ============================================================

WINNING_USER_ID=$(grep -o '"userId"[[:space:]]*:[[:space:]]*[0-9]*' "$WINNING_RESPONSE" \
    | head -1 \
    | sed 's/.*:[[:space:]]*//')

if [ -z "$WINNING_USER_ID" ]; then
    echo "ERROR: Could not extract winning userId."
    exit 1
fi

echo "Winning user:   $WINNING_USER_ID"
echo ""

# ============================================================
# CONFIRM
# ============================================================

echo "=========================================="
echo "CONFIRMING WINNING RESERVATION"
echo "=========================================="

CONFIRM_URL="$BASE_URL/reservations/$RESERVATION_ID/confirm"

CONFIRM_RESPONSE="$WORK_DIR/confirm.body"

CONFIRM_HTTP=$(curl -s \
    --connect-timeout 10 \
    --max-time 30 \
    -X POST "$CONFIRM_URL" \
    -H "Content-Type: application/json" \
    -H "X-User-Id: $WINNING_USER_ID" \
    -H "Idempotency-Key: burst-confirm-$RESERVATION_ID" \
    -o "$CONFIRM_RESPONSE" \
    -w "%{http_code}")

echo "Confirm URL:"
echo "$CONFIRM_URL"
echo ""

echo "Confirm HTTP status: $CONFIRM_HTTP"

echo "Confirm response:"
cat "$CONFIRM_RESPONSE"

echo ""
echo ""

# ============================================================
# FINAL SHOW STATE
# ============================================================

echo "=========================================="
echo "FINAL SHOW STATE"
echo "=========================================="

STATE=$(curl -s "$BASE_URL/shows/$SHOW_ID")

echo "$STATE"
echo ""

# ============================================================
# RECONCILIATION
# ============================================================

AVAIL=$(echo "$STATE" \
    | grep -o '"availableSeats"[[:space:]]*:[[:space:]]*[0-9]*' \
    | head -1 \
    | sed 's/.*:[[:space:]]*//' || true)

HELD=$(echo "$STATE" \
    | grep -o '"heldSeats"[[:space:]]*:[[:space:]]*[0-9]*' \
    | head -1 \
    | sed 's/.*:[[:space:]]*//' || true)

CONF=$(echo "$STATE" \
    | grep -o '"confirmedSeats"[[:space:]]*:[[:space:]]*[0-9]*' \
    | head -1 \
    | sed 's/.*:[[:space:]]*//' || true)

TOTAL_SEATS=$(echo "$STATE" \
    | grep -o '"totalSeats"[[:space:]]*:[[:space:]]*[0-9]*' \
    | head -1 \
    | sed 's/.*:[[:space:]]*//' || true)

AVAIL=${AVAIL:-0}
HELD=${HELD:-0}
CONF=${CONF:-0}
TOTAL_SEATS=${TOTAL_SEATS:-0}

SUM=$((AVAIL + HELD + CONF))

echo "=========================================="
echo "FINAL RECONCILIATION"
echo "=========================================="

echo "Available:   $AVAIL"
echo "Held:        $HELD"
echo "Confirmed:   $CONF"
echo "Total:       $TOTAL_SEATS"
echo "Sum:         $SUM"

if [ "$SUM" -eq "$TOTAL_SEATS" ]; then
    echo "✓ RECONCILIATION OK"
else
    echo "✗ RECONCILIATION FAILED"
fi

echo ""

# ============================================================
# VALIDATION
# ============================================================

echo "=========================================="
echo "VALIDATION"
echo "=========================================="

PASS=true

if [ "$HTTP_5XX" -eq 0 ]; then
    echo "✓ Reserve 5xx = 0"
else
    echo "✗ Reserve 5xx = $HTTP_5XX"
    PASS=false
fi

if [ "$HTTP_201" -eq 1 ]; then
    echo "✓ Exactly 1 successful HOLD"
else
    echo "✗ Expected 1 successful HOLD, got $HTTP_201"
    PASS=false
fi

if [ "$CONFIRM_HTTP" -ge 200 ] && [ "$CONFIRM_HTTP" -lt 300 ]; then
    echo "✓ Winning reservation CONFIRMED"
else
    echo "✗ Confirmation failed: HTTP $CONFIRM_HTTP"
    PASS=false
fi

if [ "$SUM" -eq "$TOTAL_SEATS" ]; then
    echo "✓ Reconciliation OK"
else
    echo "✗ Reconciliation FAILED"
    PASS=false
fi

echo ""

if [ "$PASS" = true ]; then

    echo "=========================================="
    echo "✓ ALL BURST CHECKS PASSED"
    echo "=========================================="

    exit 0

else

    echo "=========================================="
    echo "✗ BURST TEST FAILED"
    echo "=========================================="

    exit 1

fi