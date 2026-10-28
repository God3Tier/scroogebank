# Data stores: one RDS for PostgreSQL instance per service (ADR-0023), the
# client-keys DynamoDB table (ADR-0030) and the ID-documents bucket (ADR-0041).
# health-db is migrated by the Client Health API task; its scheduled analysis
# task is added separately by RH-SF11D.

resource "aws_db_parameter_group" "postgres" {
  name   = "${local.name}-postgres17"
  family = "postgres17"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
}

locals {
  databases = {
    users        = { db_name = "users", sg = "db_users" }
    clients      = { db_name = "clients", sg = "db_clients" }
    accounts     = { db_name = "accounts", sg = "db_accounts" }
    transactions = { db_name = "transactions", sg = "db_trans" }
    audit        = { db_name = "audit", sg = "db_audit" }
    alerts       = { db_name = "alerts", sg = "db_alerts" }
    health       = { db_name = "health", sg = "db_health" }
  }
}

module "db" {
  source   = "../../modules/postgres"
  for_each = local.databases

  identifier            = "${local.name}-${each.key}-db"
  db_name               = each.value.db_name
  instance_class        = local.db_settings.instance_class
  allocated_storage     = local.db_settings.allocated_storage
  max_allocated_storage = local.db_settings.max_allocated_storage
  backup_retention_days = local.db_settings.backup_retention_days
  multi_az              = local.db_settings.multi_az
  deletion_protection   = local.db_settings.deletion_protection
  skip_final_snapshot   = local.db_settings.skip_final_snapshot
  kms_key_arn           = aws_kms_key.platform.arn
  subnet_group_name     = aws_db_subnet_group.this.name
  security_group_id     = local.sg[each.value.sg]
  parameter_group_name  = aws_db_parameter_group.postgres.name
  backup_tag            = local.enable_backup
}

# ------------------------------------------------- least-privilege logins
# No service connects as its database's master user, which owns the tables;
# only db-migrate does (ADR-0028 decision 3, ADR-0042 decision 6). Terraform
# makes each login's password, and the db-migrate container in the task of
# the service that owns the database creates the login and sets the password
# on every start (services.tf).
#
# The auditor and chain-verify hold the roles ADR-0022 decision 2 defines.

locals {
  audit_logins = {
    auditor      = { username = "auditor_app", role = "auditor_service" }
    chain_verify = { username = "chain_verify", role = "auditor_chain_verify" }
  }
}

# The other services' logins read and change rows, but cannot change the
# schema (infrastructure/db-migrate/app-login/afterMigrate.sql).
# transaction-service checks no token, so its login only reads transactions,
# and the ingest Lambda has its own (infrastructure/db-migrate/transactions/).
locals {
  app_logins = {
    user_service        = { db = "users" }
    client_service      = { db = "clients" }
    account_service     = { db = "accounts" }
    transaction_service = { db = "transactions" }
    transaction_ingest  = { db = "transactions" }
    event_alert_service = { db = "alerts" }
    client_health_service = { db = "health" }
  }
}

resource "random_password" "app_login" {
  for_each = local.app_logins
  length   = 32
  special  = false
}

resource "aws_secretsmanager_secret" "app_login" {
  for_each                = local.app_logins
  name                    = "${local.name}/db/${each.value.db}/${each.key}"
  description             = "${each.value.db}-db login without schema rights"
  recovery_window_in_days = local.deletion_protection ? 7 : 0
}

resource "aws_secretsmanager_secret_version" "app_login" {
  for_each  = local.app_logins
  secret_id = aws_secretsmanager_secret.app_login[each.key].id
  secret_string = jsonencode({
    username = each.key
    password = random_password.app_login[each.key].result
  })
}

resource "random_password" "audit_login" {
  for_each = local.audit_logins
  length   = 32
  special  = false
}

resource "aws_secretsmanager_secret" "audit_login" {
  for_each                = local.audit_logins
  name                    = "${local.name}/db/audit/${each.value.username}"
  description             = "audit-db login holding ${each.value.role}"
  recovery_window_in_days = local.deletion_protection ? 7 : 0
}

resource "aws_secretsmanager_secret_version" "audit_login" {
  for_each  = local.audit_logins
  secret_id = aws_secretsmanager_secret.audit_login[each.key].id
  secret_string = jsonencode({
    username = each.value.username
    password = random_password.audit_login[each.key].result
  })
}

