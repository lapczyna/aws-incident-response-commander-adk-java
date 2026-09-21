# Deploying to AWS, and taking it down again

> **Nothing here deploys automatically.** There is no push trigger, no schedule, and no branch that
> can acquire one by accident. Both workflows are `workflow_dispatch` only, both run in a GitHub
> Environment whose approval is a precondition for obtaining AWS credentials at all, and the deploy
> workflow plans unless you explicitly ask it to apply.
>
> **Read [docs/cost.md](cost.md) first.** The default configuration is about **$0.045/hour**, which
> is nothing for an afternoon and about **$33** if you forget about it for a month.

The whole system runs locally against the simulator with no AWS account and no API key. This page is
for when you want it running on real infrastructure — which is worth doing once, to see the IAM
layer refuse something the application had already refused twice.

---

## What gets created

```
VPC 10.20.0.0/16
├── 2 public subnets ──── Fargate tasks, with public IPs   ← no NAT Gateway. See ADR-0011.
│   ├── commander       0.5 vCPU / 1 GB   Spot
│   └── demo-target     0.25 vCPU / 0.5 GB Spot
├── 2 isolated subnets ── RDS PostgreSQL 17, db.t4g.micro, single-AZ, NOT highly available
└── (optional) ALB       off by default

ECR × 2 (immutable tags)  ·  Secrets Manager (RDS-managed credential, and the model key if any)
CloudWatch log groups (3-day retention)  ·  6 alarms  ·  1 dashboard  ·  1 SNS topic  ·  1 Budget
IAM: execution role, Commander task role, demo-target task role with no policies at all
```

The Commander task role carries the **read** policy always and the **action** policy only when
`enable_remediation_actions = true`. That is the outermost of the three independent layers enforcing
the same rule — the policy engine refuses first, the executor re-reads live tags and refuses second,
and IAM simply does not carry the permission.

---

## Prerequisites

- An AWS account you are willing to create billable resources in, and admin access to it **once**,
  for the bootstrap step.
- Terraform ≥ 1.11 (1.15.6 is what CI uses; earlier than 1.11 has no S3-native state locking).
- A GitHub repository, if you want the workflows. A local `terraform apply` works just as well.

---

## Step 1 — bootstrap, once, with admin credentials

This creates the three things the pipeline needs to exist before it can run: the state bucket, the
GitHub OIDC provider, and the deploy role.

```bash
cd infra/terraform/bootstrap
terraform init
terraform apply -var="github_repository=your-account/your-repo"
```

It prints what to do next. In GitHub:

1. **Create the environment.** Settings → Environments → New environment → `aws-demo`. Add yourself
   as a required reviewer.

   This is not optional and not cosmetic. The deploy role's trust policy requires the OIDC subject
   to be exactly `repo:<owner>/<name>:environment:aws-demo`. A workflow that does not run in that
   environment receives no credentials — the approval gate is a precondition for holding
   credentials, not a convention layered on top of them.

2. **Set repository variables** (Settings → Secrets and variables → Actions → Variables):

   | Variable | Value |
   |---|---|
   | `AWS_DEPLOY_ROLE_ARN` | from the bootstrap output |
   | `AWS_REGION` | `eu-west-1` |
   | `TF_STATE_BUCKET` | from the bootstrap output |
   | `TFVARS` | the contents of a tfvars file — at minimum `budget_notification_email = "you@example.com"` |

   None of these are secrets, which is why they are variables. There is no AWS access key anywhere
   in this setup; there is nothing to rotate and nothing to leak.

3. **Confirm the SNS subscription.** Alarms email the same address, and the subscription is pending
   until you click the link. Terraform reports it as created either way, so an unconfirmed address
   is an alarm firing into nothing.

### What the deploy role can and cannot do

Terraform needs broad permissions to build a VPC, a cluster and a database, and there is no honest
way to make that small. What it can be is *bounded*, and `bootstrap/oidc.tf` bounds it three ways:
resource-name scoping where the service supports it, explicit `Deny` statements where it does not,
and nothing at all for services this project does not use.

The `Deny` statements are the interesting half, because an explicit deny cannot be overridden by any
later allow:

