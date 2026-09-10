# IAM policies

Two policies, deliberately separate. The read policy is what an investigation needs; the action
policy is what remediation needs. Splitting them means a deployment can run the Commander in
read-only mode by attaching only the first — and that is the sensible way to try it against a real
account for the first time.

`${region}`, `${account_id}`, `${project}`, `${project_tag}` and `${environment}` are substituted by
`infra/terraform/iam.tf`, which renders these files with `templatefile()` rather than restating them
as `aws_iam_policy_document` blocks. There is one copy of each policy, and it is this one.

That arrangement has a failure mode: `templatefile()` fails on a placeholder it was not given a
value for, so adding `${cluster}` to a document here would pass every check in the repository and
then break an apply halfway through creating a VPC. `DeploymentContractTest` reads the substitution
map out of `iam.tf` and fails the Java build if either document uses a placeholder that is not in
it.

## `commander-task-role-read.json`

Read-only. Four statements covering exactly the four signal ports.

The wildcards are not laziness. `cloudwatch:GetMetricStatistics` and `cloudtrail:LookupEvents` do not
support resource-level permissions at all — AWS evaluates them against `*` regardless of what is
written — so narrowing them is expressed in the condition on the ECS statement instead, and by the
log-group ARN pattern. Writing a narrower resource on an action that ignores it would look more
secure while changing nothing, which is worse than being explicit about the limitation.

ECS reads are constrained by `ecs:cluster`, which **is** honoured, so the Commander cannot enumerate
services in any other cluster in the account.

## `commander-task-role-actions.json`

Three statements, and the two `Deny` statements matter more than the `Allow`.

The `Allow` permits only `UpdateService` and `StopTask`, only in the demo cluster, and only on
resources carrying both the project and environment tags. Those tag conditions are the same
constraint the policy engine applies in code and the executor re-verifies against live AWS state —
the third independent enforcement of the same rule, at the layer that cannot be bypassed by a bug in
the other two.

`DenyEverythingOutsideTheDemoCluster` exists because an `Allow` is only as narrow as the next person
to edit it. An explicit `Deny` cannot be overridden by any later `Allow` in any attached policy, so
widening the permitted actions later cannot accidentally widen their blast radius.

`DenyDestructiveOperationsOutright` removes the operations that have no place in this system at all.
`ecs:DeleteService` is not something a remediation should ever need, and there is no configuration
in which the Commander should be able to register a task definition — a rollback targets one that
already exists. Denying them at the IAM layer means a future bug in the action allowlist cannot
reach them.

## What is deliberately absent

No `iam:*`, no `sts:AssumeRole`, no `secretsmanager:PutSecretValue`, no `ec2:*`. The Commander reads
its own configuration and acts on ECS; anything else would be scope it has no use for and an
escalation path it should not have.
