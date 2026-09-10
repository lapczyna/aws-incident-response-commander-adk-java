# Two repositories, both with immutable tags.
#
# Immutability is not a purity exercise. The deploy workflow tags images with the commit SHA, and a
# mutable tag means the answer to "what is running in the demo" is "whatever was pushed last",
# which is precisely the question an incident tool should be able to answer about itself.
#
# force_delete is on because teardown has to work. A repository that refuses to delete while it
# holds images turns `terraform destroy` into a manual cleanup, and a manual cleanup is a bill.

resource "aws_ecr_repository" "commander" {
  name                 = "${local.name}/commander"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = true

  image_scanning_configuration {
    # Basic scanning is free and runs on push. There is no reason to deploy an image nobody looked
    # at, especially one whose whole subject is safety.
    scan_on_push = true
  }
}

resource "aws_ecr_repository" "demo_target" {
  name                 = "${local.name}/demo-target"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = true

  image_scanning_configuration {
    scan_on_push = true
  }
}

# Storage is USD 0.10 per GB-month, and a JRE-based image is a few hundred megabytes. Five images
# is enough to roll back a couple of deploys and not enough to matter on the bill.
locals {
  ecr_lifecycle_policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep the five most recent images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 5
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}

resource "aws_ecr_lifecycle_policy" "commander" {
  repository = aws_ecr_repository.commander.name
  policy     = local.ecr_lifecycle_policy
}

resource "aws_ecr_lifecycle_policy" "demo_target" {
  repository = aws_ecr_repository.demo_target.name
  policy     = local.ecr_lifecycle_policy
}
