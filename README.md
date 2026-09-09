# AWS Incident Response Commander

An approval-gated, multi-agent incident response system built on **Google ADK for Java**.

It receives an alert, gathers evidence from four sources in parallel, forms a hypothesis and then
argues against it under a bounded critique loop, proposes remediation, **stops and waits for a human**,
executes only what was approved, verifies recovery, and writes the postmortem.

> **The governing constraint:** an LLM alone can never authorize a production-changing action.
> Authorization, resource scope, action allowlists, risk thresholds, idempotency, budgets and state
> transitions are all deterministic Java. The model classifies, correlates, hypothesises and writes
> prose — nothing more.

The whole system runs locally against a deterministic simulator, so **no AWS account and no model
API key are needed** to see it work or to run the tests.

---

## Status

Built in phases. Current state: **Phase 3 of 11 complete** — the first real ADK investigation runs
end to end against the simulator, with durable ADK sessions in PostgreSQL.

`./mvnw verify` runs **229 tests** with no model API key and no AWS credentials.

| Phase | Scope | State |
|---|---|---|
| 0 | Architecture and repository foundation | ✅ done |
| 1 | Domain and durable incident lifecycle | ✅ done |
| 2 | Fault-injectable service and signal simulator | ✅ done |
| 3 | First ADK investigation (vertical slice) | ✅ done |
| 4 | Parallel multi-agent investigation | next |
| 5 | Bounded diagnosis and remediation planning | planned |
| 6 | Durable approval-gated remediation | planned |
| 7 | Guarded AWS adapters | planned |
| 8 | Recovery verification and reporting | planned |
| 9 | Evaluation, security and observability | planned |
| 10 | Cost-conscious AWS deployment | planned |
| 11 | Portfolio polish | planned |

---

## Which ADK concepts this demonstrates, and where to look

This project exists partly to be read. Each ADK concept below maps to a specific place in the code.

| ADK concept | Where | Notes |
|---|---|---|
| `SequentialAgent` | `commander-adk` — `IncidentAgentFactory` | Ordered incident stages |
| `ParallelAgent` | same | Four investigators run concurrently |
| `LoopAgent` + `ExitLoopTool` | same | Hypothesis → critique → refine, `maxIterations = 3` |
| Custom `BaseAgent` | `PolicyGateAgent` | A deterministic stage with no model call at all |
| `LlmAgent.outputKey` | all specialists | Typed hand-off through session state |
| Callbacks | `commander-adk` | Budgets, deadlines, partial-failure handling |
| `BasePlugin` | `BudgetPlugin`, cost telemetry | Token, request and cost accounting |
| **Human-in-the-loop** | `LongRunningFunctionTool(requireConfirmation = true)` | `ToolContext.requestConfirmation(...)` |
| **Resumable execution** | `PostgresSessionService` + `App.resumabilityConfig` | Survives a JVM restart mid-approval |
| Custom `BaseSessionService` | `commander-persistence-postgres` | Java ADK ships no JDBC implementation |
| `BaseLlm` provider SPI | model profiles | Gemini, Ollama, Bedrock, and a deterministic fake |

### What Java ADK could not do, and what was built instead

Honest accounting, verified against `google/adk-java` v1.9.0 source rather than documentation:

