# One RDS for PostgreSQL instance for one service (ADR-0023 decision 1).
# The stack passes in sizes and backup settings.

terraform {
  required_version = ">= 1.10.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

resource "aws_db_instance" "this" {
  identifier     = var.identifier
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class
  db_name        = var.db_name

  # RDS creates the master password and keeps it in Secrets Manager.
  username                    = "crm_admin"
  manage_master_user_password = true

  allocated_storage     = var.allocated_storage
  max_allocated_storage = var.max_allocated_storage
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = var.kms_key_arn

  db_subnet_group_name   = var.subnet_group_name
  vpc_security_group_ids = [var.security_group_id]
  parameter_group_name   = var.parameter_group_name
  publicly_accessible    = false
  multi_az               = var.multi_az

  backup_retention_period  = var.backup_retention_days
  backup_window            = "18:00-18:30" # 02:00-02:30 SGT
  maintenance_window       = "sun:19:00-sun:19:30"
  copy_tags_to_snapshot    = true
  delete_automated_backups = true

  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = var.skip_final_snapshot
  final_snapshot_identifier = var.skip_final_snapshot ? null : "${var.identifier}-final"

  auto_minor_version_upgrade   = true
  apply_immediately            = true
  performance_insights_enabled = false
  monitoring_interval          = 0

  tags = merge(var.tags, var.backup_tag ? { Backup = "daily" } : {})
}
