# Threat model

This system reads operational data written by systems under attack, feeds it to a language model,
and can change infrastructure. Each of those is a liability on its own; together they are the reason
almost everything consequential in this codebase is deterministic Java.

The governing assumption is stated once and applied everywhere: **the model is not trusted.** Not
because models are bad at this, but because a control that depends on a model behaving well is a
control whose strength cannot be stated, tested, or reasoned about. Every mitigation below is
positioned so that it holds whether the model is helpful, confused, or completely subverted.

## Trust boundaries

| Zone | Contents | Trusted? |
|---|---|---|
| Operational data | CloudWatch logs, CloudTrail descriptions, ECS `stoppedReason` | **No** — an attacker who reached the service chose this text |
| Model output | Hypotheses, proposals, narratives | **No** — derived from untrusted input by a component with no security properties |
| Deterministic Java | Policy engine, state machine, fingerprints, verification | Yes — this is the trusted computing base |
| Human approver | Decisions recorded against a fingerprint | Yes, within their role, and audited |
| AWS IAM | Task role permissions | Yes — the final boundary, enforced outside the process |

Note what is on which side. The model sits *inside* the untrusted zone: downstream of
attacker-chosen text, and upstream of nothing that matters.

## The threats

### T1 — Prompt injection via log output

An attacker who reached the service writes log lines shaped like instructions, hoping whatever
triages the incident follows them. Simulator scenario 9 does exactly this.

**Mitigation.** Trust is decided by *source*, never by content. Free-text sources are truncated,
stripped of control characters, wrapped in `<untrusted-evidence>` markers that content cannot forge,
and preceded by a standing instruction that any directive inside is a finding rather than an order.

**Where:** `EvidenceSanitizer` · **Test:** `PromptInjectionTest`

### T2 — Injection via CloudTrail change descriptions

The same attack through a different port. A defence covering only logs would be a defence against
the example rather than the class.

**Mitigation.** The same sanitiser, driven by `EvidenceSource.carriesFreeText()`, so adding a source
means classifying it rather than remembering to handle it.

**Where:** `EvidenceSanitizer`, `EvidenceSource` · **Test:** `PromptInjectionTest.changesAreDelimited`

### T3 — A fully subverted model proposes a destructive action

Assume T1 and T2 both fail, or are bypassed by wording nobody anticipated.

**Mitigation.** The proposal is evaluated by a pure function containing no natural language: account,
region, environment, ARN allowlist, action allowlist, required tag, confidence floor. Every rule is
evaluated — none short-circuits — so a denial reports all of them.

**Where:** `PolicyEngine`, `PolicyGateAgent` · **Tests:**
`PromptInjectionTest.compromisedModelIsRefused`, `GoldenScenarioTest.hostileModelNeverExecutes`

The second test runs a model that has fully accepted the injected instructions against *every*
scenario, not just the injection one. A defence that holds on the case it was written for and fails
elsewhere is not a defence.

### T4 — A denial is ignored downstream

The gate refuses and something runs anyway.

**Mitigation.** The guarded stages are the gate's *sub-agents*, not its siblings. A denial simply
does not run them; there is no signal that could be missed. This replaced an `endInvocation` design
that ADK's `SequentialAgent` silently ignores — the original approach looked correct and did nothing.

**Where:** `PolicyGateAgent` · **Tests:** `PolicyGateTest` · **Background:** ADR-0003

### T5 — Approval replay

A captured approval is submitted twice to execute the action twice.

**Mitigation.** Two independent mechanisms. A unique constraint on the decision row, and an
idempotency claim taken on the action fingerprint *before* acting. The database decides the winner,
so correctness does not depend on application-level locking or on there being one process.

**Where:** `ApprovalService`, `IdempotencyStore` · **Tests:** `ApprovalAttackTest.replayRefused`,
`ApprovalResumeIntegrationTest`

### T6 — Stale approval

The incident moves on, and the approval a human gave no longer describes the situation they judged.

