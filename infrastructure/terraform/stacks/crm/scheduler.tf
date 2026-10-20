# EventBridge Scheduler (ADR-0022, ADR-0024): chain-verify daily, and the
# transaction import every 5 minutes once its Lambda exists. Schedules pause
# while the stack is stopped. The alert worker's schedule is in
# stacks/alert-worker (ADR-0048).

data "aws_iam_policy_document" "scheduler_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }
  }
}

resource "aws_iam_role" "scheduler" {
  name               = "${local.name}-scheduler"
  assume_role_policy = data.aws_iam_policy_document.scheduler_assume.json
}

data "aws_iam_policy_document" "scheduler" {
  statement {
    sid       = "StartChainVerify"
    actions   = ["ecs:RunTask"]
    resources = ["arn:${data.aws_partition.current.partition}:ecs:${local.region}:${local.account_id}:task-definition/${module.chain_verify.task_definition_family}:*"]
    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [aws_ecs_cluster.this.arn]
    }
  }

  statement {
    sid       = "PassChainVerifyRoles"
    actions   = ["iam:PassRole"]
    resources = [module.chain_verify.task_role_arn, module.chain_verify.execution_role_arn]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  dynamic "statement" {
    for_each = var.enable_ingest_lambda ? [1] : []
    content {
      sid       = "InvokeIngest"
      actions   = ["lambda:InvokeFunction"]
      resources = [aws_lambda_function.ingest[0].arn]
    }
  }
}

resource "aws_iam_role_policy" "scheduler" {
  name   = "start-jobs"
  role   = aws_iam_role.scheduler.id
  policy = data.aws_iam_policy_document.scheduler.json
}

resource "aws_scheduler_schedule" "chain_verify" {
  name                         = "${local.name}-chain-verify"
  description                  = "Daily hash-chain check and S3 Object Lock anchor (ADR-0022)"
  schedule_expression          = "cron(0 3 * * ? *)"
  schedule_expression_timezone = "Asia/Singapore"
  state                        = var.running ? "ENABLED" : "DISABLED"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = aws_ecs_cluster.this.arn
    role_arn = aws_iam_role.scheduler.arn

    ecs_parameters {
      task_definition_arn = module.chain_verify.task_definition_arn
      launch_type         = "FARGATE"
      task_count          = 1

      network_configuration {
        subnets          = local.app_subnet_ids
        security_groups  = [local.sg["chain_verify"]]
        assign_public_ip = false
      }
    }

    retry_policy {
      maximum_retry_attempts = 2
    }
  }
}

resource "aws_scheduler_schedule" "ingest" {
  count                        = var.enable_ingest_lambda ? 1 : 0
  name                         = "${local.name}-transaction-ingest"
  description                  = "Import ready transaction files (ADR-0024)"
  schedule_expression          = "rate(5 minutes)"
  schedule_expression_timezone = "Asia/Singapore"
  state                        = var.running ? "ENABLED" : "DISABLED"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = aws_lambda_function.ingest[0].arn
    role_arn = aws_iam_role.scheduler.arn

    retry_policy {
      maximum_retry_attempts = 0 # the next run picks the files up
    }
  }
}
