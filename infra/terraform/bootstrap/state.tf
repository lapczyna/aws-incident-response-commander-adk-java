# The Terraform state bucket.
#
# Versioned, encrypted, and closed to the public — not as a checklist exercise, but because
# Terraform state is the most sensitive artefact this project produces. It contains resource ids,
# ARNs, security group rules and the shape of the whole deployment. It does not contain the
# database password (RDS owns that) or the model API key (Secrets Manager owns that), and both of
# those absences are deliberate design choices made elsewhere in this configuration.
#
# Versioning is what makes a corrupted or truncated state recoverable. It is the one setting here
# that has actually saved someone.

resource "random_id" "suffix" {
  count = var.state_bucket_name == "" ? 1 : 0

  byte_length = 4
}

locals {
  bucket_name = var.state_bucket_name != "" ? var.state_bucket_name : "${var.project}-tfstate-${random_id.suffix[0].hex}"
}

resource "aws_s3_bucket" "state" {
  bucket = local.bucket_name

  # No force_destroy. Everything else in this project is built to be torn down in one command; this
  # bucket is the exception, and it should take a deliberate act to remove the record of what was
  # created. A `terraform destroy` of the main stack does not touch it.
  force_destroy = false
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      # SSE-S3 rather than KMS. A customer-managed key adds USD 1 per month plus per-request
      # charges to protect a file that already carries no secrets, and it adds a second thing that
      # must be reachable before a teardown can run.
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Old state versions are kept for a month and then removed. Long enough to recover from a bad
# apply; short enough that a bucket of state files does not become its own line item.
resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "expire-old-state-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 30
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# Terraform 1.11+ takes a lock by writing a .tflock object next to the state, using S3 conditional
# writes. Nothing needs to be created for it — this comment exists because its absence is the
# question a reader will have when they notice there is no DynamoDB table.
