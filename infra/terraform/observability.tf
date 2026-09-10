# Alarms and a dashboard, restricted to metrics that are free.
#
# Everything below reads the AWS/ECS and AWS/RDS metrics that are published whether or not anyone
# asks for them. Nothing here turns on Container Insights, custom metrics, or Enhanced Monitoring:
# those are billed per metric per month, and an alarm that costs more than the resource it watches
# is an odd sort of vigilance. The first three CloudWatch dashboards on an account are free, which
# is why there is exactly one.
#
# This is a smaller set than a real service would have, and the gap is deliberate rather than
# forgotten. In particular there is no alarm on "the Commander is not running": task-count metrics
# come from Container Insights, and the honest way to notice here is the health check plus the
# service event log.

resource "aws_sns_topic" "alerts" {
  name = "${local.name}-alerts"
}

resource "aws_sns_topic_subscription" "alerts_email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.budget_notification_email

  # The subscription is pending until the address confirms it. Terraform reports it as created
  # either way, so an unconfirmed address is an alarm that fires into nothing — the deployment
  # guide says to check the inbox for exactly this reason.
}

locals {
  ecs_dimensions_commander = {
    ClusterName = aws_ecs_cluster.this.name
    ServiceName = aws_ecs_service.commander.name
  }
}

resource "aws_cloudwatch_metric_alarm" "commander_cpu" {
  alarm_name          = "${local.name}-commander-cpu-high"
  alarm_description   = "The Commander is CPU-bound. On a 0.5 vCPU task this usually means an agent is looping rather than that the workload grew."
  namespace           = "AWS/ECS"
  metric_name         = "CPUUtilization"
  dimensions          = local.ecs_dimensions_commander
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  threshold           = 85
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "commander_memory" {
  alarm_name          = "${local.name}-commander-memory-high"
  alarm_description   = "Heap pressure in the Commander. The container is killed at 100 percent, and the investigation in flight goes with it."
  namespace           = "AWS/ECS"
  metric_name         = "MemoryUtilization"
  dimensions          = local.ecs_dimensions_commander
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  threshold           = 85
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
}

# The demo target's CPU alarm is not a health check. It is a signal the Commander itself reads
# during an investigation, and injecting the CPU-pressure fault is what makes it fire.
resource "aws_cloudwatch_metric_alarm" "demo_target_cpu" {
  count = var.enable_demo_target ? 1 : 0

  alarm_name        = "${local.name}-demo-target-cpu-high"
  alarm_description = "The demo target service is saturated. Expected while the cpu fault is active; this alarm is evidence, not an emergency."

  namespace   = "AWS/ECS"
  metric_name = "CPUUtilization"
  dimensions = {
    ClusterName = aws_ecs_cluster.this.name
    ServiceName = aws_ecs_service.demo_target[0].name
  }
  statistic           = "Average"
  period              = 60
  evaluation_periods  = 2
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "database_storage" {
  alarm_name        = "${local.name}-db-storage-low"
  alarm_description = "Free storage below 2 GB. On a 20 GB instance this is either runaway audit rows or a log setting nobody meant to change."

  namespace   = "AWS/RDS"
  metric_name = "FreeStorageSpace"
  dimensions = {
    DBInstanceIdentifier = aws_db_instance.this.identifier
  }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 1
  threshold           = 2 * 1024 * 1024 * 1024
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "database_cpu" {
  alarm_name        = "${local.name}-db-cpu-high"
  alarm_description = "Sustained CPU on a burstable instance. t4g classes run out of credits quietly, and the symptom is a database that is slow rather than one that is down."

  namespace   = "AWS/RDS"
  metric_name = "CPUUtilization"
  dimensions = {
    DBInstanceIdentifier = aws_db_instance.this.identifier
  }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "alb_latency" {
  count = var.enable_alb ? 1 : 0

  alarm_name        = "${local.name}-console-latency-high"
  alarm_description = "p95 response time above two seconds at the load balancer."

  namespace   = "AWS/ApplicationELB"
  metric_name = "TargetResponseTime"
  dimensions = {
    LoadBalancer = aws_lb.commander[0].arn_suffix
    TargetGroup  = aws_lb_target_group.commander[0].arn_suffix
  }
  extended_statistic  = "p95"
  period              = 300
  evaluation_periods  = 2
  threshold           = 2
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_dashboard" "this" {
  dashboard_name = local.name

  dashboard_body = jsonencode({
    widgets = concat(
      [
        {
          type   = "metric"
          x      = 0
          y      = 0
          width  = 12
          height = 6
          properties = {
            title  = "Commander — CPU and memory"
            region = var.region
            view   = "timeSeries"
            metrics = [
              ["AWS/ECS", "CPUUtilization", "ClusterName", aws_ecs_cluster.this.name, "ServiceName", aws_ecs_service.commander.name],
              [".", "MemoryUtilization", ".", ".", ".", "."],
            ]
            period = 300
            stat   = "Average"
            yAxis  = { left = { min = 0, max = 100 } }
          }
        },
        {
          type   = "metric"
          x      = 12
          y      = 0
          width  = 12
          height = 6
          properties = {
            title  = "Database — CPU, connections, free storage"
            region = var.region
            view   = "timeSeries"
            metrics = [
              ["AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", aws_db_instance.this.identifier],
              [".", "DatabaseConnections", ".", "."],
              [".", "FreeStorageSpace", ".", ".", { yAxis = "right" }],
            ]
            period = 300
            stat   = "Average"
          }
        },
      ],
      # A comprehension over the splat rather than a conditional: with the demo target disabled the
      # splat is empty and this contributes no widget, where an index would be an error.
      [
        for name in aws_ecs_service.demo_target[*].name : {
          type   = "metric"
          x      = 0
          y      = 6
          width  = 12
          height = 6
          properties = {
            title  = "Demo target — the service being investigated"
            region = var.region
            view   = "timeSeries"
            metrics = [
              ["AWS/ECS", "CPUUtilization", "ClusterName", aws_ecs_cluster.this.name, "ServiceName", name],
              [".", "MemoryUtilization", ".", ".", ".", "."],
            ]
            period = 60
            stat   = "Average"
            yAxis  = { left = { min = 0, max = 100 } }
          }
        }
      ],
      [
        {
          type   = "text"
          x      = 12
          y      = 6
          width  = 12
          height = 6
          properties = {
            markdown = join("\n", [
              "## What this dashboard is not",
              "",
              "These are the free ECS and RDS metrics. The interesting numbers — investigation duration,",
              "token spend, policy refusals, approvals waiting — are Micrometer metrics scraped from",
              "`/actuator/prometheus`, and the dashboard for them is `docs/dashboards/`.",
              "",
              "Publishing those to CloudWatch would be billed per custom metric per month. On a demo that",
              "is meant to be affordable to leave running, the operational picture stays free and the",
              "application picture stays local.",
            ])
          }
        }
      ]
    )
  })
}
