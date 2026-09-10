variable "region" {
  description = "Region for the state bucket. The same region as the stack, so that a destroy workflow does not depend on two regions being reachable."
  type        = string
  default     = "eu-west-1"
}

variable "project" {
  description = "Name prefix. Must match the main configuration's project variable: the deploy role's IAM permissions are scoped by this prefix, and a mismatch produces AccessDenied at apply time rather than at plan time."
  type        = string
  default     = "commander"
}

variable "project_tag" {
  description = "Value of the Project tag."
  type        = string
  default     = "aws-incident-response-commander"
}

variable "github_repository" {
  description = "The repository allowed to assume the deploy role, as owner/name. This is the whole of the trust boundary, so it is exact: no wildcards, no organisation-wide match."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must be owner/name."
  }
}

variable "github_environment" {
  description = "The GitHub Environment the deploy and destroy workflows run in. The trust policy requires it, which is what makes the environment's manual approval a real gate rather than a convention: a workflow that skips the environment cannot obtain credentials at all."
  type        = string
  default     = "aws-demo"
}

variable "allowed_db_instance_classes" {
  description = "The only RDS classes the deploy role may create. An explicit Deny on everything else, so a typo in a tfvars file fails at the IAM layer instead of appearing on the bill."
  type        = list(string)
  default     = ["db.t4g.micro", "db.t4g.small"]
}

variable "state_bucket_name" {
  description = "State bucket name. Empty means one is generated with a random suffix, since bucket names are globally unique and a predictable name is a name someone else may already hold."
  type        = string
  default     = ""
}
