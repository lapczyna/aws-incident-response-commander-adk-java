# ADR-0006: Spring Data JDBC plus JdbcClient, not jOOQ and not JPA

- **Status:** Accepted
- **Date:** 2026-09-08
- **Phase:** 0

## Context

Around 26 tables of durable state: the incident aggregate and its transitions, ADK sessions and
events, evidence, hypotheses and revisions, tool and model calls, remediation proposals, policy
decisions, approvals, executed actions, verification results, reports, an append-only audit log and
idempotency keys.

Requirements that shape the choice: explicit persistence over clever mapping, optimistic locking,
transactional state transitions, an append-only audit table, and a **clean build from a fresh
checkout** with no database required to compile.

## Options considered

**JPA/Hibernate.** Rejected. Lazy loading, dirty checking and cascade semantics obscure exactly
what is written and when — unhelpful in a system whose value is an auditable record. The
specification also explicitly asks to avoid unnecessarily complex ORM mappings.

**jOOQ with generated classes.** Genuinely attractive: compile-time verification of every query
against the real schema. Rejected on build cost. Codegen needs a live schema at build time, via
either a Testcontainers-backed generation step or a committed schema dump. The first makes Docker a
prerequisite for `mvn compile` and slows every build; the second creates a file that silently drifts
from the Flyway migrations. Both work against "a new developer can run it from the README".

**Spring Data JDBC + `JdbcClient`.** Chosen.

## Decision

- **Flyway owns the schema.** It is the single source of truth; nothing generates DDL.
- **Spring Data JDBC repositories** for the straightforward aggregates. No lazy loading, no proxies:
  a save is one statement set at a known point.
- **`JdbcClient` with hand-written SQL** for everything shaped like a query rather than an aggregate
  — the append-only audit log, ordered ADK event streams, timeline projections and report queries.
- **Records as row types.** Immutable, and they read the way the SQL reads.
- `@Version` optimistic locking on `incidents`; a rejected update means a concurrent modification
  invalidated the approval.
- JSONB only where a payload is genuinely open-ended (raw tool arguments and results, ADK event
  content). Every field that is queried or indexed is a real column.

## Consequences

**Good**
- `git clone && ./mvnw verify` compiles with no database and no codegen step.
- Every statement that runs is visible in the source.
- Testcontainers integration tests validate the SQL against real PostgreSQL, which is where type and
  index mistakes actually surface.

**Costs**
- No compile-time checking of SQL. A column rename breaks at test time, not build time. Mitigated by
  requiring an integration test for every hand-written query, so the coverage that matters exists.
- More boilerplate than jOOQ for complex projections.

**Note.** Flyway resolves to **12.4.0**, managed by the Spring Boot 4.1.1 BOM, rather than the
13.5.0 currently on Maven Central. Matching Boot's tested combination is worth more here than a
major-version bump; see `docs/dependency-matrix.md`.