**Mitigation.** The fingerprint is recomputed from the incident's current version and compared, so
any material change invalidates outstanding approvals. Policy is then re-run at execution time,
because the world can move between approval and execution.

**Where:** `ActionFingerprint`, `ApprovalService`, `RemediationTool` · **Test:**
`ApprovalAttackTest.staleApproval`

### T7 — Confused deputy

The system approves its own remediation, or the person who raised the incident approves acting on it.

**Mitigation.** The system cannot approve; only the approver role can; and the actor who opened an
incident cannot approve acting on it.

The last of those was **documented before it was implemented**. Writing this threat model is what
found the gap: the class comment on `ApprovalService` claimed the check, and the code only tested for
the system actor. Reading back the opener now requires `IncidentRepository.openedBy`, which existed
as a write and had no reader.

**Where:** `ApprovalService.validate` · **Tests:** `ApprovalAttackTest.systemCannotSelfApprove`,
`ApprovalAttackTest.openerCannotApprove`

### T8 — Expired approval redeemed late

A decision taken hours ago is used after the situation changed.

**Mitigation.** Expiry is evaluated on read, not only by a background sweep, so a sweep that fails to
run delays a status change rather than extending the window in which an approval is usable. The
boundary is closed: an approval expiring exactly now is expired, so the usable window does not depend
on clock resolution.

**Where:** `ApprovalRequest.hasExpired`, `ApprovalService` · **Tests:**
`ApprovalAttackTest.expiredApproval`, `ApprovalAttackTest.expiryBoundaryIsClosed`

### T9 — Credential and secret exposure

Keys or account detail reach a prompt, a log line, a container image, or an incident report.

**Mitigation.** No code path accepts an access key — `DefaultCredentialsProvider` only. AWS error
*messages* are never passed through, because they carry ARNs, account ids and request context; the
error *code* is, because that is what an investigator needs. Task-definition ARNs are shortened to
`family:revision` before they reach a prompt. Model telemetry records token counts and cost, never
prompt or response text.

**Where:** `AwsClientConfiguration`, `AwsFailures`, `EcsAdapter`, `TelemetryPlugin` · **Tests:**
`InvestigationWorkflowTest` (asserts no credential-shaped string reaches a prompt),
`GoldenScenarioTest.noAccountIdsFromTaskDefinitions`, plus gitleaks in CI

### T10 — Unbounded cost or a runaway loop

An agent loops, or a large context is resent, until the bill is the incident.

**Mitigation.** Four independent bounds: `RunConfig.maxLlmCalls`, `LlmAgent.maxSteps`, a shared
per-invocation tool budget with a wall-clock deadline, and a monthly `CostGuard` that fails closed.
Refusals are returned as structured tool results, so an agent that hits a ceiling reports what it has
instead of crashing and discarding the evidence already gathered.

**Where:** `InvestigationBudgetPlugin`, `CostGuard` · **Tests:** `CostGuardTest`,
`GoldenScenarioTest.staysWithinBudget`

### T11 — False assurance

The system reports success for a remediation that changed nothing, and a human stops paying
attention.

**Mitigation.** Recovery is judged by numeric comparison in Java with four outcomes; only
`RECOVERED` resolves an incident, and an unreadable metric is `INDETERMINATE` rather than success.
Report citations are checked against stored evidence, and any that resolve to nothing are printed as
a warning inside the report itself.

**Where:** `RecoveryVerification`, `RecoveryVerifier`, `IncidentReportRenderer` · **Tests:**
`RecoveryVerificationTest`, `IncidentReportRendererTest`

### T12 — The deployment pipeline as a path around the approval gate

Everything above constrains what the *running* system can do. None of it constrains what can be
deployed. A workflow that could grant the task role a wider policy, point the Commander at a
different account, or flip `dry_run` would reach production change without passing a single control
in the application — and it would do so through the one component nobody thinks of as part of the
security model.

