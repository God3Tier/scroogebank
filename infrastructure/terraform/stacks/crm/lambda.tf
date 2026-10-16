data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

# ----------------------------------------------------------- Cognito triggers
# services/cognito-triggers/README.md "Infrastructure requirements" (ADR-0040):
# java25 on arm64, 1,769 MB, 5-second timeout, no VPC, no SnapStart.

locals {
  cognito_triggers_name = "${local.name}-cognito-triggers"
}

resource "aws_cloudwatch_log_group" "cognito_triggers" {
  count             = var.enable_cognito_triggers ? 1 : 0
  name              = "/aws/lambda/${local.cognito_triggers_name}"
  retention_in_days = local.log_retention_days
}

resource "aws_iam_role" "cognito_triggers" {
  count              = var.enable_cognito_triggers ? 1 : 0
  name               = local.cognito_triggers_name
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

data "aws_iam_policy_document" "cognito_triggers" {
  count                   = var.enable_cognito_triggers ? 1 : 0
  source_policy_documents = [data.aws_iam_policy_document.send_audit.json]

  statement {
    sid       = "OwnLogGroup"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.cognito_triggers[0].arn}:*"]
  }
}

resource "aws_iam_role_policy" "cognito_triggers" {
  count  = var.enable_cognito_triggers ? 1 : 0
  name   = "cognito-triggers"
  role   = aws_iam_role.cognito_triggers[0].id
  policy = data.aws_iam_policy_document.cognito_triggers[0].json
}

resource "aws_lambda_function" "cognito_triggers" {
  count            = var.enable_cognito_triggers ? 1 : 0
  function_name    = local.cognito_triggers_name
  description      = "Sends login and password-reset events to the audit queue; fails open"
  role             = aws_iam_role.cognito_triggers[0].arn
  runtime          = "java25"
  architectures    = ["arm64"]
  handler          = "bank.cognito.Handler::handleRequest"
  filename         = var.cognito_triggers_zip
  source_code_hash = filebase64sha256(var.cognito_triggers_zip)
  memory_size      = 1769
  timeout          = 5

  environment {
    variables = {
      AUDIT_QUEUE_URL = aws_sqs_queue.audit.url
    }
  }

  depends_on = [aws_cloudwatch_log_group.cognito_triggers, aws_iam_role_policy.cognito_triggers]
}

resource "aws_lambda_permission" "cognito_triggers" {
  count         = var.enable_cognito_triggers ? 1 : 0
  statement_id  = "AllowThisUserPool"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.cognito_triggers[0].function_name
  principal     = "cognito-idp.amazonaws.com"
  source_arn    = aws_cognito_user_pool.this.arn
}

# The replay role: an operator assumes it to send AUDIT_SEND_FAILED events
# back to the queue (services/cognito-triggers Replay). It is also the role to
# redrive the dead-letter queue with, since the queue policy admits producers
# only.
data "aws_iam_policy_document" "account_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "AWS"
      identifiers = ["arn:${data.aws_partition.current.partition}:iam::${local.account_id}:root"]
    }
  }
}

resource "aws_iam_role" "cognito_triggers_replay" {
  name               = "${local.name}-audit-replay"
  assume_role_policy = data.aws_iam_policy_document.account_assume.json
}

data "aws_iam_policy_document" "cognito_triggers_replay" {
  source_policy_documents = [data.aws_iam_policy_document.send_audit.json]

  statement {
    sid       = "ReadFailedSends"
    actions   = ["logs:FilterLogEvents"]
    resources = var.enable_cognito_triggers ? ["${aws_cloudwatch_log_group.cognito_triggers[0].arn}:*"] : ["arn:${data.aws_partition.current.partition}:logs:${local.region}:${local.account_id}:log-group:/aws/lambda/${local.cognito_triggers_name}:*"]
  }

  statement {
    sid = "RedriveDeadLetters"
    actions = [
      "sqs:StartMessageMoveTask",
      "sqs:ListMessageMoveTasks",
      "sqs:CancelMessageMoveTask",
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes",
    ]
    resources = [aws_sqs_queue.audit_dlq.arn]
  }
}

resource "aws_iam_role_policy" "cognito_triggers_replay" {
  name   = "replay"
  role   = aws_iam_role.cognito_triggers_replay.id
  policy = data.aws_iam_policy_document.cognito_triggers_replay.json
}

# -------------------------------------------------------- transaction ingest
# ADR-0024: a scheduled, VPC-attached Lambda in Go on provided.al2023 with
# reserved concurrency 1. The zip is the receiver's poller, built by
# `make lambda` in services/sftp (infrastructure plan INF-04); it reads its
# secrets from Secrets Manager at start.

# The CRM's SSH key for the bank's SFTP server. The public half goes to the
# sftp-server stack; the private half stays here.
resource "tls_private_key" "sftp_client" {
  algorithm = "ED25519"
}

resource "aws_secretsmanager_secret" "sftp_client_key" {
  name                    = "${local.name}/sftp/client-key"
  description             = "transaction-ingest's SSH private key for the external SFTP server"
  recovery_window_in_days = local.deletion_protection ? 7 : 0
}

