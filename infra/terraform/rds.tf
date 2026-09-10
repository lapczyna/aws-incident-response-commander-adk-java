# PostgreSQL.
#
# THIS IS NOT HIGHLY AVAILABLE, AND IT IS NOT MEANT TO BE.
#
# Single-AZ, one instance, one day of backups. Multi-AZ doubles the instance cost for a demo that
# can be recreated from an empty schema in under a minute, and pretending otherwise in a portfolio
# project would be the kind of claim this whole repository exists to avoid making. If this stack
# were real, this file is the first one that would change: multi_az = true, backup retention in
# weeks, deletion protection on, and a read replica for the reporting queries.
#
# What it does get right: the master password never exists in Terraform state, in a variable, or in
# a task definition. manage_master_user_password hands generation and storage to RDS, which puts it
# in its own Secrets Manager secret and rotates the credential without anything here knowing what
# it is. The ECS task reads it from that secret at start-up.

resource "aws_db_subnet_group" "this" {
  name        = "${local.name}-db"
  description = "Isolated subnets, no route to the internet"
  subnet_ids  = aws_subnet.database[*].id
}

resource "aws_db_parameter_group" "this" {
  name        = "${local.name}-pg17"
  family      = "postgres17"
  description = "Log slow statements; everything else is the engine default"

  # Half a second. The Commander's own queries are small; anything slower than this is either a
  # missing index or the connection-pool-pressure scenario doing its job, and both are worth seeing.
  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "this" {
  identifier     = "${local.name}-db"
  engine         = "postgres"
  engine_version = "17"
  instance_class = var.db_instance_class

  db_name  = "commander"
  username = "commander"

  # No password argument, anywhere. RDS generates it and owns it.
  manage_master_user_password = true

  # No max_allocated_storage, which is what disables storage autoscaling: storage that grows on its
  # own is a bill that grows on its own, and 20 GB is already orders of magnitude more than this
  # schema needs. The free-storage alarm exists to notice the case this forecloses.
  allocated_storage = var.db_allocated_storage
  storage_type      = "gp3"
  storage_encrypted = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.database.id]
  parameter_group_name   = aws_db_parameter_group.this.name
  publicly_accessible    = false

  multi_az                = false
  backup_retention_period = var.db_backup_retention_days
  backup_window           = "02:00-03:00"
  maintenance_window      = "Mon:03:00-Mon:04:00"

  # Both are billed extras, and both are off. Enhanced Monitoring is per-instance per-month;
  # Performance Insights is free only for seven days of retention on some classes and is not free
  # on all of them. Neither earns its place on a demo that is meant to be torn down.
  monitoring_interval          = 0
  performance_insights_enabled = false

  auto_minor_version_upgrade = true
  apply_immediately          = true

  # Deletion protection off and no final snapshot: teardown must be a single command that leaves
  # nothing billable behind. A final snapshot is storage that survives `terraform destroy` and
  # appears on next month's bill.
  deletion_protection = false
  skip_final_snapshot = true

  # Postgres logs to CloudWatch, with the same short retention as everything else. Set here rather
  # than left to the engine, because the default is no export at all and a database whose logs are
  # unreachable is a database you cannot investigate.
  enabled_cloudwatch_logs_exports = ["postgresql"]

  tags = {
    Name = "${local.name}-db"
  }

  # Ordering, not decoration. RDS creates the export log group itself if it does not exist, with no
  # retention period, and Terraform would then fail to create the one below because it already
  # exists. Creating ours first means the retention setting is the one that takes effect.
  depends_on = [aws_cloudwatch_log_group.database]
}

# RDS creates this log group itself on first export. Declaring it here is what gives it a retention
# period; without it, the group is created with "never expire" and quietly accumulates.
resource "aws_cloudwatch_log_group" "database" {
  name              = "/aws/rds/instance/${local.name}-db/postgresql"
  retention_in_days = var.log_retention_days
}
