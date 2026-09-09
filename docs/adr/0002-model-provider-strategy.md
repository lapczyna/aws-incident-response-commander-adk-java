# ADR-0002: Provider-independent model abstraction, Gemini by default, fake model in CI

- **Status:** Accepted
- **Date:** 2026-09-08
- **Phase:** 0

## Context

The project must support Gemini, a fully local Ollama option and an optional Amazon Bedrock
profile, without the domain or orchestration code depending on any one provider. The normal CI
build must need no API key, no network and no AWS credentials.

Python ADK's LiteLLM examples do **not** apply to Java, so each integration had to be verified
against the Java sources directly.

## Findings (verified against `google/adk-java` @ 1.9.0, not documentation)

- `com.google.adk.models.BaseLlm` is the provider SPI. `Gemini`, `Claude` and `ApigeeLlm` implement it.
- The `google-adk-spring-ai` module (published as `com.google.adk:google-adk-spring-ai:1.9.0`)
  provides `com.google.adk.models.springai.SpringAI extends BaseLlm`, constructible from any Spring
  AI `ChatModel`: `new SpringAI(chatModel, modelName)`.
- ADK's own test suite exercises Ollama through Testcontainers
  (`contrib/spring-ai/.../ollama/OllamaTestContainer.java`, pulling `llama3.2:1b`), which confirms
  the Spring AI path is a supported integration and not an unproven one.
- Both Bedrock routes exist: `spring-ai-bedrock-converse:2.0.1` (Spring AI) and
  `com.anthropic:anthropic-java-bedrock:2.61.0` (backing ADK's native `Claude`).

## Decision

All orchestration depends on ADK's `BaseLlm`. A Spring profile selects the implementation.

| Profile | Wiring | Default |
|---|---|---|
| `gemini` | native `Gemini`; key from `GEMINI_API_KEY`, or Secrets Manager when deployed | **yes** |
| `ollama` | `spring-ai-ollama` `OllamaChatModel` -> `SpringAI` | no |
| `bedrock` | `spring-ai-bedrock-converse` -> `SpringAI`, model `amazon.nova-lite` | no, opt-in |
| `fake` | `FakeLlm implements BaseLlm`, scripted deterministic responses | **CI default** |

Ollama and Bedrock go through the *same* `SpringAI` adapter, so there is one integration path to
maintain rather than two.

**Bedrock model choice.** Nova Lite is $0.06 / $0.24 per million input/output tokens. Against the
documented sample workload (50 incidents/month, ~120k input and ~15k output tokens each) that is
roughly **$0.42/month**, far inside the $10 ceiling. Claude Haiku 4.5 at $1 / $5 would be about
$4-6/month — also inside the ceiling and better at tool calling, but 10x the cost for an optional
profile that exists to prove portability. Nova Lite wins; the trade-off is recorded here rather
than hidden. Request, token and monthly-cost limits are enforced by a `CostGuard` that **fails
closed**. No provisioned throughput is ever requested.

## Consequences

**Good**
- CI is deterministic, free and offline. This is the single most important property: agent tests
  that depend on a live model are neither reproducible nor cheap.
- Adding a provider means adding a profile, not touching orchestration.

**Costs**
- The `SpringAI` bridge is a translation layer, so provider-specific features (Gemini thinking
  budgets, for instance) are reachable only on the native path.
- Nova Lite's tool-calling is measurably weaker than Gemini's. The Bedrock profile is documented as
  a portability demonstration, not the recommended way to run this system.

**Data handling.** Gemini free-tier prompts may be used to improve Google's products; paid tiers
are excluded. The README states plainly that real incident data must not be sent through the free
tier. The Ollama profile exists precisely for anyone who cannot send data anywhere.
