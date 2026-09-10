# IAM for the running tasks.
#
# The two task policies are not written here. They are the JSON documents in infra/iam, rendered
# with templatefile(), because those documents are the ones with the reasoning attached
# (infra/iam/README.md) and a second copy expressed as aws_iam_policy_document would be a second
# thing to keep in step. Terraform substitutes the five placeholders and nothing else.
#
# The action policy is attached only when var.enable_remediation_actions is true. That is the
# outermost of the three independent layers enforcing the same rule: the policy engine in the
# domain, the executor re-reading live tags before acting, and this — the one that holds even if
# both of the others have a bug, because the credentials simply do not carry the permission.

locals {
  iam_template_vars = {
    region      = var.region
    account_id  = local.account_id
    project     = var.project
    project_tag = var.project_tag
    environment = var.environment
  }
}

data "aws_iam_policy_document" "ecs_task_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    # Without this, any ECS task in any account that can name this role could assume it. The
    # condition pins the trust to tasks in this account, in this cluster.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }

    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = ["arn:aws:ecs:${var.region}:${local.account_id}:*"]
    }
  }
}

# ---------------------------------------------------------------------------------------------
# Execution role: what ECS itself needs to start the task
# ---------------------------------------------------------------------------------------------
#
# Distinct from the task role on purpose. This one pulls images and reads the database secret
# before the container exists; the task role is what the application runs as. Merging them would
# give the application permission to read every secret the platform needs, which is a larger set
# than the application's own.

resource "aws_iam_role" "execution" {
  name               = "${local.name}-execution"
  description        = "ECS agent: pull images, write log streams, inject secrets"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume.json
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secrets" {
  statement {
    sid     = "ReadOnlyTheSecretsThisTaskNeeds"
    effect  = "Allow"
    actions = ["secretsmanager:GetSecretValue"]

    # Enumerated, not wildcarded. The managed policy above grants no secret access at all, and this
    # is the whole of it: the RDS-managed database credential, and the model key if there is one.
    #
    # A splat rather than a conditional, because a splat over a resource with count = 0 is an empty
    # list while an index into one is an error.
    resources = concat(
      [aws_db_instance.this.master_user_secret[0].secret_arn],
      aws_secretsmanager_secret.gemini_api_key[*].arn,
    )
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "read-task-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets.json
}

# ---------------------------------------------------------------------------------------------
# Task role: what the Commander runs as
# ---------------------------------------------------------------------------------------------

resource "aws_iam_role" "commander_task" {
  name               = "${local.name}-task"
  description        = "The Incident Commander application"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume.json
}

resource "aws_iam_role_policy" "commander_read" {
  name = "incident-signals-read"
  role = aws_iam_role.commander_task.id

  policy = templatefile("${path.module}/../iam/commander-task-role-read.json", local.iam_template_vars)
}

resource "aws_iam_role_policy" "commander_actions" {
  count = var.enable_remediation_actions ? 1 : 0

  name = "remediation-actions"
  role = aws_iam_role.commander_task.id

  policy = templatefile("${path.module}/../iam/commander-task-role-actions.json", local.iam_template_vars)
}

# Bedrock, scoped to one model.
#
# Foundation-model ARNs carry no account id, which is not a mistake in this string: the model is
# Amazon's, and the account appears only on inference profiles and custom models. Scoping to the
# exact model id means selecting a more expensive model requires editing infrastructure, not
# editing an environment variable.
data "aws_iam_policy_document" "bedrock_invoke" {
  count = var.model_profile == "bedrock" ? 1 : 0

  statement {
    sid    = "InvokeExactlyOneModel"
    effect = "Allow"

    actions = [
      "bedrock:InvokeModel",
      "bedrock:InvokeModelWithResponseStream",
    ]

    resources = ["arn:aws:bedrock:${var.region}::foundation-model/${var.bedrock_model_id}"]
  }
}

resource "aws_iam_role_policy" "bedrock_invoke" {
  count = var.model_profile == "bedrock" ? 1 : 0

  name   = "invoke-bedrock-model"
  role   = aws_iam_role.commander_task.id
  policy = data.aws_iam_policy_document.bedrock_invoke[0].json
}

# ---------------------------------------------------------------------------------------------
# Task role: the demo target service
# ---------------------------------------------------------------------------------------------
#
# It gets a role with no policies attached at all. It needs no AWS API access whatsoever — it
# serves HTTP and misbehaves on request — and giving it a role is only so that its identity is
# distinct from the Commander's in CloudTrail. A service designed to fail should not be able to
# call anything while failing.

resource "aws_iam_role" "demo_target_task" {
  count = var.enable_demo_target ? 1 : 0

  name               = "${local.name}-demo-target-task"
  description        = "The fault-injectable target service. No policies, deliberately."
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume.json
}
