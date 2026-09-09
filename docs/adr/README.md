# Architecture Decision Records

Each record captures a decision that was genuinely contested, the options weighed, and the cost
accepted. Records are immutable once accepted; a reversal is a new ADR that supersedes the old one.

| # | Decision | Status | Why it mattered |
|---|---|---|---|
| [0001](0001-hexagonal-architecture-and-module-boundaries.md) | Hexagonal architecture, framework-free domain, rules enforced by ArchUnit | Accepted | Safety decisions must be testable without a container |
| [0002](0002-model-provider-strategy.md) | Provider-independent `BaseLlm` abstraction; Gemini default, fake model in CI | Accepted | CI must run with no API key; providers must be swappable |
| [0003](0003-agent-topology.md) | Deterministic workflow agents; coordinator is a `SequentialAgent`, policy gate has no LLM | Accepted | Keeps the model off the critical path of sequencing and authorization |
| [0004](0004-postgres-adk-service-implementations.md) | Custom PostgreSQL implementations of ADK's session, memory and artifact SPIs | Accepted | Java ADK ships no JDBC session service, and restart-resume depends on one |
| [0005](0005-deprecated-resumability-config.md) | Depend on the deprecated `ResumabilityConfig`, contained behind one factory method | Accepted, review scheduled | It is the only route to HITL resumption in ADK 1.9.0 |
| [0006](0006-persistence-spring-data-jdbc.md) | Spring Data JDBC + `JdbcClient`; not jOOQ, not JPA | Accepted | Explicit SQL, and a clean build from a fresh checkout with no codegen |
| [0007](0007-durable-approval-binding.md) | Approval bound to a canonical action fingerprint, re-checked at execution | Accepted | Structurally prevents replay, stale approval and confused-deputy attacks |
| [0008](0008-rxjava-spring-bridge.md) | One RxJava-to-servlet bridge owning schedulers and MDC propagation | Accepted | ADK is RxJava, Spring is not; correlation IDs must survive the boundary |
| [0009](0009-junit-golden-scenario-evaluation.md) | JUnit golden-scenario harness driven by a deterministic `FakeLlm` | Accepted | Java ADK has no evaluation framework, and CI cannot call a paid API |

## Compatibility findings behind these records

Everything above was verified by reading `google/adk-java` at v1.9.0 rather than trusting
documentation or blog posts. The load-bearing findings:

**Present and usable in Java** — `SequentialAgent`, `ParallelAgent`, `LoopAgent` (bounded by
`maxIterations` and `EventActions.escalate`), `ExitLoopTool`, `LlmAgent.outputKey`, the full
before/after agent-model-tool callback set, `BasePlugin` and `PluginManager`, and — importantly —
real human-in-the-loop support: `ToolContext.requestConfirmation(hint, payload)`,
`LongRunningFunctionTool.create(instance, method, requireConfirmation)`, `ToolConfirmation`, and
`WorkflowAgentResumption`, which routes a confirmation back to the sub-agent that emitted it.

**Absent in Java, and worked around here** — no JDBC session service (ADR-0004), no persistent
memory service, no S3 artifact service, no evaluation framework (ADR-0009). `ResumabilityConfig` is
deprecated with no replacement shipped (ADR-0005). ADK is RxJava-based while Spring Boot 4 is not
(ADR-0008).
