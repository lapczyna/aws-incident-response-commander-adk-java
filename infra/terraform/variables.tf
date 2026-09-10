# Variables, grouped by what they cost you.
#
# The rule throughout: anything that costs money while idle is behind a flag that defaults to off,
# and anything that changes production defaults to the safe value. There is no single "production"
# switch, because a single switch is a switch someone flips without reading what it turns on.

# ---------------------------------------------------------------------------------------------
# Identity
# ---------------------------------------------------------------------------------------------

variable "project" {
  description = "Resource name prefix, and the ECS cluster name. Also the log-group prefix the read IAM policy is scoped to, so changing it changes that scope."
  type        = string
  default     = "commander"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,20}$", var.project))
    error_message = "project must be lowercase alphanumeric with hyphens, 3-21 characters."
  }
}

variable "project_tag" {
  description = "Value of the Project tag. This is the tag the policy engine requires, the IAM action policy conditions on, and the executor re-reads from live AWS state; it must match commander.policy.required-tag.value in application.yaml."
  type        = string
  default     = "aws-incident-response-commander"
}

variable "environment" {
  description = "Value of the Environment tag, and COMMANDER_ENVIRONMENT. An action proposed against any other environment is refused by the policy engine before IAM is ever consulted."
  type        = string
  default     = "demo"
}

variable "repository" {
  description = "Source repository, as owner/name. Recorded as a tag so an orphaned resource can be traced back to what created it."
  type        = string
}

variable "region" {
  description = "AWS region. eu-west-1 by default because it is where the documented cost figures were priced."
  type        = string
  default     = "eu-west-1"
}

# ---------------------------------------------------------------------------------------------
# Cost controls
# ---------------------------------------------------------------------------------------------

variable "monthly_budget_usd" {
  description = "AWS Budgets ceiling for the whole account, in USD. This is infrastructure spend and has nothing to do with the LLM budget enforced by CostGuard; see docs/cost.md."
  type        = number
  default     = 40

  validation {
    condition     = var.monthly_budget_usd > 0
    error_message = "monthly_budget_usd must be positive. A budget of zero produces alerts that fire immediately and are then ignored."
  }
}

variable "budget_notification_email" {
  description = "Where budget alerts go. Deliberately has no default: a budget nobody is told about is a budget nobody acts on, and this stack is capable of running until someone notices."
  type        = string

  validation {
    condition     = can(regex("^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$", var.budget_notification_email))
    error_message = "budget_notification_email must be an email address."
  }
}

variable "use_fargate_spot" {
  description = "Run tasks on Fargate Spot, at roughly 70 percent less than on-demand. Interruption is survivable here by design: incident state is in PostgreSQL and ADK sessions are resumable, so a reclaimed task resumes mid-approval rather than losing the incident."
  type        = bool
  default     = true
}

variable "log_retention_days" {
  description = "CloudWatch Logs retention. Short on purpose: log storage is the line item that grows without anyone deciding to grow it."
  type        = number
  default     = 3

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90], var.log_retention_days)
    error_message = "log_retention_days must be a retention period CloudWatch accepts, and this stack refuses the long ones."
  }
}

variable "enable_container_insights" {
  description = "ECS Container Insights. Off by default: it is billed per metric and per log ingested, and it is the easiest way to spend more on observing this demo than on running it."
  type        = bool
  default     = false
}

variable "enable_alb" {
  description = "Put an Application Load Balancer in front of the Commander. Off by default; an idle ALB is about USD 18 per month in eu-west-1 before a single request. With it off, reach the task on its public IP from admin_cidr."
  type        = bool
  default     = false
}

variable "db_instance_class" {
  description = "RDS instance class. Constrained to the small Graviton classes, and the IAM deploy role denies anything larger, so a typo cannot produce a db.r6g.4xlarge."
  type        = string
  default     = "db.t4g.micro"

  validation {
    condition     = contains(["db.t4g.micro", "db.t4g.small"], var.db_instance_class)
    error_message = "db_instance_class must be db.t4g.micro or db.t4g.small."
  }
}

