# Bootstrap

Applied once, by a human, with admin credentials. Creates the three things that have to exist before
the pipeline can run at all:

- the **S3 bucket** holding the main configuration's state;
- the **GitHub OIDC provider**, so Actions can authenticate without a stored key;
- the **deploy role** that Actions assumes.

```bash
terraform init
terraform apply -var="github_repository=your-account/your-repo"
```

The output says what to set in GitHub. Then see [`docs/deployment.md`](../../../docs/deployment.md).

## Why this is a separate configuration with local state

It creates the bucket that the main configuration's state lives in, so it cannot store its own state
there, and inventing a second bucket to break the circularity only moves the problem one level up.
Its state file describes a bucket and two IAM resources, holds no credentials, and re-creating it
from empty is an import rather than a rebuild.

## Why nothing destroys it

The destroy workflow tears down everything that bills by the hour. It deliberately does not touch
this: the bucket holds a few kilobytes and the record of what was created, and the role and provider
cost nothing at all. Removing them would also remove the credentials needed to run a teardown again.

If you really want them gone: `terraform destroy` here, after emptying the bucket by hand — it has
`force_destroy = false` on purpose.

## The two halves of the deploy role

`oidc.tf` is worth reading in full, but the shape is:

**The trust policy** requires the OIDC subject to be exactly
`repo:<owner>/<name>:environment:aws-demo`. Not a branch. Binding to `ref:refs/heads/main` — the
usual example — means any workflow on main can deploy; binding to a GitHub Environment means the
run must clear that environment's required reviewers *before the token is issued*. The approval
becomes a precondition for holding credentials rather than a step someone can route around. See
[ADR-0011](../../../docs/adr/0011-deployment-topology-and-cost.md).

**The permissions policy** is broad in its allows — Terraform genuinely needs to create a VPC, a
cluster and a database — and narrow in three specific ways: IAM and Secrets Manager are scoped to
the project's name prefix, expensive resources are denied outright (`ec2:CreateNatGateway`,
`ec2:RunInstances`, RDS classes outside the small Graviton pair, Multi-AZ), and every identity
escalation path is closed (`iam:CreateUser`, `iam:CreateAccessKey`, `iam:UpdateAssumeRolePolicy`,
attaching any managed policy other than the ECS task execution one). An explicit `Deny` cannot be
overridden by a later `Allow`, so those hold even if someone widens the allows.

The obvious next step, and the one thing deliberately left out: a **permissions boundary** requiring
every role this stack creates to itself be limited. It is the right control and it is untested
against a real account, and an untested boundary that blocks every apply is worse than a
correct-but-broader policy. Anyone running this for real should add it first.