| Denied | Why |
|---|---|
| `ec2:CreateNatGateway`, `ec2:CreateVpcEndpoint` | The two ways this architecture could quietly acquire a bill larger than everything else in it. The NAT Gateway is the one someone adds at 2am while debugging egress. |
| `ec2:RunInstances` | This stack is Fargate only. An instance is the classic way to "tear down" a demo and keep paying for it. |
| RDS outside `db.t4g.micro`/`small`, or Multi-AZ | A typo in a tfvars file fails at the IAM layer instead of appearing on the bill. |
| `iam:CreateUser`, `iam:CreateAccessKey`, `iam:UpdateAssumeRolePolicy`, … | A deploy role that can mint a user is one compromised workflow away from owning the account. |
| Attaching any managed policy except the ECS task execution policy | Everything else has to be an inline policy written in this repository, where it shows up in a diff. |
| Deleting the state bucket | So a teardown cannot remove the record of what it is tearing down. |

---

## Step 2 — plan

Actions → **Deploy to AWS** → Run workflow → action: `plan`.

It assumes the role, renders your `TFVARS` into `ci.auto.tfvars`, and posts the plan to the run
summary. It builds no images and creates nothing. Read it. On a first run it is about sixty
resources.

## Step 3 — apply

Same workflow, action: `apply`, and type `deploy` in the confirmation box. Then approve the
environment when GitHub asks.

Three deliberate acts — selecting apply, typing the phrase, approving the environment — which is the
same posture the application takes towards remediation. The model can propose; a human decides; the
decision is bound to something specific.

The workflow then:

1. creates the two ECR repositories with a targeted apply (the one legitimate `-target` in this
   repository: images cannot be pushed before the registries exist, and services cannot start before
   the images are pushed);
2. builds and pushes both images, tagged with the commit SHA, skipping any tag already published;
3. plans, applies, and prints the **safety posture** — the deployment's own answer to whether it can
   change anything:

```json
{
  "signal_source": "simulator",
  "model_profile": "fake",
  "enable_remediation_actions": false,
  "dry_run": true,
  "can_change_aws": false,
  "inbound_access": "none"
}
```

### Deploying from a laptop instead

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars   # edit it
terraform init \
  -backend-config="bucket=<from bootstrap>" \
  -backend-config="key=commander/terraform.tfstate" \
  -backend-config="region=eu-west-1"
terraform apply -var="commander_image_tag=<sha>" -var="demo_target_image_tag=<sha>"
```

You still have to build and push the images yourself, and the ECR repositories still have to exist
first — the same targeted apply the workflow does.

---

## Step 4 — reach it

With `enable_alb = false` (the default) and `admin_cidr` unset, the stack comes up **reachable by
nothing**. That is a working state, not a broken one: investigations start from the alert path, not
from a browser. To reach it, set `admin_cidr` to your own address — never `0.0.0.0/0`, and
especially not while remediation actions are enabled — and use the address from:

```bash
terraform output -raw commander_task_ip_hint    # prints the command that finds the current IP
terraform output console_url                    # non-null only when the ALB is enabled
terraform output dashboard_url
```

The task IP changes every time the task is replaced, which on Spot capacity can be at any time. That
is what the ALB buys, and whether $18/month is worth a stable name is the question `enable_alb`
exists to make you answer.

### Signing in to the console

The operator console is at `/console`. Under the default `identity_profile = "local-identity"` the
three demo identities are `approver`, `responder` and `viewer`, and they share a password Terraform
generated — the application's documented default of `commander` is right for a laptop and wrong for
anything with an address.

```bash
aws secretsmanager get-secret-value \
  --secret-id "$(terraform output -raw console_password_secret_arn)" \
  --query SecretString --output text
