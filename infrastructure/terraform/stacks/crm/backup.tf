# AWS Backup (ADR-0023 decision 3): one daily plan that selects every
# resource tagged Backup=daily (the RDS instances and the client-keys table)
# and copies each recovery point to a locked vault in the DR region. Prod
# only, and off on the Free plan (ADR-0042); RDS point-in-time recovery still
# applies.

resource "aws_backup_vault" "primary" {
  count       = local.enable_backup ? 1 : 0
  name        = local.name
  kms_key_arn = aws_kms_key.platform.arn
}

resource "aws_backup_vault" "dr" {
  count    = local.enable_backup ? 1 : 0
  provider = aws.dr
  name     = "${local.name}-dr"
}

# Governance-mode Vault Lock: recovery points cannot be deleted or have their
# retention shortened, except by someone allowed to remove the lock.
resource "aws_backup_vault_lock_configuration" "dr" {
  count              = local.enable_backup ? 1 : 0
  provider           = aws.dr
  backup_vault_name  = aws_backup_vault.dr[0].name
  min_retention_days = 7
  max_retention_days = 35
}

resource "aws_backup_plan" "daily" {
  count = local.enable_backup ? 1 : 0
  name  = "${local.name}-daily"

  rule {
    rule_name         = "daily"
    target_vault_name = aws_backup_vault.primary[0].name
    schedule          = "cron(30 18 * * ? *)" # 02:30 SGT, after the RDS backup window

    lifecycle {
      delete_after = 7
    }

    copy_action {
      destination_vault_arn = aws_backup_vault.dr[0].arn
      lifecycle {
        delete_after = 7
      }
    }
  }
}

data "aws_iam_policy_document" "backup_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["backup.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "backup" {
  count              = local.enable_backup ? 1 : 0
  name               = "${local.name}-backup"
  assume_role_policy = data.aws_iam_policy_document.backup_assume.json
}

resource "aws_iam_role_policy_attachment" "backup" {
  for_each = local.enable_backup ? toset([
    "service-role/AWSBackupServiceRolePolicyForBackup",
    "service-role/AWSBackupServiceRolePolicyForRestores",
  ]) : toset([])
  role       = aws_iam_role.backup[0].name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/${each.value}"
}

resource "aws_backup_selection" "tagged" {
  count        = local.enable_backup ? 1 : 0
  name         = "tagged-daily"
  plan_id      = aws_backup_plan.daily[0].id
  iam_role_arn = aws_iam_role.backup[0].arn

  selection_tag {
    type  = "STRINGEQUALS"
    key   = "Backup"
    value = "daily"
  }
}
