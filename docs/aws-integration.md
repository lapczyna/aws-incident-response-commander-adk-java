# AWS integration

The AWS adapters implement exactly the ports the simulator implements. Nothing above them knows
which is in use, which is what lets the whole system be developed, demonstrated and tested without
an AWS account — and it is why an AWS-only bug cannot hide behind a passing local build.

Activated by the `aws` Spring profile. Without it, the simulator satisfies the same interfaces.

## Read adapters

| Port | Adapter | AWS call |
|---|---|---|
| `MetricsPort` | `CloudWatchMetricsAdapter` | `GetMetricStatistics` |
| `LogsPort` | `CloudWatchLogsAdapter` | `FilterLogEvents` |
| `AlarmsPort` | `CloudWatchAlarmsAdapter` | `DescribeAlarms` |
| `EcsPort`, `DeploymentHistoryPort` | `EcsAdapter` | `DescribeServices`, `ListTasks`, `DescribeTasks` |
| `ChangeHistoryPort` | `CloudTrailAdapter` | `LookupEvents` |

### Two choices worth explaining

**`FilterLogEvents`, not Logs Insights.** Insights is far more expressive. It is also an
asynchronous three-call protocol — start, poll, fetch — whose latency is unpredictable and whose
polling loop would sit inside an investigation's deadline. For "find recent lines matching a
pattern", predictable latency is worth more than query power. The regex is applied locally over a
bounded superset, which keeps the port's contract identical in AWS and simulator modes so one set of
prompts works for both.

**`GetMetricStatistics`, not `GetMetricData`.** `GetMetricData` is the right call for a dashboard
fetching many series. Here it is always one metric over one window, and the simpler call has a
simpler failure surface and no query-language layer between the request and the answer.

## Bounds

Every SDK default is overridden, because the defaults are tuned for a service that would rather wait
than fail and an investigation would rather fail than wait.

| Bound | Value | Why |
|---|---|---|
| Attempt timeout | 5s | A source that cannot answer quickly is better reported as a gap |
| Call timeout | 15s | Caps the worst case at roughly two attempts plus backoff |
| Retries | 2 | Retrying an `AccessDenied` three times turns a clear permissions problem into a slow one |
| Log events scanned | 500 | Bounds the cost of the call, not just the size of the answer |
| Tasks described | 50 | A service that churned through hundreds of tasks is exactly when this runs |
| CloudTrail pages | 1 | If twenty recent changes do not explain it, the twenty-first will not either |

**No static credentials.** `DefaultCredentialsProvider` resolves the task role in ECS and the
developer's profile locally. There is no code path that accepts an access key, and none should be
added.

## Failures become typed evidence gaps

`AwsFailures` maps SDK exceptions onto `EvidenceGap.Reason`. The distinction is the one the whole
investigation rests on — **why** evidence is missing changes what a conclusion is worth:

| AWS condition | Gap reason | What it means for a conclusion |
|---|---|---|
| Timeout | `TIMEOUT` | We do not know; do not infer |
| 403 / `AccessDenied` | `ACCESS_DENIED` | A permissions fault, **not** a symptom of the incident |
| 429 / throttling | `UPSTREAM_ERROR` | We do not know |
| Other service error | `UPSTREAM_ERROR` | We do not know |
| Empty result | *not a gap* | The source answered and had nothing — this **does** support an inference |

Access denied is called out separately on purpose. An investigation that cannot distinguish a
missing IAM statement from a broken service will confidently report the wrong incident.

**AWS message text is never passed through.** Error messages carry ARNs, account ids and request
context that would travel into a model's prompt and into an incident report. The error *code* is
what an investigator needs, and it is safe. A test asserts the leak does not happen.

## The action executor

`AwsRemediationExecutor` is the only code in the project that can change AWS. It is reached only
from inside `RemediationTool`, which has already re-run the policy engine and claimed the action's
fingerprint, so it does not re-implement policy. It does one thing policy cannot:

**It verifies the demo tag against live AWS state.** The policy engine is *given* tags as an
argument. If those came from anywhere untrustworthy the check would be theatre, so the executor
reads them from ECS immediately before acting. A resource that was retagged, or never tagged, is
refused here even though every earlier check passed.

Each operation is small and does exactly one AWS call. There is no generic "call ECS" path, because
a generic path is one refactor away from being a general-purpose AWS capability.

- `RESTART_ECS_TASK` stops **one** task and lets ECS start a replacement. Stopping every task at
  once is an outage, not a remediation.
- `ROLLBACK_DEPLOYMENT` sets `forceNewDeployment`, so rolling back to the currently-running revision
  still replaces the tasks rather than silently doing nothing and being reported as success.
- `DEACTIVATE_DEMO_FAULT` and `RESTORE_SAFE_CONFIGURATION` **throw** here. They belong to the demo
  target service over HTTP, and a silent no-op would be reported to an operator as a successful
  remediation.

## IAM

Two policies in [`infra/iam/`](../infra/iam/), deliberately separate so a deployment can run
read-only by attaching just the first. The action policy's two `Deny` statements matter more than
its `Allow`: an explicit `Deny` cannot be overridden by a later `Allow`, so widening the permitted
actions in future cannot accidentally widen the blast radius. See
[`infra/iam/README.md`](../infra/iam/README.md), which also explains why some statements use `*` —
`GetMetricStatistics` and `LookupEvents` do not support resource-level permissions at all, and
writing a narrower resource on an action that ignores it would look more secure while changing
nothing.

The tag condition in IAM is the **third** independent enforcement of the same rule: the policy
engine checks it in code, the executor re-verifies it against live AWS, and IAM enforces it at a
layer no bug in either can bypass.

## Testing

Mocked SDK clients rather than WireMock. The SDK builds typed responses, so a mock exercises the
mapping and failure handling this code owns without also testing Amazon's HTTP serialisation. No
test reaches the network or needs credentials; anything that would is tagged `aws-live` and excluded
from the default build.

Writing these tests found a real defect: `Datapoint.unitAsString()` is null for metrics CloudWatch
reports without a unit — common for custom metrics — and the adapter passed it straight into a
record that rejects null. That would have been an NPE in the middle of an investigation, and it was
invisible against the simulator, which always supplies a unit.
