# Model setup

Everything downstream depends on ADK's `BaseLlm`, so orchestration never names a provider. A Spring
profile selects the implementation. See [ADR-0002](adr/0002-model-provider-strategy.md).

There is deliberately **no default bean**. A deployment must state which model it uses; a silent
fallback to a paid API — or to a fake in production — is worse than a startup failure that tells you
which profile to activate.

| Profile | Model | Needs | Use it for |
|---|---|---|---|
| `fake` | Scripted `FakeLlm` | nothing | **CI default.** Tests and development. |
| `gemini` | Gemini Developer API | an API key | The recommended way to run the demo. |
| `ollama` | Local model via Ollama | ~4 GB RAM | When data cannot leave your machine. |
| `bedrock` | Amazon Nova Lite | an AWS role, and model access enabled | The cheapest real model, and the only one with no key to manage. |

---

## `fake` — the CI default

No setup. `./mvnw verify` uses it, which is why the full suite runs with no API key, no network and
no cost.

`FakeLlm` returns a scripted sequence of turns — text replies and tool calls — and records every
request it receives. The recording matters as much as the replies: it is how tests assert which
tools were offered, and that untrusted evidence reached the prompt wrapped in its delimiters.

```java
FakeLlm model = FakeLlm.builder()
    .callsTool("queryServiceMetric", Map.of("serviceName", "checkout",
                                            "metricName", "TargetResponseTimeP99"))
    .respondsWith("p99 stepped from 0.18s to 0.62s about 25 minutes ago.")
    .build();
```

**What this proves and does not prove.** It verifies the system *around* the model behaves correctly
given a model response. It says nothing about whether a real model would produce that response —
a different question, answered by the opt-in `external-model` tests.

---

## `gemini` — recommended

1. Get a key from [aistudio.google.com/apikey](https://aistudio.google.com/apikey).
2. Export it:

```bash
export GEMINI_API_KEY=...           # bash
$env:GEMINI_API_KEY = "..."         # PowerShell
```

3. Run with the profile active:

```bash
./mvnw -pl commander-api spring-boot:run -Dspring-boot.run.profiles=gemini
```

The model id is configuration, never a constant, because Gemini's model names move quickly:

```yaml
commander:
  model:
    gemini:
      name: gemini-3.5-flash-lite   # check AI Studio for the current free-tier list
```

> **Data handling.** Free-tier prompts may be used to improve Google's products; paid tiers are
> excluded. **Do not send real incident data through the free tier.** Use `ollama` if the data
> cannot leave your machine.

The key is read from the environment locally and from AWS Secrets Manager when deployed. It is never
written to a property file, never logged, and never reaches a prompt — a test asserts the last of
those directly.

---

## `ollama` — fully local

Reaches ADK through the Spring AI bridge, because ADK Java has no Ollama connector of its own.
`SpringAI` adapts any Spring AI `ChatModel` onto `BaseLlm`, and ADK's own test suite exercises this
path, so it is a supported route rather than an improvisation.

1. Install Ollama from [ollama.com](https://ollama.com).
2. Pull a model that can call tools — this is the constraint that matters:

```bash
ollama pull qwen3:4b
```

3. Run:

```bash
./mvnw -pl commander-api spring-boot:run -Dspring-boot.run.profiles=ollama
```

```yaml
commander:
  model:
    ollama:
      name: qwen3:4b
      base-url: http://localhost:11434
```

### Choosing a local model

**Tool calling is the requirement, not size.** This workflow is almost entirely function calls; a
model that writes beautiful prose but cannot reliably emit a tool call is useless here. Many small
models claim tool support and degrade badly once several tools are offered at once.

| Model | RAM | Notes |
|---|---|---|
| `qwen3:4b` | ~4 GB | Default. Good tool calling for its size. |
| `qwen3:8b` | ~6 GB | Noticeably more reliable with four tools offered. |
| `llama3.2:3b` | ~3 GB | Smallest that works; expect more retries. |

Temperature is pinned low (0.1). This is analysis, not creative writing — an investigation that
differs run to run is harder to trust and impossible to regression-test.

### Verifying tool calling actually works

A local model that answers conversationally but never calls a tool would pass a naive smoke test and
fail the real workflow. The check therefore asserts a **tool invocation**, not a reply:

```bash
./mvnw verify -Dgroups=ollama
```

Tagged `ollama` and excluded from the default build. It uses Testcontainers, so it needs Docker but
not a local Ollama install — the same approach ADK uses for its own Ollama tests.

---

## `bedrock` — the cheapest real model, and no key to manage

Amazon Nova Lite at $0.06/$0.24 per million tokens: roughly **$0.54/month** against the documented
workload of 50 incidents at ~120k input and ~15k output tokens each. `CostGuardTest` computes that
figure from the same `ModelPricing` record the runtime uses, so it cannot drift away from what the
guard enforces.

```bash
export AWS_REGION=eu-west-1
./mvnw -pl commander-api -am spring-boot:run -Dspring-boot.run.profiles=bedrock,simulator
```

**Two things have to be true before it works.**

*Model access is per account and per region.* Enable Nova Lite in the Bedrock console for your
region; until you do, the first call returns `AccessDeniedException` and nothing earlier hints at
it.

*A region must be configured.* The profile refuses to start without one. Spring AI's own fallback,
when no region resolves, is `us-east-1` logged at debug — a wrong answer about cost, latency and
data residency delivered quietly. `ModelProfilesTest.refusesWithoutRegion` holds that guard in
place.

Credentials are never configured. Locally it uses your profile; on Fargate it authenticates as the
task role, and `bedrock:InvokeModel` is scoped by IAM to exactly one model ARN — so choosing a more
expensive model means editing infrastructure rather than an environment variable. The Terraform
attaches that policy only when `model_profile = "bedrock"`.

**Reaching ADK.** Through the same `SpringAI` adapter as Ollama, so there is one integration path
rather than two (ADR-0002). `BedrockModelFactory` deliberately contains no AWS SDK types at all:
`ArchitectureRulesTest` fails the build if the SDK is referenced outside
`commander-integrations-aws`, so region and credentials are left to Spring AI's own resolution
rather than passed in.

> Nova Lite's tool-calling is measurably weaker than Gemini's. It is the right choice for cost and
> for having nothing to leak; it is not the right choice for the best investigation you can get.

---

## Cost and limits

`RunConfig.maxLlmCalls` is set to **20** per investigation. ADK's default is 500, which is far too
generous for a workflow whose job is to gather evidence and summarise it: an agent looping on a
confusing signal would burn real money before anyone noticed. Exceeding the limit raises
`LlmCallsLimitExceededException`, which the incident records as a failure rather than absorbing as a
silent overspend.

`LlmAgent.maxSteps` bounds reasoning steps within a single agent at 12.

Neither limit is the primary cost control — that is the fake model being the CI default. They exist
because a limit that only applies in production is a limit nobody tests.

---

## Troubleshooting

**`The gemini profile is active but GEMINI_API_KEY is not set`** — deliberate. The alternative is
starting up and failing on the first investigation, by which point an incident already exists.

**Ollama: the model replies but never calls a tool** — the model is too small or its tool support is
nominal. Try `qwen3:8b`. Confirm with `-Dgroups=ollama`, which fails on exactly this.

**`LlmCallsLimitExceededException`** — the agent looped. Check the persisted events for the repeated
call; it usually means a tool returned something the model could not use and it retried rather than
reporting the gap.
