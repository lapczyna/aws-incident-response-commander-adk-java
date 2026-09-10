# GitHub Actions, with no stored AWS credentials anywhere.
#
# There is no access key in a repository secret, because there is no access key. GitHub mints a
# short-lived OIDC token for the workflow run, AWS validates it against the provider below, and STS
# issues credentials that expire with the job. Nothing to rotate, nothing to leak, and a stolen
# repository secret buys an attacker nothing because there isn't one.
#
# The trust policy is where this either works or is theatre. Three conditions, all required:
#
#   1. audience is sts.amazonaws.com
#   2. subject is exactly repo:<owner>/<name>:environment:<environment>
#   3. ... and nothing else. No wildcard on the repository, no `repo:owner/*`.
#
# Condition 2 is the one people get wrong. Binding to a branch (`ref:refs/heads/main`) means any
# workflow on that branch can deploy, including one added by a pull request that was merged without
# anyone reading the workflow file. Binding to a GitHub Environment means the run must pass through
# that environment's protection rules first — its required reviewers, in particular — before the
# token is even issued. The manual approval stops being a convention and becomes a precondition for
# holding credentials at all.

data "aws_caller_identity" "current" {}

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]

  # No thumbprint_list at all. AWS has validated this provider's certificate chain against its own
  # trust store since 2023, and a pinned thumbprint is a value that expires without warning and
  # takes the deployment pipeline with it when it does.
}

data "aws_iam_policy_document" "github_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:environment:${var.github_environment}"]
    }
  }
}

resource "aws_iam_role" "deploy" {
  name        = "${var.project}-deploy"
  description = "Assumed by GitHub Actions through OIDC. Scoped by resource name prefix, and denied the expensive things outright."

  assume_role_policy = data.aws_iam_policy_document.github_assume.json

  # An hour. Long enough for a terraform apply that creates an RDS instance, short enough that a
  # leaked credential is a narrow window rather than a standing one.
  max_session_duration = 3600
}

# ---------------------------------------------------------------------------------------------
# What the deploy role may do
# ---------------------------------------------------------------------------------------------
#
# Terraform needs broad permissions to build a VPC, a cluster, a database and their IAM roles;
# there is no honest way to make this small. What can be done is to make it *bounded*, and the
# bounds here are of three kinds:
#
#   - resource-name scoping wherever the service supports it (IAM, Secrets Manager, S3);
#   - explicit Deny statements for the operations that would be expensive or dangerous;
#   - nothing at all for the services this project does not use.
#
# An explicit Deny cannot be overridden by any later Allow, so the second kind survives someone
# widening the first kind later.

