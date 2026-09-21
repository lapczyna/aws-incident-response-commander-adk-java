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

# ---------------------------------------------------------------------------------------------
# The console password
# ---------------------------------------------------------------------------------------------
#
# Generated here rather than defaulted in the application, because the application's default is
# published: `commander` appears in application.yaml, in the README and on the login page. That is
# the right default for a stack on a laptop and the wrong one for anything with an address.
#
# Only exists under `local-identity`. The `oidc` profile has no local users, so there is no
# password to hold — which is the reason to prefer it for anything long-lived.

resource "random_password" "console" {
  count = var.identity_profile == "local-identity" ? 1 : 0

  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "console_password" {
  count = var.identity_profile == "local-identity" ? 1 : 0

  name        = "${local.name}/console-password"
  description = "Password for the three demo console identities. Read it with `aws secretsmanager get-secret-value`."

  # No recovery window, for the reason the Gemini secret gives: a seven-day soft delete makes
  # destroy-then-apply fail and leaves a charge behind after teardown.
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "console_password" {
  count = var.identity_profile == "local-identity" ? 1 : 0

  secret_id     = aws_secretsmanager_secret.console_password[0].id
  secret_string = random_password.console[0].result
}
