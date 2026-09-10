# What this costs

Two budgets, enforced in two places, bounding two different things. Conflating them is the most
common way to be wrong about the cost of an AI system, so they are separated here before anything
else:

| | **LLM spend** | **AWS infrastructure** |
|---|---|---|
| Bounded by | `CostGuard`, in the application | AWS Budgets, plus every default in the Terraform |
| Ceiling | `commander.model.monthly-budget-usd`, default **$10/month** | `monthly_budget_usd`, default **$40/month** |
| Behaviour at the limit | Fails closed. No further model calls. | Sends an email. **AWS does not stop anything.** |
| Covers | Tokens sent to Gemini or Bedrock | Fargate, RDS, IPv4 addresses, logs, everything else |

The $10 figure in the README has always been about the first column. It says nothing about the
second, and the second is the larger number.

> **The AWS budget is an alarm, not a brake.** There is no mechanism in this stack that stops
> resources when the budget is exceeded, because the only mechanisms AWS offers for that are
> destructive and act on the whole account. The control that actually works is
> [teardown](deployment.md#teardown), and the forecast alert exists to remind you to use it.

---

## The bill, itemised

Prices are **eu-west-1 list prices at the time of writing**, and they change. Everything below is
the default configuration: Fargate Spot, no load balancer, both services running, a single-AZ
`db.t4g.micro`.

| Line item | Hourly | Monthly (730 h) | Notes |
|---|---|---|---|
| Commander task — 0.5 vCPU, 1 GB, Spot | $0.0085 | $6.22 | On-demand would be $20.72 |
| Demo target task — 0.25 vCPU, 0.5 GB, Spot | $0.0043 | $3.11 | On-demand would be $10.36 |
| RDS `db.t4g.micro`, single-AZ | $0.0180 | $13.14 | The largest single line |
| RDS storage, 20 GB gp3 | $0.0032 | $2.30 | The gp3 minimum; the schema uses a fraction of it |
| **Public IPv4 addresses × 2** | $0.0100 | $7.30 | $0.005/hour each. The price of having no NAT Gateway |
| Secrets Manager — the RDS-managed credential | $0.0005 | $0.40 | |
| ECR storage — ~0.8 GB of images | — | $0.08 | Lifecycle policy keeps five |
| CloudWatch Logs — ingest and 3-day storage | — | ~$0.50 | Depends entirely on how much you run |
| CloudWatch alarms × 6 | — | $0.00 | Within the ten-alarm free tier |
| Dashboard × 1 | — | $0.00 | First three are free |
| AWS Budgets × 1 | — | $0.00 | First two are free |
| SNS email notifications | — | $0.00 | Within the free tier |
| **Total** | **~$0.045** | **~$33** | |

### What that means in practice

| Scenario | Cost |
|---|---|
| A 30-minute demo, torn down afterwards | **~$0.02** |
| An afternoon — deployed at 13:00, destroyed at 18:00 | **~$0.22** |
| Left running for a week | **~$7.50** |
| Left running for a month | **~$33** |
| Left running for a month **with the ALB** | **~$51** |
| Left running for a month **on on-demand Fargate** | **~$55** |

The interesting row is the first one: the demo is essentially free, and the entire cost of this
stack is a function of how long it stays up after you stop looking at it. That is why the teardown
workflow exists, why nothing has deletion protection, and why the budget's forecast alert is set to
fire a week before the month ends rather than after.

---

## The five decisions that produced that number

### 1. No NAT Gateway — saves ~$28/month net

A NAT Gateway is **$0.048/hour ($35/month) in eu-west-1** before a single byte, plus $0.048 per GB
processed. It is more expensive than everything else in this stack put together.

Fargate tasks therefore run in **public subnets with public IPs**, which costs $0.005/hour per
address — $7.30/month for the two of them. Net saving: about $28/month.

The alternative that keeps tasks private is interface VPC endpoints: ECR API, ECR DKR, CloudWatch
Logs, Secrets Manager, and Bedrock, at roughly $7.20/month each, plus an S3 gateway endpoint (free).
That is about **$36/month** — more than the NAT Gateway it was avoiding.

The cost of the choice is not financial. It is that the tasks have routable addresses, and the only
thing between them and the internet is a security group. This is why `admin_cidr` defaults to empty
and creates no ingress rule at all. See [ADR-0011](adr/0011-deployment-topology-and-cost.md).

### 2. Fargate Spot — saves ~$21/month

Spot capacity is roughly 70% cheaper and can be reclaimed with two minutes' notice. For most
workloads that is a real availability trade; here it is close to free, and for a reason specific to
this system: **incident state is durable in PostgreSQL and ADK sessions are resumable**. A reclaimed
task restarts and picks the investigation back up, including one waiting mid-approval — the property
`PostgresSessionService` exists to provide, and the one `ResumeDiagnosticTest` covers.

A Spot interruption during a live demo is still an interruption. `use_fargate_spot = false` if that
matters more than $21.

### 3. Single-AZ RDS — saves ~$13/month

Multi-AZ doubles the instance cost. **This database is not highly available and the configuration
says so out loud.** One day of backups, no deletion protection, no final snapshot. If this were
real, `rds.tf` is the first file that would change.

Cheaper alternatives that were considered and rejected: PostgreSQL as a second Fargate task (~$3/mo,
but the storage is ephemeral, which contradicts the durability the whole project is built on), and
Aurora Serverless v2 (a 0.5 ACU floor is about $44/month — more expensive, not less).

New AWS accounts get 750 hours of `db.t3.micro` free for twelve months, which removes this line
entirely for a year.

### 4. No Container Insights — saves ~$5-15/month

Container Insights bills per metric and per log line ingested, and on a two-task cluster it can
easily cost more than the tasks. The dashboard uses the free `AWS/ECS` and `AWS/RDS` metrics
instead.

The gap this leaves is honest and stated in `observability.tf`: there is **no alarm for "the
Commander is not running"**, because task-count metrics come from Container Insights. The
application-level metrics that actually matter here — investigation duration, token spend, policy
refusals, approvals waiting — are Micrometer metrics on `/actuator/prometheus`, and publishing them
to CloudWatch would be billed per custom metric per month.

### 5. No load balancer by default — saves ~$18/month

An idle ALB is $0.0252/hour in eu-west-1. With one task and one operator it is not balancing
anything; it is buying a stable DNS name and TLS termination. Turn it on (`enable_alb = true`) when
there is someone to show, and supply `certificate_arn` so the listener is HTTPS rather than plain
HTTP over the internet.

---

## Model spend, for completeness

The second column of the first table. The documented workload is **50 incidents per month at
roughly 120k input and 15k output tokens each** — four parallel evidence-gathering agents, a bounded
critique loop, a remediation proposal and a narrated report, per incident:

| Profile | Per incident | 50 incidents/month |
|---|---|---|
| `fake` | $0 | $0 |
| `ollama` | $0 | $0 (electricity, which `CostGuard` cannot see and does not pretend to) |
| `bedrock` — Nova Lite at $0.06/$0.24 per M tokens | $0.0108 | **$0.54** |
| `gemini` — Flash Lite at $0.10/$0.40 per M tokens | $0.0180 | $0.90 |

`CostGuardTest` computes the Bedrock figure from the same `ModelPricing` record the runtime uses, so
the number in this table cannot drift away from the number the guard enforces. That test exists
because an earlier version of this documentation said $0.42 and the arithmetic said $0.54.

`RunConfig.maxLlmCalls` is 20 per investigation and `LlmAgent.maxSteps` is 12, so a looping agent
hits a ceiling long before it reaches the budget. Neither is the primary control — the fake model
being the CI default is.

---

## Things this page cannot tell you

**Data transfer.** Egress to the internet is $0.09/GB after the first 100 GB per month, which is
free. Pulling images from ECR into Fargate in the same region is free. Whether *your* demo transfers
anything meaningful depends on how much traffic you generate against the target service, and
`scripts/generate-traffic.sh --rps 5` for an hour is measured in megabytes.

**The first-month effect.** Accounts under twelve months old have free-tier allowances that make
several of these lines disappear. Do not plan around them; they expire.

**Whether the prices above are still current.** They were list prices for eu-west-1 when this was
written. Check the calculator before committing to anything, and treat this page as an argument
about proportions rather than a quotation.
