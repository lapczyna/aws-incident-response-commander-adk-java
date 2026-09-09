# ADR-0005: Depending on ADK's deprecated `ResumabilityConfig`, with the blast radius contained

- **Status:** Accepted (with a scheduled review)
- **Date:** 2026-09-08
- **Phase:** 0

## Context

Durable, resumable human approval is the centrepiece of this project. In ADK Java the mechanism is:

1. a tool marked `LongRunningFunctionTool.create(instance, method, requireConfirmation = true)`
   emits an `adk_request_confirmation` function call and ends the invocation;
2. later, a `FunctionResponse` carrying a `ToolConfirmation` resumes the run;
3. `RequestConfirmationLlmRequestProcessor` and `WorkflowAgentResumption` route that confirmation
   back to the sub-agent that originally emitted the call.

Step 3 only happens when the invocation is marked resumable. The **only** way to set that flag is:

```java
App.builder().resumabilityConfig(ResumabilityConfig.builder().resumable(true).build())
```

which surfaces as `InvocationContext.isResumable()`.

`com.google.adk.apps.ResumabilityConfig` is annotated **`@Deprecated`**, as is
`App.Builder.resumabilityConfig(...)`. ADK 1.9.0 ships **no replacement**: `App` exposes no other
resumability setter, and `RunConfig` has no equivalent field. `App` itself carries
`@SuppressWarnings("deprecation") // Plumbs the deprecated ResumabilityConfig.`

So the feature is deprecated, still fully functional, still the only route, and clearly mid-migration
upstream.

## Options considered

1. **Use it, contained.** Accept the deprecation, pin ADK, isolate the call site.
2. **Reimplement resumption ourselves** — persist pending confirmations and hand-roll the replay of
   function responses into the right sub-agent. Rejected: it duplicates
   `WorkflowAgentResumption.resumeSubAgentIndex(...)` and `RequestConfirmationLlmRequestProcessor`,
   which are non-trivial and would drift from ADK's behaviour on every upgrade.
3. **Drop resumable approval**, approving out-of-band and re-running the investigation. Rejected:
   re-running costs tokens, produces a different investigation, and destroys the audit link between
   the evidence a human saw and the action they approved.

## Decision

Option 1. Every use is funnelled through a single factory method:

```java
// commander-adk: the ONLY place in this project that touches ResumabilityConfig.
@SuppressWarnings("deprecation") // ADK 1.9.0 offers no replacement; see ADR-0005.
static App resumableApp(String name, BaseAgent root, List<Plugin> plugins) { ... }
```

Supporting controls:

- ADK is pinned to `1.9.0` in the root POM and never auto-upgraded.
- The suppression is narrow and carries a comment pointing here, so it is discoverable.
- An ADK upgrade is a deliberate task whose checklist includes re-reading `apps/` for the
  replacement API. When one appears, this becomes a one-line change.

## Consequences

**Good**
- Real ADK resumption semantics, including correct sub-agent routing inside a `SequentialAgent`,
  for a few lines of code.
- The dependency is visible and documented rather than buried.

**Costs**
- A pre-2.0 dependency on a deprecated API. This is a genuine risk, not a formality: the feature may
  be renamed or restructured in any minor release.
- Deprecation warnings must stay suppressed at exactly one site; a second `@SuppressWarnings` for
  this reason anywhere else is a defect.

**Review trigger:** the next ADK minor upgrade, or the appearance of a resumability setter on `App`
or `RunConfig`.
