# Partial backend configuration. The bucket is created by `infra/terraform/bootstrap`, and its name
# is supplied at init time rather than committed:
#
#   terraform init \
#     -backend-config="bucket=<from bootstrap output>" \
#     -backend-config="key=commander/terraform.tfstate" \
#     -backend-config="region=eu-west-1"
#
# State is remote because the destroy workflow runs in GitHub Actions and cannot destroy what it
# cannot see. Local state and a CI teardown are incompatible, and the failure mode is a running
# RDS instance nobody remembers creating.
terraform {
  backend "s3" {
    encrypt = true

    # Conditional writes on S3 replace the DynamoDB lock table entirely (Terraform >= 1.11).
    use_lockfile = true
  }
}
