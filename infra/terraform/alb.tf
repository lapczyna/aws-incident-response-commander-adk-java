# An optional load balancer, off by default.
#
# An idle Application Load Balancer is about USD 18 per month in eu-west-1 — more than the Fargate
# task behind it, and more than the database next to it. For a stack with one task and one operator,
# the load balancer is not balancing anything; it is buying a stable DNS name and TLS termination.
# Both are worth paying for once there is a demo to show someone, and worth nothing while there is
# not.
#
# Without it, the Commander is reachable on the task's public IP from admin_cidr, and that address
# changes whenever the task is replaced. `terraform output commander_task_ip_hint` says how to find
# the current one.

resource "aws_lb" "commander" {
  count = var.enable_alb ? 1 : 0

  name               = "${local.name}-alb"
  load_balancer_type = "application"
  internal           = false
  subnets            = aws_subnet.public[*].id
  security_groups    = [aws_security_group.alb[0].id]

  # A minute, not the default four. Fewer connections held open on a stack that is torn down
  # often, and a faster `terraform destroy`.
  idle_timeout = 60

  # Off, deliberately: it would stop `terraform destroy` from completing, which is the operation
  # this project most wants to be certain works.
  enable_deletion_protection = false

  drop_invalid_header_fields = true
}

resource "aws_lb_target_group" "commander" {
  count = var.enable_alb ? 1 : 0

  name        = "${local.name}-commander"
  port        = local.commander_port
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.this.id

  health_check {
    path = "/actuator/health/readiness"
    # Readiness rather than plain health: the readiness probe reports down while Flyway is
    # migrating, and a target registered mid-migration serves errors to the first request.
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 30
    timeout             = 5
    matcher             = "200"
  }

  # One task, and no state worth draining. Thirty seconds instead of the default five minutes means
  # a deploy or a destroy does not sit waiting for connections that are not there.
  deregistration_delay = 30
}

# HTTP. Either the only listener, or a redirect to HTTPS when a certificate is supplied.
resource "aws_lb_listener" "commander" {
  count = var.enable_alb ? 1 : 0

  load_balancer_arn = aws_lb.commander[0].arn
  port              = 80
  protocol          = "HTTP"

  dynamic "default_action" {
    for_each = var.certificate_arn == "" ? [1] : []

    content {
      type             = "forward"
      target_group_arn = aws_lb_target_group.commander[0].arn
    }
  }

  dynamic "default_action" {
    for_each = var.certificate_arn == "" ? [] : [1]

    content {
      type = "redirect"

      redirect {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }
}

resource "aws_lb_listener" "commander_https" {
  count = var.enable_alb && var.certificate_arn != "" ? 1 : 0

  load_balancer_arn = aws_lb.commander[0].arn
  port              = 443
  protocol          = "HTTPS"
  certificate_arn   = var.certificate_arn

  # TLS 1.2 minimum. The default policy still permits older suites, and there is no client here
  # that needs them.
  ssl_policy = "ELBSecurityPolicy-TLS13-1-2-2021-06"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.commander[0].arn
  }
}
