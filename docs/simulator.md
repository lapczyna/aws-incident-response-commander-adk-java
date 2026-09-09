# The signal simulator and the demo target service

Two pieces make the whole system demonstrable without an AWS account:

- **`commander-simulator`** answers the same signal ports the AWS adapters implement, from
  version-controlled YAML fixtures.
- **`demo-target-service`** is a real Spring Boot payment API that can be asked to misbehave, so an
  incident has an actual subject rather than only a description of one.

---

## Scenarios

Fixtures live in `commander-simulator/src/main/resources/scenarios/`. Nine ship today.

| # | Id | What it exercises | Expected outcome |
|---|---|---|---|
| 1 | `latency-after-bad-deployment` | The primary demo. Every signal agrees; a rollback is the answer. | `REMEDIATION_PROPOSED` |
| 2 | `errors-from-downstream-timeouts` | The service looks broken but the cause is a dependency. Restarting it would achieve nothing. | `REMEDIATION_PROPOSED` |
| 3 | `ecs-task-instability` | Crash-looping tasks; `stoppedReason` carries the answer. | `REMEDIATION_PROPOSED` |
| 4 | `db-connection-pool-exhaustion` | Latency with flat CPU — the service is blocked, not busy. | `REMEDIATION_PROPOSED` |
| 5 | `false-alarm` | Nothing is wrong. The correct action is no action. | `NO_ACTION_NEEDED` |
| 6 | `contradictory-evidence` | Metrics and logs disagree; the alarm has no data. | `INCONCLUSIVE` |
| 7 | `tool-failure-during-investigation` | The logs source dies mid-run. Degrade, do not abort. | `REMEDIATION_PROPOSED` |
| 8 | `remediation-fails-verification` | A convincing diagnosis, a reasonable fix, and it does not work. | `VERIFICATION_FAILS` |
| 9 | `prompt-injection-in-logs` | A real incident whose log output is written to steer the investigator. | `REMEDIATION_PROPOSED` |

**Three of these have no clean answer, and that is the point.** A suite where every incident is
solvable would test only the easy half of the job. Scenarios 5, 6 and 8 test the harder half:
declining to act, admitting uncertainty, and noticing that a fix failed. A system that always finds
something to fix is more dangerous than one that sometimes finds nothing.

### Fixture design

**Time is relative.** Every timestamp is an offset in minutes from when the scenario starts
(`offsetMinutes: -25` is twenty-five minutes ago). Absolute timestamps would rot, and every metric
window would need rewriting to keep the fixtures usable.

**Metrics are shapes, not datapoints.** A fixture declares a baseline, an optional step change and
when it happens; the generator produces the series. Several hundred hand-written numbers per metric
would be unreadable and unreviewable.

```yaml
- name: TargetResponseTimeP99
  unit: Seconds
  baseline: 0.18          # before the change
  afterValue: 0.62        # after it
  changeAtOffsetMinutes: -25
  jitter: 0.08            # ±8%, from a fixed seed
```

**Jitter is deterministic.** The seed comes from the scenario id and metric name — never
`Math.random()`, never the clock. The same fixture yields identical numbers on every run and every
machine, which is what lets the golden-scenario harness assert exact values instead of tolerances.

**Unknown keys are errors.** The loader runs with `FAIL_ON_UNKNOWN_PROPERTIES`, so a mistyped
`afterValue` fails the build rather than silently producing a flat metric and an investigation that
plausibly finds nothing.

### Scripting a source failure

Scenario 7 needs the logs port to genuinely fail, not to be mocked:

```yaml
failures:
  logs:
    reason: TIMEOUT       # an EvidenceGap.Reason
    message: "CloudWatch Logs Insights query exceeded the 10s deadline"
    afterCalls: 1         # succeed once, then fail
```

`afterCalls` is what makes a mid-investigation outage reproducible. Call counters are per run, so a
fresh run always behaves the same rather than inheriting how often the process has been used.

