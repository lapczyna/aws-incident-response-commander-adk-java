data "aws_caller_identity" "current" {}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name       = var.project
  account_id = data.aws_caller_identity.current.account_id

  # Two AZs, and no more. RDS requires a subnet group spanning at least two availability zones even
  # for a single-AZ instance, so two is the minimum the stack can be built with — and subnets
  # themselves cost nothing. The instance still runs in one AZ; see rds.tf.
  azs = slice(data.aws_availability_zones.available.names, 0, 2)

  # Spring profiles, assembled from the two independent choices rather than from one deployment
  # flag. The model profile decides what the reasoning costs; the signal profile decides whether
  # anything is read from AWS at all.
  spring_profiles = join(",", [var.model_profile, var.signal_source])

  # Log-group names match the /aws/ecs/${project}-* pattern that the read IAM policy is scoped to.
  # If these diverge, the Commander loses the ability to read its own logs and the policy silently
  # grants nothing; keeping the pattern in one place is what stops that.
  commander_log_group   = "/aws/ecs/${local.name}-commander"
  demo_target_log_group = "/aws/ecs/${local.name}-demo-target"

  cluster_arn = "arn:aws:ecs:${var.region}:${local.account_id}:cluster/${local.name}"

  # Fargate Spot is the default and on-demand is the fallback, expressed once so both services
  # cannot drift apart. Weight, not base: with base = 0 on a single-task service, ECS places the
  # task on whichever provider the strategy names.
  capacity_provider = var.use_fargate_spot ? "FARGATE_SPOT" : "FARGATE"

  # The demo target is reached over HTTP by the fault tools, so the Commander needs to be able to
  # name it. Service discovery would be another billed resource; the private IP of a single task is
  # resolvable through ECS itself, and for the demo the ALB or public IP is enough.
  demo_target_port = 8081
  commander_port   = 8080
}
