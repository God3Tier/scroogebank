# Account-wide resources, applied once per AWS account before any CRM stack
# (docs/runbooks/deploy-aws.md step 1). Its own state is local: it creates the
# bucket the other stacks keep their state in. Keep terraform.tfstate from
# this directory somewhere safe, or import the resources again if it is lost.

terraform {
  required_version = ">= 1.10.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
  default_tags {
    tags = {
      Project   = "cs301-crm"
      ManagedBy = "terraform"
      Stack     = "bootstrap"
    }
  }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  account_id   = data.aws_caller_identity.current.account_id
  state_bucket = coalesce(var.state_bucket_name, "crm-tfstate-${local.account_id}")
}

# ------------------------------------------------------- Terraform state
# S3 with native locking (use_lockfile), versioned and encrypted (ADR-0042
# decision 1). The state holds secrets such as the project CA's private key,
# so only administrators may read it.

resource "aws_s3_bucket" "state" {
  bucket = local.state_bucket
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    id     = "expire-old-state-versions"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }
}

data "aws_iam_policy_document" "state" {
  statement {
    sid    = "TlsOnly"
    effect = "Deny"
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.state.arn, "${aws_s3_bucket.state.arn}/*"]
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "state" {
  bucket     = aws_s3_bucket.state.id
  policy     = data.aws_iam_policy_document.state.json
  depends_on = [aws_s3_bucket_public_access_block.state]
}

# --------------------------------------------------------- GitHub OIDC
# One provider per account; each CRM stack makes its own deploy role.

resource "aws_iam_openid_connect_provider" "github" {
  count          = var.enable_github_oidc ? 1 : 0
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

# ------------------------------------------------------------ CloudTrail
# One management-events trail is free (ADR-0027 decision 5). It also records
# data events on the audit anchor buckets (ADR-0022).

resource "aws_s3_bucket" "trail" {
  bucket        = "crm-cloudtrail-${local.account_id}"
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "trail" {
  bucket                  = aws_s3_bucket.trail.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "trail" {
  bucket = aws_s3_bucket.trail.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "trail" {
  bucket = aws_s3_bucket.trail.id
  rule {
    id     = "expire"
    status = "Enabled"
    filter {}
    expiration {
      days = var.trail_retention_days
    }
  }
}

locals {
  trail_name = "crm-account-trail"
}

data "aws_iam_policy_document" "trail" {
  statement {
    sid = "AclCheck"
    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }
    actions   = ["s3:GetBucketAcl"]
    resources = [aws_s3_bucket.trail.arn]
    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = ["arn:${data.aws_partition.current.partition}:cloudtrail:${var.aws_region}:${local.account_id}:trail/${local.trail_name}"]
    }
  }

  statement {
    sid = "Write"
    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.trail.arn}/AWSLogs/${local.account_id}/*"]
    condition {
      test     = "StringEquals"
      variable = "s3:x-amz-acl"
      values   = ["bucket-owner-full-control"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = ["arn:${data.aws_partition.current.partition}:cloudtrail:${var.aws_region}:${local.account_id}:trail/${local.trail_name}"]
    }
  }
}

resource "aws_s3_bucket_policy" "trail" {
  bucket     = aws_s3_bucket.trail.id
  policy     = data.aws_iam_policy_document.trail.json
  depends_on = [aws_s3_bucket_public_access_block.trail]
}

resource "aws_cloudtrail" "account" {
  name                          = local.trail_name
  s3_bucket_name                = aws_s3_bucket.trail.id
  is_multi_region_trail         = true
  include_global_service_events = true
  enable_log_file_validation    = true

  advanced_event_selector {
    name = "Management events"
    field_selector {
      field  = "eventCategory"
      equals = ["Management"]
    }
  }

  advanced_event_selector {
    name = "Audit anchor objects"
    field_selector {
      field  = "eventCategory"
      equals = ["Data"]
    }
    field_selector {
      field  = "resources.type"
      equals = ["AWS::S3::Object"]
    }
    # Every CRM stack names its anchor bucket crm-audit-anchor-<entity>-...
    field_selector {
      field       = "resources.ARN"
      starts_with = ["arn:${data.aws_partition.current.partition}:s3:::crm-audit-anchor-"]
    }
  }

  depends_on = [aws_s3_bucket_policy.trail]
}

# ------------------------------------------------------------- GuardDuty
# Core detection (ADR-0027 decision 5).

resource "aws_guardduty_detector" "this" {
  enable = true
}

# --------------------------------------------------------------- budgets
# ADR-0029: alerts at 50, 70, 85, 95 and 100% of the USD 300 hard limit, and a
# separate one for Bedrock.
#
# Both count cost before credits. By default AWS Budgets subtracts credits,
# so on an account whose credits cover the bill, the cost stays near 0 and no
# alert would ever fire.

resource "aws_budgets_budget" "total" {
  name         = "crm-total"
  budget_type  = "COST"
  limit_amount = tostring(var.total_budget_usd)
  limit_unit   = "USD"

  # The USD 300 is a limit for the whole project, not for each month, so this
  # is one yearly period from the project's start, not a monthly one that
  # resets.
  time_unit         = "ANNUALLY"
  time_period_start = "${var.budget_start_date}_00:00"

  cost_types {
    include_credit = false
  }

  dynamic "notification" {
    for_each = [50, 70, 85, 95, 100]
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value
      threshold_type             = "PERCENTAGE"
      notification_type          = "ACTUAL"
      subscriber_email_addresses = var.budget_emails
    }
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = var.budget_emails
  }
}

resource "aws_budgets_budget" "bedrock" {
  name         = "crm-bedrock"
  budget_type  = "COST"
  limit_amount = tostring(var.bedrock_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  cost_filter {
    name   = "Service"
    values = ["Amazon Bedrock"]
  }

  cost_types {
    include_credit = false
  }

  dynamic "notification" {
    for_each = [50, 80, 100]
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value
      threshold_type             = "PERCENTAGE"
      notification_type          = "ACTUAL"
      subscriber_email_addresses = var.budget_emails
    }
  }
}
