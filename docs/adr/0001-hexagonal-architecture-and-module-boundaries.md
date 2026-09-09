# ADR-0001: Hexagonal architecture with an enforced framework-free domain

- **Status:** Accepted
- **Date:** 2026-09-08
- **Phase:** 0

## Context

The governing requirement of this project is that **an LLM alone must never authorize a
production-changing action**. Authorization, resource scope, action allowlists, risk thresholds,
idempotency, budgets and incident state transitions are all decisions that must be deterministic,
exhaustively testable, and auditable.

That requirement is architectural, not stylistic. If the code that decides "may this action run?"
is entangled with Spring wiring, ADK types, the AWS SDK or JDBC, then it can only be tested with a
container and a database, and its behaviour becomes hard to reason about precisely where reasoning
matters most.

A second force: the same investigation workflow must run against real AWS **and** against a local
deterministic simulator, so the whole demo works with no AWS account.

## Decision

Ports-and-adapters, with the dependency rule enforced by tests rather than by convention.

```
commander-domain        <- no framework dependencies at all
commander-application   <- use cases + outbound ports (interfaces)
commander-adk | commander-persistence-postgres | commander-integrations-aws | commander-simulator
commander-api           <- Spring Boot composition root
```

Concretely:

1. `commander-domain` has an **empty `<dependencies/>` block**. It cannot import Spring, ADK, the
   AWS SDK, JDBC or even Jackson. Every safety decision lives here.
2. `commander-application` declares outbound ports as plain interfaces. Each port has **two**
   implementations — a real AWS adapter and a simulator adapter — which is what makes the
   no-AWS-account demo possible rather than aspirational.
3. Google ADK types are confined to `commander-adk` (plus `commander-persistence-postgres`, which
   must implement ADK's SPIs — see ADR-0004).
4. The AWS SDK is confined to `commander-integrations-aws`.

`ArchitectureRulesTest` in commander-api enforces all of the above. It lives there because
commander-api is the only module that depends on every other one, so the importer sees the whole
graph.

## Consequences

**Good**
- The policy engine and state machine are unit-testable with no container, database or network.
- Swapping ADK, or upgrading it across a breaking change, is a local edit in one module.
- The simulator is not a test double bolted on afterwards; it is a first-class adapter satisfying
  the same interfaces, which is why scenarios stay reproducible.

**Costs**
- More modules than a single Spring Boot application needs, and more mapping between layers. For a
  project whose entire point is demonstrating disciplined agent architecture, that cost is the
  deliverable.
- Nine modules make the reactor build slower than one.

**Watch out**
- Empty-package rules pass vacuously. `archRule.failOnEmptyShould=false` is required while modules
  are still being populated, so `everyDeclaredLayerIsPopulated` guards it with an explicit
  `BOOTSTRAP_EMPTY` list that must shrink to nothing by Phase 3.
- A subtle trap found while writing these rules: `ImportOption.Predefined.DO_NOT_INCLUDE_JARS`
  silently excludes sibling modules during `mvn verify`, because by then they are packaged jars.
  With it enabled the rules only checked commander-api and reported success. It is deliberately
  **not** used; restricting the import to the project root package is what keeps third-party code
  out.
