# ADR-0007: Approval bound to a canonical action fingerprint, re-checked at execution

- **Status:** Accepted
- **Date:** 2026-09-08
- **Phase:** 0

## Context

Every state-changing remediation needs durable human approval. The threats are concrete:

- **Approval replay** — the same approval used twice to execute twice.
- **Stale approval** — a human approves "roll back to task definition 41", but by execution time the
  incident has moved on and the proposal now means something different.
- **Confused deputy** — the approval carries authority, and the model, which authored the proposal,
  gets to decide what the approval applies to.
- **Restart** — the process dies while awaiting approval and must resume the correct incident.

An approval that binds only to an approval *identifier* defends against none of the first three.

## Decision

An approval binds to a **canonical action fingerprint**, not to an id:

```
fingerprint = SHA-256( actionType | canonical(sortedArgs) | targetResourceArn
                       | incidentId | incidentVersion )
```

`incidentVersion` is the optimistic-locking version of the incident aggregate, so **any material
change to the incident invalidates every outstanding approval for it**.

The flow:

1. The remediation tool is a `LongRunningFunctionTool` with `requireConfirmation = true`. ADK emits
   an `adk_request_confirmation` function call and ends the invocation.
2. An `ApprovalRequest` row is persisted with the fingerprint, the action preview, resource ids,
   expected impact, risk classification, evidence references and an expiry. The incident moves to
   `AWAITING_APPROVAL`. **The JVM may now be killed safely.**
3. An approver calls the approve endpoint. Java **re-derives** the fingerprint from current incident
   state and compares. A mismatch is `409 APPROVAL_STALE` — never an execution.
4. Resumption replays a `FunctionResponse` named `adk_request_confirmation` carrying
   `ToolConfirmation.confirmed(true)`, with the original function-call id, into the loaded session.
5. **The tool body re-checks the policy engine and the idempotency table before acting.**

Step 5 is the important one. Approval is **necessary but never sufficient**. The deterministic
policy engine runs again at execution time, so a human cannot approve their way past the resource
allowlist, the environment check or the action allowlist. Execution is keyed on the fingerprint in
`idempotency_keys`; a replay returns the stored prior result and performs no second action.

Additional controls: approvals expire; the approver identity is recorded and must differ from the
actor who opened the incident; every decision is appended to the audit log.

## Consequences

**Good**
- Replay, staleness and confused-deputy attacks are structurally prevented, not merely tested for.
- Restart-during-approval works, because all the state lives in PostgreSQL rather than in memory.
- Defence in depth: executing a wrong action would require defeating the policy engine twice and
  forging a SHA-256 fingerprint over state the attacker does not control.

**Costs**
- Fingerprint canonicalisation must be exactly stable — argument ordering, number formatting and
  null handling all matter. It gets its own property-based test.
- A legitimate change to the incident invalidates a pending approval and forces re-approval. That is
  the intended behaviour, and the UI must explain it clearly or it will read as a bug.

## Addendum (Phase 6): what building it actually proved, and one test that proved nothing

The mechanism works. `LongRunningFunctionTool` with `requireConfirmation` pauses the invocation,
`PostgresSessionService` persists everything needed to reconstruct it, a brand-new `Runner` sharing
only the database resumes it, and the action executes exactly once. That is ADR-0004 and ADR-0005
both paying for themselves.

Two things are worth recording from getting there.

**The failure mode of a durable session service is silence.** ADK's `InMemorySessionService` keeps a
*reference* to each event; a durable one stores a *snapshot*. Any field that does not survive
serialisation is therefore invisible to every in-memory test and missing in production. The specific
consequence here would be an approved action that never runs while the audit trail records it as
granted — the worst kind of failure, because every observable signal says it worked.
`EventSerialisationFidelityTest` now pins the fields resumption depends on (function-call ids,
`requestedToolConfirmations`, `longRunningToolIds`, author) in a test that needs no database and runs
in milliseconds.

**A test that counts the wrong thing is worse than no test.** The first version of the restart test
asserted an execution counter that lived inside the injected executor — while running in dry-run
mode, where `RemediationTool` deliberately never calls the executor. It reported zero executions and
looked like a broken resume for as long as it took to dump the stored events and find
`"status": "executed"` sitting in the trail. The system was right the whole time.

The fix was not to change the assertion but to make the semantics explicit: `engine(boolean dryRun)`
forces every test to state which mode it exercises, tests that assert an action ran use
`dryRun = false`, and the dry-run test now asserts the executor was **not** reached. Had this been
left as it was, it would have passed for the wrong reason forever and masked a genuine regression in
exactly the capability the project is built around.

---

**Rejected:** trusting the model to re-state the approved action at execution time. That hands the
authority decision back to the component least able to be held accountable for it.
