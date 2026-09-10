terraform {
  # 1.11 introduced S3-native state locking, which is why this configuration needs no DynamoDB
  # table. A lock table is a real resource with a real bill for a demo that is applied a handful of
  # times, and removing it removes something that has to be torn down afterwards.
  required_version = ">= 1.11.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.region

  # Every resource carries these, and nothing here is created without them. The tags are not
  # decoration: `Project` and `Environment` are the same two tags the policy engine requires, the
  # IAM action policy conditions on, and the executor re-reads from live AWS state before acting.
  # A resource created outside this configuration therefore cannot be a remediation target, which
  # is the property that makes a blast radius describable.
  default_tags {
    tags = {
      Project     = var.project_tag
      Environment = var.environment
      ManagedBy   = "terraform"
      Repository  = var.repository
    }
  }
}
