output "ecr_commander_repository" {
  description = "Push the Commander image here. The deploy workflow reads this."
  value       = aws_ecr_repository.commander.repository_url
}

output "ecr_demo_target_repository" {
  description = "Push the demo target image here."
  value       = aws_ecr_repository.demo_target.repository_url
}

output "cluster_name" {
  description = "ECS cluster. Also the boundary both IAM policies condition on."
  value       = aws_ecs_cluster.this.name
}

output "console_url" {
  description = "Where the Commander is reachable, when there is a load balancer to reach it at."
  value       = var.enable_alb ? format("%s://%s", var.certificate_arn == "" ? "http" : "https", one(aws_lb.commander[*].dns_name)) : null
}

output "commander_task_ip_hint" {
  description = "Without a load balancer the address is the task's public IP, and it changes on every replacement. This is how to find the current one."
  value = var.enable_alb ? null : join(" ", [
    "aws ecs list-tasks --cluster ${aws_ecs_cluster.this.name} --service-name ${aws_ecs_service.commander.name} --query 'taskArns[0]' --output text |",
    "xargs -I{} aws ecs describe-tasks --cluster ${aws_ecs_cluster.this.name} --tasks {}",
    "--query 'tasks[0].attachments[0].details[?name==`networkInterfaceId`].value' --output text |",
    "xargs -I{} aws ec2 describe-network-interfaces --network-interface-ids {}",
    "--query 'NetworkInterfaces[0].Association.PublicIp' --output text",
  ])
}

output "database_endpoint" {
  description = "RDS endpoint. Reachable only from the Commander's security group; there is no public path to it."
  value       = aws_db_instance.this.endpoint
}

output "database_secret_arn" {
  description = "The RDS-managed credential. Terraform never held this value, and neither did this repository."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "gemini_secret_arn" {
  description = "Set the real key with: aws secretsmanager put-secret-value --secret-id <this> --secret-string \"$GEMINI_API_KEY\""
  value       = one(aws_secretsmanager_secret.gemini_api_key[*].arn)
}

output "dashboard_url" {
  description = "The free-metrics dashboard."
  value       = "https://${var.region}.console.aws.amazon.com/cloudwatch/home?region=${var.region}#dashboards:name=${aws_cloudwatch_dashboard.this.dashboard_name}"
}

output "task_role_arn" {
  description = "What the Commander runs as. Whether it can act at all is decided by which policies are attached to this, not by what the application believes."
  value       = aws_iam_role.commander_task.arn
}

output "safety_posture" {
  description = "The three independent switches, resolved. Read this after every apply: it is the deployment's own answer to whether it can change anything."
  value = {
    signal_source              = var.signal_source
    model_profile              = var.model_profile
    enable_remediation_actions = var.enable_remediation_actions
    dry_run                    = var.dry_run
    can_change_aws             = var.enable_remediation_actions && !var.dry_run
    inbound_access             = var.admin_cidr == "" ? "none" : var.admin_cidr
  }
}
