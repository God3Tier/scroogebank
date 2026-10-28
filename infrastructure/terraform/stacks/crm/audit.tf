# Audit pipeline (ADR-0020, ADR-0022): one shared standard queue with a
# dead-letter queue after 5 receives, and the S3 Object Lock bucket that holds
# chain-verify's daily anchors.

resource "aws_sqs_queue" "audit_dlq" {
  name                      = "${local.name}-audit-insert-dlq"
  message_retention_seconds = 14 * 24 * 3600
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "audit" {
  name                       = "${local.name}-audit-insert"
  message_retention_seconds  = 14 * 24 * 3600
  visibility_timeout_seconds = 60
  receive_wait_time_seconds  = 20
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.audit_dlq.arn
    maxReceiveCount     = 5
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "audit_dlq" {
  queue_url = aws_sqs_queue.audit_dlq.id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.audit.arn]
  })
}

locals {
  # Roles allowed to send audit events. The queue alone does not prove which
  # service sent an event (open item 18); this policy at least limits sending
  # to these roles. dashboard-service's role comes in through
  # extra_audit_producer_role_arns (ADR-0047).
  audit_producer_role_arns = compact(concat(
    [
      module.user_service.task_role_arn,
      module.client_service.task_role_arn,
      module.account_service.task_role_arn,
      module.transaction_service.task_role_arn,
      module.event_alert_service.task_role_arn,
      module.client_health_service.task_role_arn,
      aws_iam_role.cognito_triggers_replay.arn,
    ],
    var.enable_cognito_triggers ? [aws_iam_role.cognito_triggers[0].arn] : [],
    var.enable_ingest_lambda ? [aws_iam_role.ingest[0].arn] : [],
    var.extra_audit_producer_role_arns,
  ))
}

data "aws_iam_policy_document" "audit_queue" {
  statement {
    sid    = "ProducersOnly"
    effect = "Deny"
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.audit.arn]
    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values   = local.audit_producer_role_arns
    }
  }

  statement {
    sid    = "TlsOnly"
    effect = "Deny"
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    actions   = ["sqs:*"]
    resources = [aws_sqs_queue.audit.arn]
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_sqs_queue_policy" "audit" {
  queue_url = aws_sqs_queue.audit.id
  policy    = data.aws_iam_policy_document.audit_queue.json
}

# ------------------------------------------------------------ anchor bucket

resource "aws_s3_bucket" "anchor" {
  # The prefix is what the account trail's data-event selector matches
  # (bootstrap/main.tf).
  bucket              = "crm-audit-anchor-${var.legal_entity}-${var.environment}-${local.account_id}"
  object_lock_enabled = true
  # Locked anchors (governance mode, 7 days) are removed at teardown by a role
  # with s3:BypassGovernanceRetention (runbook "Tear down").
  force_destroy = !local.deletion_protection
}

resource "aws_s3_bucket_versioning" "anchor" {
  bucket = aws_s3_bucket.anchor.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_public_access_block" "anchor" {
  bucket                  = aws_s3_bucket.anchor.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "anchor" {
  bucket = aws_s3_bucket.anchor.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Only chain-verify may write anchors (ADR-0022 decision 3).
data "aws_iam_policy_document" "anchor" {
  statement {
    sid    = "OnlyChainVerifyWrites"
    effect = "Deny"
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    actions   = ["s3:PutObject", "s3:PutObjectRetention", "s3:PutObjectLegalHold"]
    resources = ["${aws_s3_bucket.anchor.arn}/*"]
    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values   = [module.chain_verify.task_role_arn]
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
    resources = [aws_s3_bucket.anchor.arn, "${aws_s3_bucket.anchor.arn}/*"]
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "anchor" {
  bucket     = aws_s3_bucket.anchor.id
  policy     = data.aws_iam_policy_document.anchor.json
  depends_on = [aws_s3_bucket_public_access_block.anchor]
}
