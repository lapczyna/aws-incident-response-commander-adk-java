# Durable state

Flyway owns the schema; nothing generates DDL. Migrations live in
`commander-persistence-postgres/src/main/resources/db/migration`.

## Migration phasing

Tables land with the code that reads and writes them, not ahead of it. Schema created before its
code is schema nobody validates.

| Migration | Contents | Phase |
|---|---|---|
| `V1__incident_lifecycle.sql` | Incidents, transitions, evidence, hypotheses, remediation, policy decisions, approvals, executed actions, verification, reports, audit, idempotency | 1 ✅ |
| `V2__adk_sessions.sql` | `adk_sessions`, `adk_events`, `adk_app_state`, `adk_user_state`, `adk_artifacts`, `adk_memory_entries` | 3 |
| `V3__agent_telemetry.sql` | `tool_calls`, `model_calls`, `specialist_outputs`, `workflow_stages`, `prompt_versions`, `scenario_runs` | 4 |

## V1 tables

| Table | Purpose |
|---|---|
| `actors` | Humans and system principals. Approval decisions reference it, so "who approved this" is answerable directly. |
| `incidents` | The aggregate root. Carries the `version` that drives optimistic locking **and** the approval fingerprint. |
| `incident_status_transitions` | Every lifecycle move, with actor and reason. |
| `evidence` | Observations, with an `untrusted` flag derived from the source. |
| `evidence_gaps` | Evidence that *should* exist but could not be obtained. |
| `hypotheses` | Keyed `(id, revision)` — a refinement is a new row, never an update. |
| `remediation_proposals` | Each proposal is FK-linked to the exact hypothesis revision that motivated it. |
| `policy_decisions` | Both evaluations per action: `GATE` and `EXECUTION`. |
| `approval_requests` | The durable approval, with fingerprint, expiry and the ADK function-call id to resume. |
| `approval_decisions` | One decision per approval, enforced by a unique constraint. |
| `idempotency_keys` | Exactly-once execution, keyed on the fingerprint. |
| `executed_actions` | What actually ran, including whether it was a dry run. |
| `verification_results` | Whether recovery was confirmed, with evidence references. |
| `reports` | Generated postmortems. |
| `audit_events` | Append-only, enforced by a trigger. |

## Four constraints that carry weight

Most constraints are ordinary validation. These four exist to make a specific failure impossible.

**`approval_one_pending_per_incident`** — a partial unique index allowing at most one `PENDING`
approval per incident. Without it, two proposals could race to be approved and both execute.

**`approval_decision_unique`** — one decision per approval. A second attempt is a replay, and it
fails at the database rather than being caught by application logic that might be bypassed.

**`idempotency_keys` primary key** — claiming the right to execute *is* the insert. PostgreSQL
decides the winner between concurrent callers, so exactly-once holds across instances without
distributed locking. `IdempotencyStoreIntegrationTest` races sixteen threads at one fingerprint and
asserts a single winner.

**`hypotheses_must_cite_evidence`** — `cardinality(supporting_evidence) > 0`, mirroring the domain
invariant. An unsupported hypothesis cannot be stored, so "every conclusion references evidence" is
enforced in two independent places.

## The audit log is append-only, with one declared exception

`audit_events` refuses `UPDATE` and `DELETE` through a trigger rather than a `GRANT`, so the
guarantee holds regardless of which role connects and can be tested directly.

Because incidents cascade into their audit entries, a strict trigger would also make incidents
undeletable and data retention impossible. Rather than weakening the rule, maintenance must announce
itself in the same transaction:

```sql
DO $$
BEGIN
    PERFORM set_config('app.audit_maintenance', 'on', true);
    DELETE FROM incidents WHERE received_at < now() - interval '90 days';
END
$$;
```

The setting is transaction-scoped, so it cannot leak onto a pooled connection and unlock a later
transaction — `AuditLogIntegrationTest` asserts exactly that. This is not a defence against someone
holding arbitrary SQL access; nothing at this layer could be. It is a defence against the
application's own ordinary code paths, including a future "cleanup" query added without thinking.

## Optimistic locking

`incidents.version` is the concurrency token. Updates are conditional:

```sql
UPDATE incidents SET ... WHERE id = :id AND version = :expectedVersion
```

Zero rows affected means someone else moved the incident first, and the write is rejected. This is
not only a concurrency nicety: because the version feeds the approval fingerprint, losing this race
is the system correctly refusing to act on a stale view of the incident.

## Testing

Integration tests run against real PostgreSQL via Testcontainers (`postgres:17-alpine`), never an
in-memory substitute. The schema uses partial unique indexes, arrays, JSONB and a plpgsql trigger,
none of which H2 reproduces faithfully — a test that passes against a fake database and fails
against the real one is worse than no test.