data "aws_iam_policy_document" "deploy" {
  statement {
    sid    = "BuildTheStack"
    effect = "Allow"

    # These services do not support resource-level permissions for the create and describe calls
    # Terraform makes, so scoping is expressed in the Deny statements below rather than pretended
    # at here. Writing a narrow-looking Resource on an action that ignores it is worse than saying
    # so: it reads as a control and enforces nothing.
    actions = [
      "ec2:*",
      "ecs:*",
      "ecr:*",
      "rds:*",
      "logs:*",
      "cloudwatch:*",
      "sns:*",
      "budgets:*",
      "elasticloadbalancing:*",
      "application-autoscaling:*",
    ]

    resources = ["*"]
  }

  statement {
    sid    = "ReadIdentityAndPricing"
    effect = "Allow"

    actions = [
      "sts:GetCallerIdentity",
      "iam:ListOpenIDConnectProviders",
      "ce:GetCostAndUsage",
      "pricing:GetProducts",
      # The destroy workflow's last step asks the tagging API what still carries the project tag.
      # A teardown that reports success without checking is how a demo becomes a subscription.
      "tag:GetResources",
    ]

    resources = ["*"]
  }

  # IAM, scoped by name. The deploy role can manage the roles this stack creates and no others,
  # which is what stops a compromised workflow from granting itself administrator.
  statement {
    sid    = "ManageOnlyThisStacksRoles"
    effect = "Allow"

    actions = [
      "iam:CreateRole",
      "iam:DeleteRole",
      "iam:GetRole",
      "iam:UpdateRole",
      "iam:TagRole",
      "iam:UntagRole",
      "iam:ListRoleTags",
      "iam:PutRolePolicy",
      "iam:DeleteRolePolicy",
      "iam:GetRolePolicy",
      "iam:ListRolePolicies",
      "iam:AttachRolePolicy",
      "iam:DetachRolePolicy",
      "iam:ListAttachedRolePolicies",
      "iam:ListInstanceProfilesForRole",
      "iam:PassRole",
      "iam:CreateServiceLinkedRole",
    ]

    resources = [
      "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${var.project}-*",
      "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/aws-service-role/*",
    ]
  }

  statement {
    sid    = "ManageOnlyThisStacksSecrets"
    effect = "Allow"

    actions = [
      "secretsmanager:CreateSecret",
      "secretsmanager:DeleteSecret",
      "secretsmanager:DescribeSecret",
      "secretsmanager:TagResource",
      "secretsmanager:UntagResource",
      "secretsmanager:UpdateSecret",
      "secretsmanager:PutSecretValue",
      "secretsmanager:GetResourcePolicy",
      "secretsmanager:ListSecretVersionIds",
    ]

    resources = ["arn:aws:secretsmanager:${var.region}:${data.aws_caller_identity.current.account_id}:secret:${var.project}/*"]
  }

  # Reading a secret value is not on that list. The deploy role creates the container for the model
  # key and never needs to look inside it; the workflow checks whether the placeholder is still
  # there using DescribeSecret and VersionIdsToStages, which reveals that a value was set without
  # revealing the value.
  statement {
    sid    = "ListSecretsForThePlaceholderCheck"
    effect = "Allow"

    actions   = ["secretsmanager:ListSecrets"]
    resources = ["*"]
  }

  statement {
    sid    = "TerraformState"
    effect = "Allow"

    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:ListBucket",
      "s3:GetBucketLocation",
    ]

    resources = [
      aws_s3_bucket.state.arn,
      "${aws_s3_bucket.state.arn}/*",
    ]
  }

  # -------------------------------------------------------------------------------------------
  # Denies
  # -------------------------------------------------------------------------------------------

  # The cost guardrail. NAT Gateways, VPC endpoints and Global Accelerator are the three ways this
  # architecture could quietly acquire a monthly bill larger than everything else in it, and the
  # first is the one someone adds while debugging egress at two in the morning. Denying it here
  # means that debugging session ends with a decision rather than with a charge.
  statement {
    sid    = "DenyTheExpensiveNetworking"
    effect = "Deny"

    actions = [
      "ec2:CreateNatGateway",
      "ec2:CreateVpcEndpoint",
      "ec2:AcceptTransitGatewayVpcAttachment",
      "ec2:CreateTransitGateway",
      "globalaccelerator:*",
      "ec2:PurchaseReservedInstancesOffering",
      "ec2:PurchaseHostReservation",
      "ec2:AllocateHosts",
      "savingsplans:CreateSavingsPlan",
    ]

    resources = ["*"]
  }

  # No EC2 instances. This stack is Fargate only, and an instance is the classic way to end up
  # paying for a demo that was "torn down" — the cluster went, the instance stayed.
  statement {
    sid    = "DenyEc2Instances"
    effect = "Deny"

    actions = [
      "ec2:RunInstances",
      "ec2:StartInstances",
    ]

    resources = ["*"]
  }

  # RDS, bounded by class and by shape. rds:DatabaseClass and rds:MultiAz are honoured condition
  # keys, so this is enforcement rather than documentation.
  statement {
    sid    = "DenyLargeOrMultiAzDatabases"
    effect = "Deny"

    actions = [
      "rds:CreateDBInstance",
      "rds:ModifyDBInstance",
      "rds:CreateDBCluster",
      "rds:RestoreDBInstanceFromDBSnapshot",
    ]

    resources = ["*"]

    condition {
      # IfExists, so that a ModifyDBInstance call which does not mention a class is not denied for
      # failing to mention it. A create always names one, which is the case this is guarding.
      test     = "StringNotEqualsIfExists"
      variable = "rds:DatabaseClass"
      values   = var.allowed_db_instance_classes
    }
  }

  statement {
    sid    = "DenyMultiAz"
    effect = "Deny"

    actions   = ["rds:CreateDBInstance", "rds:ModifyDBInstance"]
    resources = ["*"]

    condition {
      test     = "BoolIfExists"
      variable = "rds:MultiAz"
      values   = ["true"]
    }
  }

  # Identity escalation, closed off entirely. A deploy role that can create a user, mint an access
  # key, or attach AdministratorAccess is a deploy role that is one compromised workflow away from
  # owning the account — and none of these are things Terraform needs here.
  statement {
    sid    = "DenyIdentityEscalation"
    effect = "Deny"

    actions = [
      "iam:CreateUser",
      "iam:CreateAccessKey",
      "iam:CreateLoginProfile",
      "iam:UpdateLoginProfile",
      "iam:CreateOpenIDConnectProvider",
      "iam:UpdateAssumeRolePolicy",
      "iam:CreateSAMLProvider",
      "iam:DeleteOpenIDConnectProvider",
      "iam:PutUserPolicy",
      "iam:AttachUserPolicy",
      "iam:CreatePolicyVersion",
      "organizations:*",
      "account:*",
    ]

    resources = ["*"]
  }

  statement {
    sid    = "DenyAttachingAdministratorPolicies"
    effect = "Deny"

    actions   = ["iam:AttachRolePolicy"]
    resources = ["*"]

    # The one managed policy this stack legitimately attaches is the ECS task execution role
    # policy. Anything else has to be an inline policy written in this repository, where it can be
    # read in a diff.
    condition {
      test     = "ArnNotEquals"
      variable = "iam:PolicyARN"
      values   = ["arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"]
    }
  }

  # The state bucket cannot be deleted by the pipeline that depends on it. This is not primarily a
  # security control; it is what stops a destroy workflow from removing the record of what it
  # destroyed halfway through destroying it.
  statement {
    sid    = "DenyTouchingTheStateBucketItself"
    effect = "Deny"

    actions = [
      "s3:DeleteBucket",
      "s3:PutBucketPolicy",
      "s3:PutBucketVersioning",
      "s3:PutBucketPublicAccessBlock",
    ]

    resources = [aws_s3_bucket.state.arn]
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "terraform-deploy"
  role   = aws_iam_role.deploy.id
  policy = data.aws_iam_policy_document.deploy.json
}
