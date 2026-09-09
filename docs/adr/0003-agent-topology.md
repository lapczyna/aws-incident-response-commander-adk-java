# ADR-0003: Deterministic workflow agents, with a non-LLM policy gate

- **Status:** Accepted
- **Date:** 2026-09-08
- **Phase:** 0

## Context

The specification names ten agents, among them an `IncidentCoordinatorAgent`, and asks for parallel
evidence gathering, a bounded hypothesis-refinement loop, and a deterministic risk/policy stage.

The obvious reading — make the coordinator an `LlmAgent` with the specialists as sub-agents and let
it delegate — puts a language model on the critical path of *stage sequencing*. It would then be
the model deciding whether verification runs after remediation, or whether the policy gate is
consulted at all. That directly contradicts the project's governing constraint.

## Verified ADK Java primitives

All confirmed present in 1.9.0 by reading the sources:

| Need | API |
|---|---|
| ordered stages | `SequentialAgent.builder()` |
| concurrent evidence | `ParallelAgent.builder()` |
| bounded refinement | `LoopAgent.builder().maxIterations(n)`; also exits on `EventActions.escalate` |
| loop exit from a critic | `ExitLoopTool.INSTANCE` |
| custom deterministic stage | `BaseAgent` is `public abstract` with `protected abstract Flowable<Event> runAsyncImpl(InvocationContext)` |
| typed hand-off between stages | `LlmAgent.Builder.outputKey(String)` writing into session state |
| per-agent step ceiling | `LlmAgent.Builder.maxSteps(int)` |
| per-run model-call ceiling | `RunConfig.maxLlmCalls` (defaults to 500) |

## Decision

The coordinator is a **root `SequentialAgent`** assembled by a Java factory, not an `LlmAgent`. The
risk and policy stage is a **`PolicyGateAgent extends BaseAgent`** containing no model call at all.

```
SequentialAgent  incident_commander
├─ LlmAgent      intake_classifier          outputKey "classification"
├─ ParallelAgent evidence_collection
│    ├─ LlmAgent metrics_investigator       outputKey "evidence_metrics"
│    ├─ LlmAgent logs_investigator          outputKey "evidence_logs"
│    ├─ LlmAgent ecs_investigator           outputKey "evidence_ecs"
│    └─ LlmAgent change_investigator        outputKey "evidence_changes"
├─ LoopAgent     hypothesis_refinement  maxIterations=3
│    ├─ LlmAgent hypothesis_agent           outputKey "hypothesis"
│    └─ LlmAgent hypothesis_critic          outputKey "critique"   [ExitLoopTool]
├─ LlmAgent      remediation_planner        outputKey "remediation_proposal"
├─ PolicyGateAgent                          <- plain Java, no LLM
├─ LlmAgent      remediation_executor       [LongRunningFunctionTool, requireConfirmation=true]
├─ LlmAgent      recovery_verifier          outputKey "verification"
└─ LlmAgent      incident_report_agent      outputKey "report"
```

The division of labour is explicit: the LLM may **classify, summarise, correlate, hypothesise and
write prose**. It may never decide *what happens next* or *whether an action is permitted*.

## Consequences

**Good**
- Stage order is a property of the code, provable by reading `IncidentAgentFactory`.
- The policy gate cannot be talked out of a decision, because there is nothing there to talk to.
- Every loop is bounded twice over: `maxIterations` and `escalate`.

**Costs**
- Less "agentic" than a self-directing coordinator, and it will not adapt its own workflow to a
  surprising incident shape. That is the intended trade: predictability over autonomy, in a system
  that can restart production services.
- `ParallelAgent` sub-agents communicate only through session state keys, so the contract between
  specialists and the hypothesis stage is a set of string keys. Typed records are parsed at the
  boundary in Phase 4 to keep that honest.

## Addendum (Phase 5): the policy gate guards its sub-agents rather than signalling a stop

Implementing the gate turned up something that would have been a real hole.

The obvious way for a stage to stop a pipeline is to emit an event with
`EventActions.endInvocation(true)`. It reads like the right mechanism and it does nothing here.
`SequentialAgent.runAsyncImpl` never consults that flag between sub-agents, and
`InvocationContext.setEndInvocation` only mutates the child context the gate itself was handed —
each sibling builds its own child from the *unchanged* parent. A denial expressed either way is
silently ignored, and the executor runs anyway. The failure is invisible: the gate logs a refusal,
the audit trail records a denial, and the action still happens.

The fix is to stop signalling and start structuring. The stages a denial must prevent are the
gate's **sub-agents**, not its siblings:

```java
new PolicyGateAgent(policyEngine, targetTags, List.of(remediationExecutor))
```

On ALLOW the gate runs what it guards; on DENY it returns the decision event and nothing else.
"Nothing runs unless policy allowed it" is then a property of the object graph, not of a signal that
has to be honoured by code this project does not own. `PolicyGateTest` asserts it by placing a
marker stage inside the gate and checking it never ran.

This is a good illustration of why the compatibility work in Phase 0 was worth doing but not
sufficient: reading the source told us `endInvocation` exists; only building on it revealed which
agent types actually honour it.

---

**Deviation from the specification.** The spec listed `IncidentCoordinatorAgent` among the agents.
It exists here as a composed `SequentialAgent` plus the Java factory that builds it, rather than as
an `LlmAgent`. The semantics the spec asked for are preserved; the LLM's authority over sequencing
is not.
