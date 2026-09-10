terraform {
  required_version = ">= 1.11.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Local state, on purpose, and the only place in this repository where that is true.
  #
  # This module creates the bucket the main configuration's state lives in. It cannot store its own
  # state there, and inventing a second bucket to break the circularity only moves the problem. The
  # state file it produces describes a bucket and two IAM resources; it holds no credentials, and
  # re-creating it from an empty state is an import rather than a rebuild.
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = var.project_tag
      ManagedBy = "terraform"
      Component = "bootstrap"
    }
  }
}
