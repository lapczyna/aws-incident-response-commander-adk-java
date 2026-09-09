# ADR-0004: Custom PostgreSQL implementations of ADK's session, memory and artifact SPIs

- **Status:** Accepted
- **Date:** 2026-09-08
- **Phase:** 0

## Context

The system must survive a restart while an incident waits for human approval, and resume the right
incident afterwards. That makes durable ADK session and event storage a hard requirement, not a
nice-to-have.

Python ADK ships a `DatabaseSessionService`. **Java ADK does not.**

## Findings

`com.google.adk.sessions` in 1.9.0 contains exactly three implementations:

- `InMemorySessionService` — lost on restart
- `VertexAiSessionService` — requires Vertex AI, and couples a portfolio project to GCP
- (out of tree) `com.google.adk:google-adk-firestore-session-service` — requires Firestore

The picture repeats elsewhere:

- `com.google.adk.memory` has only `InMemoryMemoryService`.
- `com.google.adk.artifacts` has `InMemoryArtifactService` and `GcsArtifactService` — no S3.

None of these fit a project whose durable store is PostgreSQL and whose cloud is AWS.

## Decision

Implement three adapters in `commander-persistence-postgres`, against ADK's published interfaces:

| Adapter | Implements | Backing tables |
|---|---|---|
| `PostgresSessionService` | `BaseSessionService` | `adk_sessions`, `adk_events`, `adk_app_state`, `adk_user_state` |
| `PostgresMemoryService` | `BaseMemoryService` | `adk_memory_entries` |
| `PostgresArtifactService` | `BaseArtifactService` | `adk_artifacts` |

`BaseSessionService` is RxJava3-shaped (`Single` / `Maybe` / `Completable`), so the adapters wrap
blocking JDBC on a bounded scheduler rather than pretending to be non-blocking.

The interface supplies a default `appendEvent` that applies `EventActions.stateDelta` to session
state, honouring the `app:` / `user:` / `temp:` prefixes and the `State.REMOVED` sentinel. The
Postgres implementation **mirrors that contract exactly** — `temp:` keys are never persisted,
`app:` and `user:` keys go to their own scoped tables — because divergence here would produce
state that silently differs between the in-memory and durable profiles.

`adk_events` is append-only and strictly ordered. Events are the audit trail of what the agents
actually did, so they are never updated or deleted outside the retention job.

This is the one documented exception to ADR-0001's rule that ADK types stay inside commander-adk:
implementing an ADK SPI necessarily means importing ADK. `ArchitectureRulesTest` encodes the
exception explicitly rather than loosening the rule.

## Consequences

**Good**
- Incidents survive `docker compose restart` mid-approval, which is the headline demo.
- No GCP dependency in an AWS project.
- Storing ADK events in the same database as the incident aggregate means one transaction boundary
  and one backup.

**Costs**
- Roughly 600 lines of adapter that upstream may eventually provide, and which must be re-verified
  on every ADK upgrade. Contract tests run the same suite against `InMemorySessionService` and
  `PostgresSessionService` to catch divergence.
- Blocking JDBC behind an Rx interface needs a dedicated scheduler; getting that wrong risks
  starving ADK's event stream.

## Addendum (Phase 3): one deliberate divergence from ADK's in-memory service

Implementing this turned up an inconsistency inside ADK itself, found by the contract test rather
than by reading.

`BaseSessionService`'s own default `appendEvent` explicitly skips keys prefixed `temp:` when
applying a state delta. But `InMemorySessionService` **overrides** that method, and its catch-all
branch writes every key that is not `app:` or `user:` into session state — `temp:` included — before
delegating to the default. The net effect is that ADK's in-memory service retains `temp:` state.

For an in-memory store this is nearly invisible: the session object *is* the store, nothing is ever
reloaded, and lingering scratch costs only memory. For a durable store it matters. Writing `temp:`
state to PostgreSQL would make per-invocation scratch outlive the invocation and reappear on the
next reload — exactly what the prefix exists to prevent.

`PostgresSessionService` therefore follows the **interface contract** rather than ADK's in-memory
behaviour. `SessionServiceContractTest` runs one suite against both implementations, and this single
case is excluded from the shared parameterisation with the reason stated inline; asserting a common
contract that ADK's own code does not honour would mean either failing on ADK's code or copying the
quirk into the durable path. A separate test pins ADK's actual behaviour, so if upstream changes it
we find out.

This is the value of testing a custom SPI implementation against the framework's own: the divergence
is now a documented decision rather than an undiscovered difference between the demo profile and
production.

---

**Explicitly rejected:** falling back to `InMemorySessionService` outside a named test profile.
Silent in-memory fallback would make the durability claim false in exactly the situation that
matters — an unplanned restart.