# ------------------------------------------------------------ client keys

resource "aws_dynamodb_table" "client_keys" {
  name         = "${local.name}-client-keys"
  hash_key     = "client_id"
  billing_mode = "PAY_PER_REQUEST"

  deletion_protection_enabled = local.deletion_protection

  attribute {
    name = "client_id"
    type = "S"
  }

  # Losing this table makes all client data unreadable (ADR-0030), so it has
  # point-in-time recovery and, in prod, the AWS Backup plan.
  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }

  tags = local.enable_backup ? { Backup = "daily" } : {}
}

# ---------------------------------------------------- client ID documents

resource "aws_s3_bucket" "documents" {
  bucket        = "${local.name}-client-documents-${local.account_id}"
  force_destroy = !local.deletion_protection
}

resource "aws_s3_bucket_public_access_block" "documents" {
  bucket                  = aws_s3_bucket.documents.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "documents" {
  bucket = aws_s3_bucket.documents.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_versioning" "documents" {
  bucket = aws_s3_bucket.documents.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.documents.arn
    }
    bucket_key_enabled = true
  }
}

# Objects only for client-service's role, and only through the S3 gateway
# endpoint (ADR-0041 decision 9). Bucket-level settings stay manageable by
# Terraform.
data "aws_iam_policy_document" "documents" {
  statement {
    sid    = "OnlyClientService"
    effect = "Deny"
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:GetObjectVersion", "s3:DeleteObjectVersion"]
    resources = ["${aws_s3_bucket.documents.arn}/*"]
    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values   = [module.client_service.task_role_arn]
    }
  }

  statement {
    sid    = "OnlyThroughTheGatewayEndpoint"
    effect = "Deny"
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = ["${aws_s3_bucket.documents.arn}/*"]
    condition {
      test     = "StringNotEquals"
      variable = "aws:SourceVpce"
      values   = [aws_vpc_endpoint.s3.id]
    }
  }

  statement {
    sid    = "TlsOnly"
    effect = "Deny"
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.documents.arn, "${aws_s3_bucket.documents.arn}/*"]
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "documents" {
  bucket     = aws_s3_bucket.documents.id
  policy     = data.aws_iam_policy_document.documents.json
  depends_on = [aws_s3_bucket_public_access_block.documents]
}

# --------------------------------------------------------- database console
# A one-off psql task per database, for ad-hoc SQL and the producer's account
# sync (scripts/db-run-sql.sh), as the master user. It runs only when someone
# starts it with aws ecs run-task.

resource "aws_iam_role" "db_console_exec" {
  name               = "${local.name}-db-console-exec"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "db_console_exec" {
  role       = aws_iam_role.db_console_exec.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "db_console_secrets" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [for db in module.db : db.master_secret_arn]
  }
}

resource "aws_iam_role_policy" "db_console_secrets" {
  name   = "read-master-secrets"
  role   = aws_iam_role.db_console_exec.id
  policy = data.aws_iam_policy_document.db_console_secrets.json
}

resource "aws_cloudwatch_log_group" "db_console" {
  name              = "/ecs/${local.name}-db-console"
  retention_in_days = local.log_retention_days
}

resource "aws_ecs_task_definition" "db_console" {
  for_each                 = local.databases
  family                   = "${local.name}-db-console-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.db_console_exec.arn

  container_definitions = jsonencode([{
    name      = "psql"
    image     = "public.ecr.aws/docker/library/postgres:17-alpine"
    essential = true
    # The runbook replaces this command with the SQL to run.
    command = ["psql", "-c", "SELECT current_user, version();"]
    environment = [
      { name = "PGHOST", value = module.db[each.key].address },
      { name = "PGDATABASE", value = module.db[each.key].db_name },
      { name = "PGSSLMODE", value = "require" },
    ]
    secrets = [
      { name = "PGUSER", valueFrom = "${module.db[each.key].master_secret_arn}:username::" },
      { name = "PGPASSWORD", valueFrom = "${module.db[each.key].master_secret_arn}:password::" },
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.db_console.name
        awslogs-region        = local.region
        awslogs-stream-prefix = each.key
      }
    }
  }])
}
