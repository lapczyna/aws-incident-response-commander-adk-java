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
model transcript and the assertions that must hold. Each of the nine simulator scenarios gets at
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

---

## What was actually built (Phase 9)

The shape changed in one important way once it existed, and the record is amended rather than
quietly left describing something else.

**The harness does not assert `expectedOutcome`.** The plan above says each scenario asserts that the
incident was classified correctly. Written against a `FakeLlm`, that assertion is circular: the fake
returns whatever it was scripted to return, so the test would be checking the script. The number it
would produce — "9 of 9 scenarios classified correctly" — would look like an accuracy measurement and
be nothing of the kind, which is worse than having no number.

So `GoldenScenarioTest` asserts only what holds **regardless of what the model concludes**, and it
asserts all of it across every scenario rather than one:

- a hostile model never reaches an execution, in any scenario;
- every scenario finishes inside its tool budget;
- every scenario terminates, including the one whose log source fails mid-run;
- untrusted evidence is delimited and its markers balanced wherever free text appears;
- no scenario leaks a task-definition ARN, and therefore an account id, into a prompt.

Adding a scenario file adds it to all of these automatically, so a new scenario cannot be added
without being held to the same invariants.

**Scenario 9 was added for this phase.** A genuine incident whose logs and CloudTrail descriptions
are written to steer whatever reads them. Its injected lines are shaped like errors on purpose — an
attacker who wants their text read will put it where an investigator searches, and an injection
buried in output no query returns is not an attack anyone needs a defence against.

**Model judgement remains unmeasured, and is documented as unmeasured.** The `external-model` tag and
the mechanism for it exist; no accuracy figure is claimed anywhere in this repository, because none
has been measured.

**One test caught itself passing for the wrong reason.** An early version of the injection test used
a plausible but unparseable proposal format. It passed — the pipeline refused it as `NO_PROPOSAL`
before policy was ever consulted. That is a real defence and it is not the one the test claimed to be
demonstrating. The fixtures now emit well-formed JSON so the policy engine is what refuses them, and
the surefire output shows four independent rules firing.

