#!/usr/bin/env bash
# Drives one incident end to end against a running Commander.
#
# Raise, investigate, read the proposal, decide it as a second person, read the postmortem. Every
# step is an HTTP call anyone can make, printed before it is made, so the script is a readable
# transcript of the API rather than a magic button.
#
# It is deliberately not a test. The tests assert; this narrates — which is the thing a test cannot
# do and the thing a demonstration needs.
#
# Usage:
#   scripts/demo.sh [--url URL] [--scenario ID] [--decision approve|reject] [--password PASSWORD]
#
# Prerequisites: the stack is up (`docker compose up -d`) and its policy permits the action. With
# the shipped defaults nothing is allowlisted, so the investigation will close with a refusal —
# which is a correct outcome and a dull demo. To see the approval gate, start the Commander with:
#
#   COMMANDER_ACTIONS_ENABLED=true \
#   COMMANDER_ALLOWED_ACTIONS=ROLLBACK_DEPLOYMENT \
#   COMMANDER_ALLOWED_RESOURCE_ARNS=arn:aws:ecs:eu-west-1:123456789012:service/commander/checkout \
#   docker compose up -d
#
# Dry run stays on throughout. Nothing is executed against anything.
set -euo pipefail

URL="http://localhost:8080"
SCENARIO="latency-after-bad-deployment"
DECISION="approve"
PASSWORD="${COMMANDER_DEMO_PASSWORD:-commander}"

# Two identities, because one cannot do this alone. The responder raises the incident and the
# approver decides it: an approver who raised it themselves is refused, which is the separation-of-
# duties rule and the most interesting refusal in the system.
RESPONDER="responder"
APPROVER="approver"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --url)      URL="$2"; shift 2 ;;
    --scenario) SCENARIO="$2"; shift 2 ;;
    --decision) DECISION="$2"; shift 2 ;;
    --password) PASSWORD="$2"; shift 2 ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 1 ;;
  esac
done

case "$DECISION" in
  approve|reject) ;;
  *) echo "--decision must be approve or reject" >&2; exit 1 ;;
esac

command -v jq >/dev/null 2>&1 || { echo "jq is required" >&2; exit 1; }

step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
note() { printf '   %s\n' "$1"; }

# Prints the call before making it, so the transcript shows what produced each answer.
#
# -f, so a non-2xx is a failure rather than an error document assigned to a variable and parsed as
# though it were the answer. The steps that expect a refusal use their own curl and read the status.
api() {
  local user="$1" method="$2" path="$3"; shift 3
  printf '   \033[2m%s %s   (as %s)\033[0m\n' "$method" "$path" "$user" >&2
  curl -sSf -u "${user}:${PASSWORD}" -X "$method" "${URL}${path}" \
    -H 'Content-Type: application/json' -H 'Accept: application/json' "$@"
}

# ----------------------------------------------------------------------------------------------

step "Checking the Commander is up"
if ! curl -sf "${URL}/actuator/health" >/dev/null; then
  echo "Not reachable at ${URL}. Start it with: docker compose up -d" >&2
  exit 1
fi
note "$(curl -sS "${URL}/actuator/health" | jq -r '.status')"

step "Starting scenario: ${SCENARIO}"
# Only present under the simulator profile. Against live AWS there is no scenario to choose, so a
# failure here is informational rather than fatal.
if scenario=$(api "$APPROVER" POST "/api/demo/scenarios/${SCENARIO}/start" 2>/dev/null); then
  note "$(jq -r '.title' <<<"$scenario")"
  note "expected outcome: $(jq -r '.expectedOutcome' <<<"$scenario")"
else
  note "No scenario endpoint — this Commander is reading live AWS rather than the simulator."
fi

step "Raising the incident"
incident=$(api "$RESPONDER" POST /api/incidents -d '{
  "title": "Checkout p99 latency tripled",
  "serviceName": "checkout",
  "severity": "SEV2",
  "metricName": "TargetResponseTimeP99",
  "observedValue": 1450.0,
  "recoveryThreshold": 500.0
}')
incident_id=$(jq -r '.id' <<<"$incident")
note "id=${incident_id} status=$(jq -r '.status' <<<"$incident")"
note "The metric and its threshold were captured now, before any model saw anything."

step "Investigating"
note "Four specialists in parallel, then hypothesis, critique, refine, propose, gate."
note "This holds the connection until it finishes."
investigated=$(api "$RESPONDER" POST \
  "/api/incidents/${incident_id}/investigate?metricName=TargetResponseTimeP99&observedValue=1450.0&recoveryThreshold=500.0")
status=$(jq -r '.status' <<<"$investigated")
note "status=${status}"

if [[ "$status" != "AWAITING_APPROVAL" ]]; then
  step "No human decision was needed"
  note "$(jq -r '.closingNote // .summary // "no note recorded"' <<<"$investigated")"
  note ""
  note "If that says the policy refused, this deployment allowlists nothing — which is the"
  note "shipped default. The header of this script says how to enable one action."
  exit 0
fi

step "Waiting for a human"
approval=$(api "$APPROVER" GET /api/approvals | jq -c --arg id "$incident_id" \
  '[.[] | select(.incidentId == $id)][0]')
approval_id=$(jq -r '.id' <<<"$approval")

printf '\n'
jq -r '
  "   action:      \(.action)   risk: \(.risk)",
  "   target:      \(.targetArn)",
  "   fingerprint: \(.fingerprint)   binds to incident version \(.incidentVersion)",
  "   rationale:   \(.rationale)",
  "   impact:      \(.expectedImpact)",
  "   expires:     \(.expiresAt)"' <<<"$approval"

step "Demonstrating separation of duties"
note "The responder who raised this incident tries to approve it."
refused=$(curl -sS -o /dev/null -w '%{http_code}' -u "${RESPONDER}:${PASSWORD}" \
  -X POST "${URL}/api/approvals/${approval_id}/approve" \
  -H 'Content-Type: application/json' -d '{"comment":"I am sure"}')
note "HTTP ${refused} — an investigator cannot approve, and neither could the person who opened it."

step "Deciding: ${DECISION}"
decided=$(api "$APPROVER" POST "/api/approvals/${approval_id}/${DECISION}" \
  -d "{\"comment\":\"Demonstration decision made by ${APPROVER}.\"}")
note "status=$(jq -r '.status' <<<"$decided")"
note "$(jq -r '.closingNote // "no closing note"' <<<"$decided")"

step "The postmortem"
if report=$(curl -sSf -u "${APPROVER}:${PASSWORD}" \
      -H 'Accept: text/markdown' "${URL}/api/incidents/${incident_id}/report"); then
  printf '\n%s\n' "$report"
else
  note "No report yet. One is written when the incident closes."
fi

step "Done"
note "Console: ${URL}/console    OpenAPI: ${URL}/swagger-ui.html"
note "Incident: ${URL}/console/incidents/${incident_id}"