resource "aws_secretsmanager_secret_version" "sftp_client_key" {
  secret_id     = aws_secretsmanager_secret.sftp_client_key.id
  secret_string = tls_private_key.sftp_client.private_key_openssh
}

# The server's host key fingerprint, pinned (ADR-0024).
resource "aws_ssm_parameter" "sftp_host_key_fingerprint" {
  count       = var.sftp_host_key_fingerprint == null ? 0 : 1
  name        = "/${local.name}/sftp/host-key-fingerprint"
  description = "SHA256 fingerprint of the external SFTP server's host key"
  type        = "String"
  value       = var.sftp_host_key_fingerprint
}

resource "aws_cloudwatch_log_group" "ingest" {
  count             = var.enable_ingest_lambda ? 1 : 0
  name              = "/aws/lambda/${local.name}-transaction-ingest"
  retention_in_days = local.log_retention_days
}

resource "aws_iam_role" "ingest" {
  count              = var.enable_ingest_lambda ? 1 : 0
  name               = "${local.name}-transaction-ingest"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

resource "aws_iam_role_policy_attachment" "ingest_vpc" {
  count      = var.enable_ingest_lambda ? 1 : 0
  role       = aws_iam_role.ingest[0].name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

data "aws_iam_policy_document" "ingest" {
  count                   = var.enable_ingest_lambda ? 1 : 0
  source_policy_documents = [data.aws_iam_policy_document.send_audit.json]

  statement {
    sid     = "ReadSecretsAtStart"
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      local.db_master_secret["transactions"],
      aws_secretsmanager_secret.sftp_client_key.arn,
      aws_secretsmanager_secret.ingest_client.arn,
    ]
  }
}

resource "aws_iam_role_policy" "ingest" {
  count  = var.enable_ingest_lambda ? 1 : 0
  name   = "transaction-ingest"
  role   = aws_iam_role.ingest[0].id
  policy = data.aws_iam_policy_document.ingest[0].json
}

resource "aws_lambda_function" "ingest" {
  count                          = var.enable_ingest_lambda ? 1 : 0
  function_name                  = "${local.name}-transaction-ingest"
  description                    = "Imports ready transaction files from the external SFTP server (ADR-0024)"
  role                           = aws_iam_role.ingest[0].arn
  runtime                        = "provided.al2023"
  architectures                  = ["arm64"]
  handler                        = "bootstrap"
  filename                       = var.ingest_lambda_zip
  source_code_hash               = filebase64sha256(var.ingest_lambda_zip)
  memory_size                    = 256
  timeout                        = 900 # it starts no file with less than a minute left
  reserved_concurrent_executions = 1   # two runs never take the same files

  vpc_config {
    subnet_ids         = local.app_subnet_ids
    security_group_ids = [local.sg["ingest"]]
  }

  # Secrets are named here, never copied in (ADR-0024 security).
  environment {
    variables = {
      DB_HOST                   = module.db["transactions"].address
      DB_PORT                   = "5432"
      DB_NAME                   = module.db["transactions"].db_name
      DB_SSLMODE                = "verify-full"
      DB_SSLROOTCERT            = "/var/task/rds-ca.pem" # packaged in the zip
      DB_SECRET_ARN             = local.db_master_secret["transactions"]
      SFTP_ADDR                 = coalesce(var.sftp_server_address, "unset:22")
      SFTP_USER                 = "reportuser"
      SFTP_REMOTE_DIR           = "/reports"
      SFTP_HOST_KEY_FINGERPRINT = coalesce(var.sftp_host_key_fingerprint, "unset")
      SFTP_PRIVATE_KEY_SECRET   = aws_secretsmanager_secret.sftp_client_key.arn
      ACCOUNT_SERVICE_BASE_URL  = "${local.alb_scheme}://${aws_lb.internal.dns_name}"
      COGNITO_TOKEN_URL         = "https://${aws_cognito_user_pool_domain.this.domain}.auth.${local.region}.amazoncognito.com/oauth2/token"
      COGNITO_INGEST_SECRET     = aws_secretsmanager_secret.ingest_client.arn
      COGNITO_INGEST_SCOPE      = "crm-accounts/lookup"
      AUDIT_QUEUE_URL           = aws_sqs_queue.audit.url
    }
  }

  depends_on = [aws_cloudwatch_log_group.ingest, aws_iam_role_policy.ingest, aws_iam_role_policy_attachment.ingest_vpc]

  lifecycle {
    precondition {
      condition     = var.ingest_lambda_zip != null && var.sftp_server_address != null && var.sftp_host_key_fingerprint != null
      error_message = "enable_ingest_lambda needs ingest_lambda_zip, sftp_server_address and sftp_host_key_fingerprint."
    }
  }
}

# EventBridge Scheduler invokes the function asynchronously. Lambda would retry
# a failed run twice, which would count toward the circuit breaker's failed
# runs, and would hold a run throttled behind a long one for hours. The next
# scheduled run picks the files up instead.
resource "aws_lambda_function_event_invoke_config" "ingest" {
  count                        = var.enable_ingest_lambda ? 1 : 0
  function_name                = aws_lambda_function.ingest[0].function_name
  maximum_retry_attempts       = 0
  maximum_event_age_in_seconds = 300
}
