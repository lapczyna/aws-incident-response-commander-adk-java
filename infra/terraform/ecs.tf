# The cluster and the two services.
#
# One task each, no autoscaling. Autoscaling on a demo is a mechanism for spending money while
# nobody is watching, and the Commander is a workflow engine rather than a request-serving tier:
# a second replica would not make an investigation faster, it would make two of them.

resource "aws_ecs_cluster" "this" {
  # The cluster name is load-bearing. Both IAM policies condition on
  # arn:aws:ecs:<region>:<account>:cluster/<project>, so this name is the boundary the deny
  # statement draws around everything the Commander is able to touch.
  name = local.name

  setting {
    name  = "containerInsights"
    value = var.enable_container_insights ? "enabled" : "disabled"
  }
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = local.capacity_provider
    weight            = 1
  }
}

resource "aws_cloudwatch_log_group" "commander" {
  name              = local.commander_log_group
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_log_group" "demo_target" {
  count = var.enable_demo_target ? 1 : 0

  name              = local.demo_target_log_group
  retention_in_days = var.log_retention_days
}

# ---------------------------------------------------------------------------------------------
# Incident Commander
# ---------------------------------------------------------------------------------------------

locals {
  # Every environment variable the deployed Commander reads, in one place, so that what the
  # container believes about itself can be compared against what IAM will actually permit.
  #
  # The three switches that stand between this deployment and a production change are visible here
  # as three separate values: signal_source decides whether AWS is read at all,
  # enable_remediation_actions decides whether the task role can act, and dry_run decides whether
  # an approved action is executed or simulated. All three default to the safe setting, and each
  # has to be turned off on purpose.
  commander_environment = [
    { name = "SPRING_PROFILES_ACTIVE", value = local.spring_profiles },
    { name = "COMMANDER_DB_URL", value = "jdbc:postgresql://${aws_db_instance.this.endpoint}/${aws_db_instance.this.db_name}" },
    { name = "COMMANDER_DB_USER", value = aws_db_instance.this.username },
    { name = "AWS_REGION", value = var.region },
    { name = "COMMANDER_AWS_ACCOUNT_ID", value = local.account_id },
    { name = "COMMANDER_ENVIRONMENT", value = var.environment },
    { name = "COMMANDER_ACTIONS_ENABLED", value = tostring(var.enable_remediation_actions) },
    { name = "COMMANDER_DRY_RUN", value = tostring(var.dry_run) },
    { name = "COMMANDER_MODEL_BUDGET_USD", value = format("%.2f", var.model_monthly_budget_usd) },
  ]

  # Nothing else is set here. Every name above is read by a property in application.yaml; a
  # variable the application does not read is a promise the infrastructure cannot keep, and the
  # obvious candidate — an address for the demo target service — is missing precisely because
  # there is no service discovery in this VPC to give it a meaningful value.

  commander_secrets = concat(
    [
      {
        name = "COMMANDER_DB_PASSWORD"
        # The JSON key selector. RDS stores {"username":...,"password":...}; the trailing empty
        # fields are the version-id and version-stage slots, left blank so ECS resolves the
        # current version.
        valueFrom = "${aws_db_instance.this.master_user_secret[0].secret_arn}:password::"
      }
    ],
    # Iterating a splat rather than indexing under a conditional: with no gemini secret the splat is
    # an empty list, where an index would be an error.
    [
      for arn in aws_secretsmanager_secret.gemini_api_key[*].arn : {
        name      = "GEMINI_API_KEY"
        valueFrom = arn
      }
    ],
    # Present only under local-identity; under oidc there are no local users to hold a password for.
    [
      for arn in aws_secretsmanager_secret.console_password[*].arn : {
        name      = "COMMANDER_DEMO_PASSWORD"
        valueFrom = arn
      }
    ]
  )
}

