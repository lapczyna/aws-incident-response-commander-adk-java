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

**Rejected:** trusting the model to re-state the approved action at execution time. That hands the
authority decision back to the component least able to be held accountable for it.
