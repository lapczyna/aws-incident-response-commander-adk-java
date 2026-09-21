# Runbook

How to operate this thing: what the console shows, what each decision commits you to, and what to
do when it behaves in a way that looks wrong but is not.

It assumes the stack is running. For getting it running, see the README's quick start; for AWS, see
[deployment.md](deployment.md).

---

## The five minute version

```bash
docker compose up -d
scripts/demo.sh                          # one incident, end to end, narrated
open http://localhost:8080/console       # sign in as approver / commander
```

With the shipped configuration the demo will investigate and then refuse to act, because nothing is
allowlisted. That is the system working. To see the approval gate, start it with one action
permitted against one resource:

```bash
COMMANDER_ACTIONS_ENABLED=true \
COMMANDER_ALLOWED_ACTIONS=ROLLBACK_DEPLOYMENT \
COMMANDER_ALLOWED_RESOURCE_ARNS=arn:aws:ecs:eu-west-1:123456789012:service/commander/checkout \
docker compose up -d
```

Dry run stays on. Nothing is executed against anything.

---

## Who can do what

| Identity | Raise | Investigate | Approve or reject | Switch scenario |
|---|---|---|---|---|
| `responder` | yes | yes | no | no |
| `approver` | yes | yes | yes — but never on an incident they raised | yes |
| `viewer` | no | no | no | no |

The password is `commander` locally, and a generated secret on AWS — `terraform output
console_password_secret_arn`, then `aws secretsmanager get-secret-value`.

Under the `oidc` profile none of these exist. Roles come from the token's claims, a token with no
recognised role authenticates as a viewer, and there is no form login at all.

---

## The console

One page, at `/console`. Two queues, refreshed every three seconds.

**Waiting for a human** comes first because it is the only thing on the page that blocks something.
Each entry shows the action, its risk, the exact target ARN, the rationale, the expected impact, the
incident version the approval binds to, and the first eight characters of the fingerprint.

**Incidents in flight** is everything still open. A row shaded amber is waiting on a person.

Clicking an incident opens its own page: the full proposal, the evidence it cites, and — once the
incident closes — the postmortem, rendered verbatim as it was stored.

### The two numbers on an approval

**Incident version.** An approval binds to the incident as it stood when the proposal was made. If
the incident moves on — another investigation, a status change — the fingerprint no longer matches
and the approval is refused as stale. This is not a bug and not a race; it is the mechanism that
stops an authorisation being redeemed against a different situation than the one it was granted for.

**Fingerprint.** A digest over the action, its arguments, the target ARN, the incident id and that
version. It is shown abbreviated because it is for recognising a decision in an audit log, not for
verifying one by eye.

---

## Deciding

**Approve** authorises one action, once, against one target, as the incident stands right now. It
does not turn anything on: if the deployment's policy does not permit the action, approving it
changes nothing, because the policy engine runs again inside the tool at execution time. Approval is
necessary and never sufficient.

**Reject** closes the incident as `REJECTED` and executes nothing. It is a distinct outcome from
`RESOLVED`, deliberately — a person stopping a change is not the same as the system finding nothing
to do, and the postmortem says which happened.

Write the comment. It is recorded against you in the audit trail, and it is what the next person
reading this incident has to go on.

---

## When it looks broken and is not

**"The investigation refused to act."**
Check the closing note. With the shipped defaults no action type is permitted and no resource is
allowlisted, so every proposal is refused — by four independent rules at once. `docker compose logs
commander | grep "Policy:"` prints the resolved posture at start-up.

**"Approving returned 403."**
Either you are not an approver, or you raised this incident. An approver cannot authorise acting on
an incident they opened. Use the other identity.

**"Approving returned 409 — stale."**
The incident moved after the proposal was made. Investigate again; the new proposal binds to the new
version.

**"Approving returned 409 — expired."**
Approvals have a TTL, one hour by default (`COMMANDER_APPROVAL_TTL`). An approval with no deadline
is one that can be redeemed against a system nobody remembers.

**"Recovery came back INDETERMINATE."**
The metric could not be measured, so the verdict is "unknown" rather than "worked". An incident
raised without `metricName`, `observedValue` and `recoveryThreshold` always verifies this way: those
are captured when the incident is raised, before any model sees anything, precisely so that the
thing being judged cannot choose its own pass mark.

**"The investigation stopped early."**
It is bounded: 16 tool calls and five minutes per invocation, then it reports what it has.
`COMMANDER_MAX_TOOL_CALLS` and `COMMANDER_INVESTIGATION_DEADLINE`.

**"Everything fails with a cost error."**
`CostGuard` fails closed at the configured monthly model budget. That bounds LLM spend only; it says
nothing about what the AWS infrastructure costs.

**"The console asks for a password in a browser dialog."**
That is the Basic-auth challenge, which should only reach API clients. If a browser sees it, the
application is running without an identity profile — check `SPRING_PROFILES_ACTIVE` includes
`local-identity` or `oidc`.

---

## Restarting mid-approval

The interesting durability property, and worth demonstrating once:

```bash
scripts/demo.sh &                        # run until it is waiting for a human
docker compose restart commander
curl -su approver:commander localhost:8080/api/approvals
```

The pending approval is still there, and approving it still resumes the paused agent invocation.
Sessions live in PostgreSQL, keyed by incident id, so the state survives the process. Nothing is
held in memory that would be lost.

---

## Switching scenarios

Nine fixtures ship with the build. The one worth showing is not the one that works:

```bash
curl -su approver:commander localhost:8080/api/demo/scenarios | jq -r '.[] | "\(.id)\t\(.expectedOutcome)"'
curl -su approver:commander -X POST localhost:8080/api/demo/scenarios/<id>/start
```

`VERIFICATION_FAILS` is the one to pick. It demonstrates the system applying a remediation,
measuring the result, and reporting that it did not work — which is the behaviour that distinguishes
arithmetic from asking a model whether its own fix succeeded.

An investigation already running keeps the scenario it started with. Switching only affects the
next one.

---

## Turning it off

```bash
docker compose down -v                   # local: containers and the volume
```

On AWS, the teardown is not optional and has its own checklist —
[deployment.md](deployment.md#teardown).
