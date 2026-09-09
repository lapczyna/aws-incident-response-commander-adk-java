# Observability

Three questions this has to answer, in the order an operator asks them: **is it spending money**,
**is it being refused**, and **is it telling the truth about recoveries**. Everything below exists to
answer one of those.

## Correlation

Every log line carries the ids needed to reconstruct a run, as structured fields rather than as text
inside a message. `logback-spring.xml` names them explicitly instead of dumping the whole MDC, so
adding a field is a decision and a stray entry left by some library does not silently become part of
the log schema.

| Field | Set by |
|---|---|
| `incidentId` | the incident use case |
| `invocationId`, `agent`, `tool` | `InvestigationBudgetPlugin` |
| `approvalId` | the approval path |
| `traceId`, `spanId` | Micrometer Tracing |

### MDC does not survive RxJava on its own

ADK returns `Flowable<Event>` and runs work on pooled schedulers. MDC is thread-local, so a value set
on the calling thread is simply absent on the thread that emits events.

That failure is quiet in the worst way. It is not an error; it is an empty `incidentId` on exactly
the operations worth correlating, appearing only under concurrency, and often vanishing when someone
adds logging to investigate. Worse, pooled threads are reused, so a value left behind by one
invocation attaches itself to whatever runs next — a log line carrying a confidently *wrong* incident
id, which is more damaging than a blank one because a blank field is noticed and a wrong one is
trusted.

`MdcPropagation.withContext(flowable)` captures the context at assembly time and re-installs it
around every signal delivered downstream, restoring the previous context afterwards rather than
clearing it.

Two things worth knowing about it:

- **It applies downstream, not upstream.** Operators placed after the call see the context; operators
  before it do not. That is the useful direction — the interesting code is the consumer reading
  events out of a runner.
- **The first attempt did nothing.** Wrapping the stream in `Flowable.using` looked right and
  silently failed, because the resource is created on the subscribing thread while the work happens
  on a scheduler worker. The tests observe the context on the emitting thread specifically so that a
  no-op implementation cannot pass.

It is explicit rather than a global `RxJavaPlugins` assembly hook, because a global hook would cover
every stream in the process — ADK's internals included — with cost and failure modes invisible at the
call sites relying on it.

## Metrics

Exposed at `/actuator/prometheus`. Names are constants in `TelemetryPlugin` and
`MicrometerSafetyMetrics`, and the Grafana dashboard in [`dashboards/`](dashboards/) points at those
names — a rename breaks a panel silently, showing no data rather than an error, so the names live in
one place.

| Metric | Tags | What a bad value means |
|---|---|---|
| `commander.model.cost.usd` | `model` | Approaching the monthly limit. At the limit, calls stop. |
| `commander.model.refused` | `agent` | The budget is exhausted; investigations now run without a model |
| `commander.model.tokens` | `agent`, `direction` | Input rising with flat output usually means evidence stopped being truncated |
| `commander.model.calls.untracked` | — | On a paid profile, spend is going unmeasured |
| `commander.policy.decisions` | `decision` | Sustained denials: either a misconfigured allowlist or something proposing what it should not |
| `commander.approval.decisions` | `outcome` | `REFUSED_NOTAUTHORISED` is someone attempting a decision they cannot make |
| `commander.approval.wait` | — | A p95 near the expiry window means decisions arrive too late to use |
| `commander.verification.outcomes` | `outcome` | Rising `INDETERMINATE` is a monitoring problem, not a remediation one |
| `commander.tool.calls` | `tool`, `status` | `unavailable` is a recorded evidence gap; conclusions in that window are weaker than they look |
| `commander.agent.latency` | `agent` | Parallel collection approaching the *sum* of its specialists means they stopped running concurrently |

**Tag cardinality is bounded by construction.** Every tag value is an enumeration name, an agent
name, or a fixed refusal reason — never a resource id, an incident id, or anything else that grows
with usage. An unbounded tag turns a metrics backend into an outage.

**Metrics never affect a decision.** `SafetyMetrics` is a port whose every method is a no-op by
default, and the safety-critical services take it through an overloaded constructor. A service built
without a registry behaves identically to one with it, so a metrics backend being unavailable cannot
change whether an action is authorised.

## What is deliberately not recorded

- **No prompts or model responses.** Both are untrusted operational data. A log pipeline forwards,
  indexes and renders content in tools that were not designed with hostile input in mind, and an
  injected log line should not get a second delivery route.
- **No chain of thought.** Concise model-produced rationales, evidence references and decisions are
  persisted; the reasoning transcript is not.
- **No AWS error messages.** They carry ARNs, account ids and request context. The error *code* is
  recorded, because that is what an investigator needs and it is safe.
- **`com.google.adk` stays at INFO.** Raising it to DEBUG logs prompts and responses, which
  reintroduces the first item on this list.

## Tracing

OpenTelemetry via Micrometer Tracing, exported over OTLP when
`OTEL_EXPORTER_OTLP_ENDPOINT` is set and disabled otherwise.

Sampling is **1.0**. Investigations are rare and expensive, each one is worth a complete trace, and
sampling would drop exactly the run someone is trying to understand. This is a defensible default
here precisely because the volume is low; it would not be for a high-traffic service.

## Health

`/actuator/health` with liveness and readiness probes enabled, and details shown only to authorized
callers. The exposed endpoint set is `health,info,metrics,prometheus` rather than `*`: an incident
tool holds internals worth reading, and the set that is safe to serve is smaller than the set that is
useful in a shell.
