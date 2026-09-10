# Terraform

Two configurations. Apply the first once with admin credentials; everything after that goes through
the second.

| | What it creates | Applied by | Destroyed by |
|---|---|---|---|
| [`bootstrap/`](bootstrap/) | State bucket, GitHub OIDC provider, deploy role | A human, once | Nobody, deliberately |
| this directory | The whole stack | The deploy workflow, or a human | The destroy workflow |

Full walkthrough: [`docs/deployment.md`](../../docs/deployment.md). What it costs and why:
[`docs/cost.md`](../../docs/cost.md). The two decisions that shaped it:
[ADR-0011](../../docs/adr/0011-deployment-topology-and-cost.md).

---

## The files

| File | Contents |
|---|---|
| `versions.tf` | Provider pin and the default tags every resource inherits |
| `backend.tf` | S3 state with native locking. No DynamoDB table; Terraform ≥ 1.11 uses conditional writes |
| `variables.tf` | Every knob, grouped by what it costs you |
| `locals.tf` | The names that have to agree with something else — cluster, log groups, Spring profiles |
| `network.tf` | VPC, public subnets for the tasks, isolated subnets for the database, security groups. **Read the header comment**: the absence of a NAT Gateway is the decision the rest of the file follows from |
| `ecr.tf` | Two registries, immutable tags, five images retained |
| `rds.tf` | PostgreSQL. Single-AZ, and says so |
| `secrets.tf` | The model key container. Terraform never sees the value |
| `iam.tf` | Execution and task roles. The policy documents come from `../iam` via `templatefile()` |
| `ecs.tf` | Cluster, task definitions, services |
| `alb.tf` | Optional load balancer, off by default |
| `observability.tf` | Alarms and a dashboard, restricted to metrics that are free |
| `budget.tf` | The backstop that watches the actual bill |
| `outputs.tf` | Including `safety_posture`, which is worth reading after every apply |

## Three things that are less obvious than they look

**The IAM policies are not written in Terraform.** `iam.tf` renders the JSON documents in
[`../iam`](../iam), because those documents are where the reasoning lives and a second copy
expressed as `aws_iam_policy_document` would be a second thing to keep in step. Terraform substitutes
five placeholders and nothing else — and `DeploymentContractTest` fails the Java build if a policy
ever uses a sixth, because `templatefile()` would otherwise fail halfway through an apply.

**The action policy is attached conditionally.** With `enable_remediation_actions = false` — the
default — the Commander's task role does not carry `ecs:UpdateService` or `ecs:StopTask` at all. The
application still refuses the action twice before that matters, which is the point: three
independent layers, and the one here holds even if both of the others have a bug.

**The cluster name is load-bearing.** Both IAM policies condition on
`arn:aws:ecs:<region>:<account>:cluster/<project>`, so `var.project` is not a naming convention — it
is the boundary drawn around everything the Commander can reach.

## Working on it

```bash
terraform fmt -recursive .
terraform init -backend=false && terraform validate    # no credentials needed
```

CI runs exactly those two on every pull request, in the `Infrastructure` job, which is why it stays
in the tier of checks that need no secrets.
