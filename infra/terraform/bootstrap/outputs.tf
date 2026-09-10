output "state_bucket" {
  description = "Pass to the main configuration: terraform init -backend-config=\"bucket=<this>\""
  value       = aws_s3_bucket.state.id
}

output "deploy_role_arn" {
  description = "Set as the AWS_DEPLOY_ROLE_ARN repository variable in GitHub. Not a secret: it is useless without a token from the one repository and environment named in its trust policy."
  value       = aws_iam_role.deploy.arn
}

output "trusted_subject" {
  description = "The exact OIDC subject that may assume the deploy role. If a workflow gets AccessDenied on the assume step, compare this against the sub claim in the run's token: the mismatch is almost always a missing `environment:` on the job."
  value       = "repo:${var.github_repository}:environment:${var.github_environment}"
}

output "next_steps" {
  description = "What to do with these values."
  value = join("\n", [
    "1. In GitHub, create the environment '${var.github_environment}' with required reviewers.",
    "   The trust policy names it, so a run that skips the environment gets no credentials at all.",
    "2. Set repository variables:",
    "     AWS_DEPLOY_ROLE_ARN = ${aws_iam_role.deploy.arn}",
    "     AWS_REGION          = ${var.region}",
    "     TF_STATE_BUCKET     = ${aws_s3_bucket.state.id}",
    "3. Run the Deploy workflow. It plans by default and applies only when asked.",
  ])
}