```

The value is in the Terraform state file, so treat that state as sensitive. It is deliberately not a
Terraform output: an output appears in every plan log and in any CI artefact that captures one.

For anything longer-lived than a demonstration, set `identity_profile = "oidc"` and point
`spring.security.oauth2.resourceserver` at your issuer. That profile has no local users at all,
creates no password secret, and accepts bearer tokens only — a token carrying no recognised role
authenticates as a viewer, which can read and decide nothing.

### Turning the model on

`model_profile = "fake"` is the default and runs the whole pipeline with scripted responses. For a
real model:

- **`bedrock`** — nothing to configure. The task role gets `bedrock:InvokeModel` scoped to exactly
  one model ARN, and authenticates as itself. Enable Nova Lite in the Bedrock console for your
  region first; model access is per-account and per-region, and the failure without it is
  `AccessDeniedException` on the first call.
- **`gemini`** — Terraform creates an empty secret and never sees the value. Set it yourself:

  ```bash
  aws secretsmanager put-secret-value \
    --secret-id "$(terraform output -raw gemini_secret_arn)" \
    --secret-string "$GEMINI_API_KEY"
  aws ecs update-service --cluster commander --service commander-commander --force-new-deployment
  ```

  The deploy workflow warns if the secret still holds the placeholder. It checks with
  `DescribeSecret` rather than `GetSecretValue` — the deploy role cannot read the value and does not
  need to; the timestamps say whether anyone has ever written one.

  Do not send real incident data through the Gemini free tier. See the data-handling note in the
  README.

### What is not available in the deployed demo

The **connection-pool-pressure scenario**. Locally the demo target service gets its own PostgreSQL
database so that starving its pool cannot starve the investigator; on AWS it runs with no datasource
at all, because a second RDS instance costs more than one of nine scenarios is worth and sharing the
first one would make that scenario lie about what it demonstrates. Everything else, including all
nine simulator scenarios, works identically.

---

## Teardown

**Do this.** The stack bills by the hour, and every design decision in it — no deletion protection,
no final snapshot, no recovery window on the secret, `force_delete` on the registries — exists so
that this runs to completion in a single pass.

Actions → **Destroy the AWS stack** → type `commander` → approve the environment.

```bash
# or, locally
cd infra/terraform
terraform destroy -var="repository=..." -var="commander_image_tag=x" -var="demo_target_image_tag=x"
```

Tick **keep_registries** to leave the ECR repositories in place. That costs about $0.08/month and
saves a full image rebuild on the next deploy; everything that bills by the hour still goes.

### The teardown checklist

The workflow's last step asks the resource tagging API what still carries `Project =
aws-incident-response-commander` and fails loudly if anything does — because "terraform destroy
completed" and "nothing is running" are different claims, and only the second one is about the bill.
Verify by hand if you deployed locally:

```bash
aws resourcegroupstaggingapi get-resources \
  --tag-filters "Key=Project,Values=aws-incident-response-commander" \
  --query 'ResourceTagMappingList[].ResourceARN' --output table
```

| Thing | Destroyed? | Note |
|---|---|---|
| Fargate tasks, ECS cluster | Yes | Billing stops within a minute |
| RDS instance and its storage | Yes | `skip_final_snapshot`, so nothing survives to be billed |
| RDS-managed credential | Yes | Deleted with the instance |
| Model key secret | Yes | `recovery_window_in_days = 0`, so no seven-day soft delete still billing |
| Public IPv4 addresses | Yes | Released with the tasks |
| ALB, if enabled | Yes | No deletion protection, deliberately |
| ECR repositories | Yes, unless you asked to keep them | `force_delete`, so images do not block it |
| CloudWatch log groups | Yes | |
| **State bucket** | **No** | Bootstrap module. A few kilobytes; keeps the record of what existed |
| **Deploy role, OIDC provider** | **No** | Bootstrap module. Costs nothing |
| **AWS Budget** | Yes | Which means the alerts stop too. Consider keeping one by hand |

The last row is worth pausing on: destroying the stack destroys its budget alert. If you deploy
often, create a standing account-level budget outside Terraform so that the thing watching for
forgotten resources is not itself a forgotten resource.

---

## When it does not work

**`AccessDenied` on the assume-role step.** Compare the run's OIDC subject against
`terraform output trusted_subject` from the bootstrap module. It is almost always a job missing
`environment: aws-demo`, or a repository renamed after bootstrap.

**`Error: creating ECR Repository: RepositoryAlreadyExistsException`** on a re-run — a previous
apply partly succeeded. Import it (`terraform import aws_ecr_repository.commander commander/commander`)
or destroy and start again; nothing is holding data yet.

**The service starts and immediately stops.** Look at the log group, not at the ECS events. The two
common causes are Flyway failing to reach the database — check that the task is in the right
security group — and the model profile throwing at start-up, which is deliberate and says exactly
what is missing.

**The deployment circuit breaker rolled back.** The task never passed its health check within the
90-second start period. A cold JVM plus a Flyway migration on a `t4g.micro`-backed database can
exceed it on a first deploy; the second attempt is usually fine, and if it is not, the readiness
endpoint is telling you something real.

**Nothing is reachable.** Expected, until `admin_cidr` is set. See Step 4.

**A task vanished mid-demo.** Spot reclamation. The investigation resumes when the replacement task
starts, which is the property `PostgresSessionService` exists to provide — but if you would rather
it did not happen while someone is watching, set `use_fargate_spot = false`.