**Mitigation.** The deploy role's trust policy is bound to a GitHub *Environment*, not a branch, so
credentials are issued only after that environment's required reviewers have approved: the approval
is a precondition for holding credentials rather than a step in a workflow that could be reordered
(ADR-0011). Both workflows are `workflow_dispatch` only, and applying additionally requires typing a
confirmation phrase. The role itself is scoped by resource-name prefix and carries explicit denies
for every identity-escalation path — `iam:CreateUser`, `iam:CreateAccessKey`,
`iam:UpdateAssumeRolePolicy`, and attaching any managed policy other than the ECS task execution one
— which no later `Allow` can override. The action policy is attached only when
`enable_remediation_actions` is true, and its default is false, so the deployed task role does not
carry `ecs:UpdateService` at all unless someone asked for it in writing.

**Where:** `infra/terraform/bootstrap/oidc.tf`, `infra/terraform/iam.tf`, `.github/workflows/` ·
**Tests:** `DeploymentContractTest` (the deployment defaults are the safe ones, the required tag
matches the tag Terraform attaches, and no IAM placeholder exists that Terraform does not substitute)

### T13 — Configuration drift silently disabling a control

The tag rule is enforced three times and every one of those checks compares against a *configured*
value. If Terraform stops attaching `Project=aws-incident-response-commander`, or the application
starts requiring a different value, all three layers refuse every action — and the symptom looks
like a policy bug rather than a deployment one. The reverse drift is worse: an environment name that
no longer matches means a proposal is refused for the wrong reason, and someone widens the rule to
make the demo work.

**Mitigation.** The agreement is asserted rather than documented. The required tag in
`application.yaml`, the `project_tag` default in `variables.tf`, and the tag the ECS service
actually carries are checked against each other in the Java build, as are the environment name and
the log-group prefix the read policy is scoped to.

**Where:** `application.yaml`, `infra/terraform/variables.tf`, `infra/iam/` · **Test:**
`DeploymentContractTest`

## Defence in depth, concretely

The demo tag rule is enforced three times, in three places that fail independently:

1. `PolicyEngine` checks it in code, from tags supplied by the caller.
2. `AwsRemediationExecutor` re-reads the tags from live AWS immediately before acting, because the
   engine is *given* tags and whatever supplied them could be wrong.
3. IAM refuses the API call outright through a tag condition, at a layer no bug in either can bypass.

The approval rule is enforced twice: once when a human decides, and again inside the tool that
executes, which re-runs the policy engine and claims the fingerprint before acting.

## What this model does not defend against

Stated plainly, because a threat model claiming total coverage is not one.

- **A compromised AWS account or task role.** Everything here assumes the IAM boundary holds. An
  attacker holding the task role does not need this application.
- **Network-level attacks on the deployed tasks.** There is no NAT Gateway, so Fargate tasks run in
  public subnets with routable addresses and the security group is the only thing in front of them.
  That is a deliberate cost trade, described in full in [ADR-0011](adr/0011-deployment-topology-and-cost.md),
  and it is why `admin_cidr` defaults to empty and creates no ingress rule at all. Anyone adapting
  this for something real should reverse that decision first.
- **A malicious operator with the approver role.** Separation of duties closes one specific
  self-approval path. It does not stop two colluding humans, and no control here would.
- **Model provider compromise.** A subverted provider could return anything; the blast radius is the
  same as a subverted model, bounded by the policy engine. The provider also sees every prompt, which
  is why the warning about not sending real incident data through a free tier is in the README rather
  than buried here.
- **Denial of service against the demo target service.** Fault injection is bounded and auto-expiring
  by construction, but that service is a demonstration target, not a hardened one.
- **Injection *detection*.** No attempt is made to spot hostile phrasing. Deciding trust by
  pattern-matching for "ignore previous instructions" is a game the attacker wins, because they
  choose the wording. Trust is decided by source, which they do not.

## The one thing worth restating

Every mitigation above is arranged so that a successful prompt injection produces a **wrong
suggestion**, which is then refused by code that has no natural language in it.

That is the entire design. If a future change makes any model output directly consequential — a model
choosing an ARN, setting a threshold, deciding whether something is approved, or deciding whether an
incident is resolved — this threat model is void and needs rewriting rather than amending.