| Gap | Consequence here |
|---|---|
| No JDBC/PostgreSQL session service (only in-memory, Vertex AI, Firestore) | `PostgresSessionService` implements `BaseSessionService` — [ADR-0004](docs/adr/0004-postgres-adk-service-implementations.md) |
| No persistent memory service | `PostgresMemoryService` |
| No S3 artifact service (only in-memory and GCS) | `PostgresArtifactService` |
| `ResumabilityConfig` is `@Deprecated` with no replacement shipped | Used deliberately, contained behind one factory method — [ADR-0005](docs/adr/0005-deprecated-resumability-config.md) |
| No evaluation framework (Python's `google.adk.evaluation` has no Java twin) | JUnit golden-scenario harness — [ADR-0009](docs/adr/0009-junit-golden-scenario-evaluation.md) |
| ADK is RxJava 3; Spring Boot 4 is not | One bridge class owning schedulers and MDC propagation — [ADR-0008](docs/adr/0008-rxjava-spring-bridge.md) |

The good news, also verified: **human-in-the-loop genuinely works in Java.**
`ToolContext.requestConfirmation()`, `ToolConfirmation`, and `WorkflowAgentResumption` — which routes
an approval back to the sub-agent that emitted the call inside a `SequentialAgent` — are all real.

---

## Architecture

Full diagrams: [`docs/diagrams/architecture.md`](docs/diagrams/architecture.md) — component, agent
topology, approval sequence, state machine and deployment.

```
commander-domain        no framework dependencies at all — every safety decision lives here
commander-application   use cases and outbound ports
    ├── commander-adk                 ADK agents, tools, plugins, model profiles
    ├── commander-persistence-postgres Flyway, repositories, ADK SPI implementations
    ├── commander-integrations-aws    CloudWatch, Logs, ECS, CloudTrail  (read-only + guarded actions)
    └── commander-simulator           deterministic fixtures — same ports, no AWS account
commander-api           Spring Boot: REST, OpenAPI, security, Thymeleaf + HTMX console, SSE
demo-target-service     the fault-injectable payment API being investigated
commander-testing       FakeLlm, golden scenarios, Testcontainers support
```

The dependency rule is enforced by `ArchitectureRulesTest`, not by convention. It fails the build if
the domain imports Spring, ADK, the AWS SDK or JDBC; if ADK types escape their module; or if the
simulator reaches for the AWS SDK.

---

## Quick start

### Prerequisites

- **JDK 25** (verified with BellSoft Liberica 25.0.2)
- **Docker** — for Testcontainers and the local stack
- Nothing else. Maven arrives via the wrapper; no AWS account or API key is required to build.

### Build

```bash
git clone <this repo>
cd aws-incident-response-commander-adk-java
./mvnw verify
```

That runs the full suite with **no model API key, no AWS credentials and no network model calls** —
the fake-model profile is the CI default. Docker is needed only for the Testcontainers integration
tests.

```powershell
# Windows
.\mvnw.cmd verify
```

### Run the local stack

```bash
docker compose up -d                     # PostgreSQL + the fault-injectable target service
scripts/generate-traffic.sh --rps 5      # steady traffic against it

# Inject a bad-deployment latency regression that expires on its own after 10 minutes
curl -X POST localhost:8081/admin/faults/latency   -H 'Content-Type: application/json'   -d '{"durationSeconds": 600, "parameters": {"millis": "450"}, "activatedBy": "demo"}'

curl -s localhost:8081/admin/faults       # what is active, and for how long
curl -X DELETE localhost:8081/admin/faults # reset everything
```

Fault injection is **off unless explicitly enabled**, every fault expires on its own, and every
magnitude is clamped to a compiled-in ceiling. See [docs/simulator.md](docs/simulator.md).

### Useful commands

```bash
./mvnw spotless:apply                     # format (google-java-format)
./mvnw verify -Dgroups=ollama             # opt-in: local model tool-calling check (pulls a model)
./mvnw verify -Dgroups=external-model     # opt-in: real API key required
./mvnw dependency:tree -Dscope=test       # regenerate the dependency matrix
```

---

## Model profiles

Set with `--spring.profiles.active=...`. See
[ADR-0002](docs/adr/0002-model-provider-strategy.md).

| Profile | What it uses | Cost | Default |
|---|---|---|---|
| `gemini` | Gemini Developer API, native ADK integration | Free tier available | recommended |
| `ollama` | Fully local via Spring AI → ADK `SpringAI` adapter | Free, needs ~4 GB RAM | privacy-first |
| `bedrock` | Amazon Nova Lite via Bedrock Converse | ~$0.42/month at the documented workload | opt-in, Phase 7 |
| `fake` | Deterministic scripted responses | Free | **CI default** |

Full instructions, including how to pick a local model that can actually call tools:
[docs/model-setup.md](docs/model-setup.md).

> **Data handling.** Gemini **free-tier** prompts may be used to improve Google's products; paid
> tiers are excluded. Do not send real incident data through the free tier. The `ollama` profile
> exists for anyone who cannot send data anywhere.

> **Cost ceiling.** The $10/month ceiling in this project applies to **LLM usage only**. It does not
> describe the cost of the AWS infrastructure, which is documented separately in the cost analysis
> delivered in Phase 10.

---

## Safety model

- The **policy engine runs twice** — once at the gate, and again inside the tool body at execution
  time. Approval is necessary, never sufficient.
- Approvals bind to a **canonical action fingerprint** over the action, arguments, target ARN,
  incident id and incident version. Any material change invalidates the approval
  ([ADR-0007](docs/adr/0007-durable-approval-binding.md)).
- Execution is **idempotent by fingerprint**; a replay returns the stored result and acts once.
- Actions are **dry-run by default**, restricted to allowlisted, correctly tagged resources in the
  configured account, region and environment.
- Tool output is treated as **untrusted evidence, never instruction** — sanitised, bounded, and
  wrapped in explicit delimiters to resist prompt injection through log content.
- No chain-of-thought is persisted. Stored rationale is the model's own conclusions, evidence
  references and decisions.

---

## Documentation

| Document | Contents |
|---|---|
| [ADRs](docs/adr/README.md) | Nine decision records, plus the ADK Java compatibility findings |
| [Diagrams](docs/diagrams/architecture.md) | Component, agent topology, approval sequence, state machine, deployment |
| [Dependency matrix](docs/dependency-matrix.md) | Every version, resolved by the build and explained |
| [Schema](docs/schema.md) | Durable state, the constraints that carry weight, and the append-only audit rule |
| [Simulator & target service](docs/simulator.md) | The eight scenarios, fixture format, and the fault-injection safety model |
| [Model setup](docs/model-setup.md) | Gemini, Ollama and the fake model; choosing a local model that can actually call tools |

Arriving in later phases: simulator scenario tutorial, Gemini/Ollama/Bedrock setup guides, AWS
deployment and teardown, cost analysis, threat model, runbook, evaluation guide, troubleshooting and
a sample postmortem.

---

## Technology

Java 25 · Spring Boot 4.1.1 · Google ADK for Java 1.9.0 · Spring AI 2.0.1 · PostgreSQL · Flyway ·
Spring Data JDBC · AWS SDK v2 · Terraform · Docker · ECS Fargate · JUnit 5 · Testcontainers 2.0 ·
ArchUnit · Micrometer · OpenTelemetry

Exact versions and the reasoning behind each: [`docs/dependency-matrix.md`](docs/dependency-matrix.md).

---

## License

Not yet licensed. A license will be added before the repository is published.