resource "aws_ecs_task_definition" "commander" {
  family                   = "${local.name}-commander"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"

  # 0.5 vCPU / 1 GB. A JVM running Spring Boot and ADK does not fit comfortably in 512 MB, and an
  # OOM-killed investigator is a worse economy than the USD 0.01 per hour this costs.
  cpu    = "512"
  memory = "1024"

  execution_role_arn = aws_iam_role.execution.arn
  task_role_arn      = aws_iam_role.commander_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name      = "commander"
      image     = "${aws_ecr_repository.commander.repository_url}:${var.commander_image_tag}"
      essential = true

      portMappings = [
        {
          containerPort = local.commander_port
          protocol      = "tcp"
        }
      ]

      environment = local.commander_environment
      secrets     = local.commander_secrets

      # Readiness rather than liveness: Flyway runs at start-up, and a container reported healthy
      # before the schema is migrated would be added to the load balancer and then fail every
      # request. The start period covers JVM start plus migration.
      healthCheck = {
        command     = ["CMD-SHELL", "wget -q -O - http://localhost:${local.commander_port}/actuator/health/readiness || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 90
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.commander.name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "commander"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "commander" {
  name            = "${local.name}-commander"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.commander.arn
  desired_count   = 1

  capacity_provider_strategy {
    capacity_provider = local.capacity_provider
    weight            = 1
  }

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.commander.id]
    assign_public_ip = true # No NAT Gateway. See network.tf.
  }

  # A single task, replaced rather than doubled. minimum_healthy_percent = 0 means there is a gap
  # of a minute or so during a deploy, which is the correct trade for never paying for two tasks.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 200

  # Circuit breaker with rollback: a task definition that cannot pass its health check is rolled
  # back automatically instead of leaving the service retrying a broken image and billing for it.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  dynamic "load_balancer" {
    for_each = var.enable_alb ? [1] : []

    content {
      target_group_arn = aws_lb_target_group.commander[0].arn
      container_name   = "commander"
      container_port   = local.commander_port
    }
  }

  # Two orderings the reference graph does not imply. Without the listener, the service tries to
  # register with a target group nothing is listening on and the first apply fails on a race the
  # second apply hides. Without the capacity-provider association, a service cannot ask for
  # FARGATE_SPOT at all.
  depends_on = [
    aws_lb_listener.commander,
    aws_ecs_cluster_capacity_providers.this,
  ]

  # The task definition is updated by this configuration, but the running revision is also changed
  # by the deploy workflow. Ignoring desired_count keeps a manual `aws ecs update-service
  # --desired-count 0` — the cheapest way to pause a demo — from being reverted by the next apply.
  lifecycle {
    ignore_changes = [desired_count]
  }
}

# ---------------------------------------------------------------------------------------------
# Demo target service
# ---------------------------------------------------------------------------------------------

resource "aws_ecs_task_definition" "demo_target" {
  count = var.enable_demo_target ? 1 : 0

  family                   = "${local.name}-demo-target"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"

  # The smallest Fargate size there is. This service exists to be slow on request; it does not need
  # headroom, and the CPU-pressure fault is clamped well below what would starve it.
  cpu    = "256"
  memory = "512"

  execution_role_arn = aws_iam_role.execution.arn
  task_role_arn      = aws_iam_role.demo_target_task[0].arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name      = "demo-target"
      image     = "${aws_ecr_repository.demo_target.repository_url}:${var.demo_target_image_tag}"
      essential = true

      portMappings = [
        {
          containerPort = local.demo_target_port
          protocol      = "tcp"
        }
      ]

      # Deliberately not the `demo` Spring profile. That profile also configures a datasource, and
      # this deployment gives the target service no database: sharing the Commander's instance
      # would make the connection-pool-pressure scenario starve the investigator along with the
      # investigated, and a second RDS instance costs more than that one scenario is worth. Faults
      # are switched on directly instead, and the pool-pressure fault is unavailable here.
      environment = [
        { name = "DEMO_FAULTS_ENABLED", value = "true" },
        { name = "SPRING_PROFILES_ACTIVE", value = "default" },
      ]

      healthCheck = {
        command = ["CMD-SHELL", "wget -q -O - http://localhost:${local.demo_target_port}/actuator/health/liveness || exit 1"]
        # Liveness, not readiness, and this is the one place that distinction is inverted on
        # purpose: an injected fault is supposed to make this service unhealthy-looking, and a
        # readiness-based check would have ECS kill and replace the very task the incident is
        # about.
        interval    = 30
        timeout     = 5
        retries     = 5
        startPeriod = 60
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.demo_target[0].name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "demo-target"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "demo_target" {
  count = var.enable_demo_target ? 1 : 0

  name            = "${local.name}-demo-target"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.demo_target[0].arn
  desired_count   = 1

  capacity_provider_strategy {
    capacity_provider = local.capacity_provider
    weight            = 1
  }

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.demo_target.id]
    assign_public_ip = true
  }

  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  depends_on = [aws_ecs_cluster_capacity_providers.this]

  # This is the service the Commander is permitted to restart and scale, so it carries the two tags
  # that permission is conditioned on. Without them the IAM policy denies the action, the policy
  # engine refuses the proposal, and the executor refuses again after reading the live tags — which
  # is the intended behaviour if someone ever removes them.
  tags = {
    Project     = var.project_tag
    Environment = var.environment
  }

  lifecycle {
    ignore_changes = [desired_count]
  }
}
