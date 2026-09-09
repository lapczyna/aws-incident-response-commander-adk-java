#!/usr/bin/env bash
# Generates steady traffic against the demo target service.
#
# Deliberately a script rather than a Java component: traffic has to come from outside the service
# under investigation, or the load generator's own latency becomes part of what is being measured.
#
# Usage:
#   scripts/generate-traffic.sh [--url URL] [--rps N] [--duration SECONDS]
set -euo pipefail

URL="http://localhost:8081"
RPS=5
DURATION=0          # 0 means run until interrupted

while [[ $# -gt 0 ]]; do
  case "$1" in
    --url)      URL="$2"; shift 2 ;;
    --rps)      RPS="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 1 ;;
  esac
done

if ! curl -sf "$URL/actuator/health" >/dev/null 2>&1; then
  echo "Target service is not reachable at $URL" >&2
  echo "Start it with: ./mvnw -pl demo-target-service spring-boot:run -Dspring-boot.run.profiles=demo" >&2
  exit 1
fi

echo "Generating ~${RPS} req/s against ${URL} (Ctrl-C to stop)"
interval=$(awk "BEGIN {printf \"%.3f\", 1/$RPS}")
started=$(date +%s)
sent=0
failed=0

# Report on exit however we got there, including Ctrl-C.
summary() {
  echo
  echo "sent=${sent} failed=${failed} over $(( $(date +%s) - started ))s"
}
trap summary EXIT

while true; do
  if [[ "$DURATION" -gt 0 && $(( $(date +%s) - started )) -ge "$DURATION" ]]; then
    break
  fi

  status=$(curl -s -o /dev/null -w '%{http_code}' -m 10 \
    -X POST "$URL/api/payments" \
    -H 'Content-Type: application/json' \
    -d "{\"orderId\":\"order-${sent}\",\"amountMinor\":$(( (RANDOM % 20000) + 500 )),\"currency\":\"EUR\"}" \
    || echo "000")

  sent=$((sent + 1))
  if [[ "$status" != "201" ]]; then
    failed=$((failed + 1))
    echo "  request ${sent}: HTTP ${status}"
  fi

  if (( sent % 50 == 0 )); then
    echo "  ${sent} sent, ${failed} failed"
  fi

  sleep "$interval"
done
