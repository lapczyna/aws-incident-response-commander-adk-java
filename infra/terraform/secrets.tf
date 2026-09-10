# The one secret this configuration creates, and the one thing it deliberately does not put in it.
#
# The Gemini API key is created as an empty secret and its value is set out of band:
#
#   aws secretsmanager put-secret-value \
#     --secret-id <the arn from terraform output> \
#     --secret-string "$GEMINI_API_KEY"
#
# A key passed to Terraform as a variable ends up in state, in the plan file, and in the CI job's
# memory, and state lives in an S3 bucket that is now three permissions away from being readable.
# Creating the container in Terraform and filling it with the CLI keeps the value in exactly one
# place. lifecycle.ignore_changes is what stops the next apply from wiping it back to the
# placeholder.
#
# The database password is not here at all: RDS generates and owns it (see rds.tf), and nothing in
# this repository has ever seen it.

resource "aws_secretsmanager_secret" "gemini_api_key" {
  count = var.model_profile == "gemini" ? 1 : 0

  name        = "${local.name}/gemini-api-key"
  description = "Set with `aws secretsmanager put-secret-value`. Terraform never sees the value."

  # No recovery window. Secrets Manager's default is a seven-day soft delete, during which the name
  # is taken and the secret is still billed — which means `terraform destroy` followed by
  # `terraform apply` fails, and teardown leaves a charge behind.
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "gemini_api_key_placeholder" {
  count = var.model_profile == "gemini" ? 1 : 0

  secret_id = aws_secretsmanager_secret.gemini_api_key[0].id

  # Secrets Manager will not store an empty string, so the placeholder is a sentence rather than a
  # blank. The deploy workflow refuses to start a service whose key is still this value, which
  # turns a 401 on the first investigation into a failure before anything is running.
  secret_string = "REPLACE_ME_WITH_put-secret-value"

  lifecycle {
    ignore_changes = [secret_string]
  }
}
