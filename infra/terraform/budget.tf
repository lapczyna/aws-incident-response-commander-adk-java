# The backstop.
#
# Every other cost control in this configuration is a decision made in advance: no NAT Gateway, no
# Container Insights, three-day log retention, Spot capacity, the smallest instance classes. This
# one is different. It watches the actual bill, which is the only number that cannot be argued
# with, and it notices the thing none of the others can — that the stack was left running.
#
# AWS Budgets is free for the first two budgets per account. There is one here.
#
# Note what this is not: it is not the LLM budget. CostGuard, in the application, caps model spend
# at commander.model.monthly-budget-usd and fails closed when the ceiling is reached. That is a
# different number, enforced in a different place, and neither bounds the other. docs/cost.md says
# so at more length, because conflating them would make the cheaper figure look like the whole
# story.

resource "aws_budgets_budget" "monthly" {
  name         = "${local.name}-monthly"
  budget_type  = "COST"
  limit_amount = format("%.2f", var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # Account-wide rather than filtered to this project's tags. A tag filter would miss exactly the
  # failure this is here to catch: something created outside Terraform, or left behind by a
  # half-finished destroy, which by definition does not carry the tags.

  # Fifty percent, while there is still a month left to react in.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 50
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.budget_notification_email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 90
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.budget_notification_email]
  }

  # The forecast alert is the one that matters. Actual spend crossing the line tells you what
  # already happened; the forecast crossing it tells you a week early that the stack is still up.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.budget_notification_email]
  }
}