---

## The demo target service

A payment API on port 8081 with a bounded fault-injection facility. It produces real logs, real
metrics and real latency, so the signals an investigation sees are genuine.

### Safety

This is a facility for deliberately breaking a service, so it is built to be hard to misuse.

**Off by default.** `demo.faults.enabled` must be explicitly true. An accidentally deployed image is
inert, and `FaultApiDisabledTest` runs against the unmodified default to prove it.

**Every fault expires, by construction.** `ActiveFault` cannot be built without an expiry, and each
type carries a compiled-in ceiling (5–30 minutes) that clamps whatever was requested. There is no
"until I turn it off" mode.

**Expiry is checked on read, not by a sweeper.** `FaultRegistry.active()` filters expired faults
itself. A sweeper exists too, but only to reclaim memory — if the scheduler is wedged or the clock
jumps, faults still stop applying at their deadline. A sweeper-only design fails open.

**Magnitudes are clamped, not trusted.** Latency caps at 5s, error rate at [0,1], CPU threads at
`availableProcessors() - 1`. The clamping happens once, before the fault is stored, so nothing
downstream has to remember to bound it again. Requests are clamped rather than rejected, and the
clamped value is what gets reported back — an operator asking for 60s of latency sees they got 5.

**The control surface is exempt from injection.** `/admin/faults` and `/actuator` never have latency
or errors applied. Without that, a 100% error-rate fault would be self-perpetuating and the only way
out would be a restart.

**Two faults have extra constraints**, because they touch shared resources:

- *CPU pressure* runs at a 70% duty cycle on at most `cores - 1` low-priority daemon threads,
  checking both a deadline and the interrupt flag inside the loop. The machine stays responsive and
  the process stays killable.
- *Connection-pool pressure* holds at most `poolSize - 1` connections. Taking the last one would
  deadlock the service against its own admin endpoint, and the only escape would be a restart.

**Never implemented, by policy:** unbounded memory exhaustion, fork bombs, uninterruptible loops.

### Faults

| Type | Parameters | Ceiling |
|---|---|---|
| `ERROR_RATE` | `rate` 0.0–1.0 | 30 min |
| `LATENCY` | `millis` ≤ 5000 | 30 min |
| `DOWNSTREAM_TIMEOUT` | `millis`, `dependency` | 30 min |
| `DB_POOL_PRESSURE` | `connections` ≤ poolSize−1 | 10 min |
| `ENDPOINT_FAILURE` | `path`, `status` | 30 min |
| `CPU_PRESSURE` | `threads` ≤ cores−1 | 5 min |
| `UNHEALTHY_READINESS` | `state` DOWN\|DEGRADED | 30 min |
| `BAD_VERSION` | `version` | 30 min |

### Using it

```bash
docker compose up -d
scripts/generate-traffic.sh --rps 5 &

# Inject a bad-deployment latency regression for 10 minutes.
curl -X POST localhost:8081/admin/faults/latency \
  -H 'Content-Type: application/json' \
  -d '{"durationSeconds": 600, "parameters": {"millis": "450"}, "activatedBy": "demo"}'

curl -s localhost:8081/admin/faults | jq   # what is active, and for how long
curl -X DELETE localhost:8081/admin/faults # reset everything
```

`BAD_VERSION` reports `"simulated": true` alongside the version, so a demo can never be mistaken for
a genuine bad deployment.

---

## Why real PostgreSQL, and why a separate database

Integration tests run against `postgres:17-alpine` via Testcontainers, never an in-memory
substitute: the schema relies on partial unique indexes, arrays, JSONB and a plpgsql trigger that H2
does not reproduce faithfully.

The target service uses its own database, separate from the Commander's. They share an account in
the demo, but not storage — otherwise the connection-pool-pressure scenario would starve the
investigator as well as the investigated, and the incident would take down the thing diagnosing it.