variable "db_allocated_storage" {
  description = "RDS storage in GB. 20 is the gp3 minimum."
  type        = number
  default     = 20
}

variable "db_backup_retention_days" {
  description = "Automated backup retention. One day, because this data is reproducible by re-running the simulator; the backup exists so a restore is possible at all, not so history is preserved."
  type        = number
  default     = 1
}

# ---------------------------------------------------------------------------------------------
# Access
# ---------------------------------------------------------------------------------------------

variable "admin_cidr" {
  description = "CIDR allowed to reach the Commander and the demo target service. Empty by default, which creates no inbound rule at all: the stack comes up reachable by nothing, and opening it is an explicit act. Never set this to 0.0.0.0/0 while remediation actions are enabled."
  type        = string
  default     = ""

  validation {
    condition     = var.admin_cidr == "" || can(cidrhost(var.admin_cidr, 0))
    error_message = "admin_cidr must be a valid CIDR block, or empty for no inbound access."
  }
}

variable "certificate_arn" {
  description = "ACM certificate for the ALB. When set, the listener is HTTPS and port 80 redirects to it. When empty the listener is plain HTTP, which is why admin_cidr exists."
  type        = string
  default     = ""
}

# ---------------------------------------------------------------------------------------------
# Application behaviour
# ---------------------------------------------------------------------------------------------

variable "model_profile" {
  description = "Which model the deployed Commander uses: gemini, bedrock, or fake. There is no default that reaches a paid provider by accident, and the Bedrock IAM policy is attached only when bedrock is selected."
  type        = string
  default     = "fake"

  validation {
    condition     = contains(["fake", "gemini", "bedrock"], var.model_profile)
    error_message = "model_profile must be fake, gemini or bedrock. ollama is a local-only profile and has no meaning on Fargate."
  }
}

variable "bedrock_model_id" {
  description = "Bedrock model id, used both for the runtime and to scope bedrock:InvokeModel to exactly this model."
  type        = string
  default     = "amazon.nova-lite-v1:0"
}

variable "model_monthly_budget_usd" {
  description = "The LLM spending ceiling CostGuard enforces in the application, in USD per calendar month. Distinct from monthly_budget_usd, which is the AWS bill."
  type        = number
  default     = 10
}

variable "signal_source" {
  description = "Where investigations get their evidence: the deterministic simulator, or live AWS. The simulator makes no AWS API calls at all, so it is both the cheaper and the safer default."
  type        = string
  default     = "simulator"

  validation {
    condition     = contains(["simulator", "aws"], var.signal_source)
    error_message = "signal_source must be simulator or aws."
  }
}

variable "enable_remediation_actions" {
  description = "Attach the IAM action policy and set COMMANDER_ACTIONS_ENABLED. Off by default, which means the deployed task role physically cannot call ecs:UpdateService or ecs:StopTask, whatever the application believes about itself."
  type        = bool
  default     = false
}

variable "dry_run" {
  description = "COMMANDER_DRY_RUN. True means approved actions are simulated and reported as simulated. Turning this off is the last of three independent switches, and it is the only one that makes the system change anything."
  type        = bool
  default     = true
}

variable "commander_image_tag" {
  description = "Image tag to deploy for the Commander. The deploy workflow passes the commit SHA; latest is not used, because a service that redeploys the same tag cannot tell you what is running."
  type        = string
}

variable "demo_target_image_tag" {
  description = "Image tag to deploy for the fault-injectable demo target service."
  type        = string
}

variable "enable_demo_target" {
  description = "Run the fault-injectable target service. It is the thing being investigated, so a deployment without it has nothing to demonstrate, but it is also a second Fargate task, so it is a switch."
  type        = bool
  default     = true
}
