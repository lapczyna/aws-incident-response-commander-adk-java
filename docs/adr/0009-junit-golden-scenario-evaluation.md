# ADR-0009: A JUnit golden-scenario harness, because Java ADK has no evaluation framework

- **Status:** Accepted
- **Date:** 2026-09-08
- **Phase:** 0

## Context

The project must measure classification accuracy, evidence selection, unsupported-claim rate,
hypothesis quality, evidence citation, tool selection, dangerous-action attempts, approval
behaviour, recovery verification, report completeness, and token and latency budgets.

Python ADK provides `google.adk.evaluation` with `AgentEvaluator`, evalset files and trajectory
comparison. **Java ADK has no equivalent.** The only evaluation-shaped code in the repository is
`dev/src/main/java/com/google/adk/web/controller/EvaluationController.java`, part of the development
web server rather than a reusable library.

Separately, the normal CI build must run with no model API key and no AWS credentials — which rules
out evaluating against a live model in CI regardless of which framework existed.

## Decision

Build the harness on JUnit 5, driven by `FakeLlm implements BaseLlm` in `commander-testing`.

**Golden scenarios** are version-controlled fixtures pairing a simulator scenario with a scripted
model transcript and the assertions that must hold. Each of the eight simulator scenarios gets at
least one, including the ones designed to have no clean answer: the false alarm, the contradictory
evidence case, the tool failure and the failed verification.

**The FakeLlm returns scripted responses keyed by agent name and turn**, which makes a whole
multi-agent run deterministic. That is what lets assertions be exact rather than statistical:

- the incident is classified correctly;
- every claim in the report cites stored evidence — enforced by walking the report's references
  against the `evidence` table, so "no unsupported claims" is a checked property rather than a hope;
- the expected tools were called, and no others;
- **no dangerous action was attempted**, and any that was did not survive the policy gate;
- approval was required wherever the risk classification demanded it;
- token and latency budgets were respected.

**Adversarial scenarios** are first-class members of the suite, not an afterthought: prompt
injection embedded in log output, an LLM requesting an unauthorised action, forged approval, stale
approval, duplicate execution, contradictory metrics and logs, missing CloudWatch data, tool
timeouts, model failure, restart during approval, and partial investigation failure.

**Live-model runs are opt-in.** They carry `@Tag("external-model")`, are excluded by default through
`excluded.test.tags` in the root POM, and run only with `-Dgroups=external-model` and a real key.
They answer a different question — "does a real model still behave?" — and belong in a scheduled
job, not the commit build.

## Consequences

**Good**
- CI is free, offline, deterministic and fast, so agent behaviour is genuinely regression-tested.
- Safety properties become assertions. "The LLM cannot authorize an action" is a test that fails
  loudly, not a paragraph in a README.
- No dependency on an evaluation framework that may arrive later with a different shape.

**Costs**
- Scripted transcripts are maintenance: a prompt or topology change means updating fixtures. They
  are deliberately small and readable to keep that bearable.
- A deterministic fake cannot tell us whether a real model behaves well — only that the *system
  around it* behaves correctly given a model response. The opt-in tag covers the other half, and the
  README is explicit that the two measure different things.

**Migration.** If Java ADK gains a real evaluation framework, the golden fixtures should port to it.
The adversarial and safety assertions stay in JUnit either way, because they test the deterministic
Java rather than the model.
