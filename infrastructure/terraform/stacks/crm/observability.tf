# Observability baseline (ADR-0027): alarms to one SNS topic, a dashboard,
# and the alarms the service READMEs ask for. Alarm owners are not assigned
# yet (ADR-0027 decision 6); subscribe them to the topic.

resource "aws_sns_topic" "alarms" {
  name = "${local.name}-alarms"
}

data "aws_iam_policy_document" "alarms_topic" {
  statement {
    sid = "AccountOwner"
    principals {
      type        = "AWS"
      identifiers = ["arn:${data.aws_partition.current.partition}:iam::${local.account_id}:root"]
    }
    actions   = ["sns:Publish", "sns:Subscribe", "sns:GetTopicAttributes", "sns:SetTopicAttributes", "sns:ListSubscriptionsByTopic"]
    resources = [aws_sns_topic.alarms.arn]
  }

  statement {
    sid = "AlarmsAndEvents"
    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com", "events.amazonaws.com"]
    }
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alarms.arn]
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }
  }
}

resource "aws_sns_topic_policy" "alarms" {
  arn    = aws_sns_topic.alarms.arn
  policy = data.aws_iam_policy_document.alarms_topic.json
}

resource "aws_sns_topic_subscription" "email" {
  count     = var.alarm_email == null ? 0 : 1
  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

locals {
  alarm_actions    = [aws_sns_topic.alarms.arn]
  metric_namespace = "CRM/${local.name}"
}

# -------------------------------------------------------------- audit queue

resource "aws_cloudwatch_metric_alarm" "audit_dlq" {
  alarm_name          = "${local.name}-audit-dlq-not-empty"
  alarm_description   = "Audit events failed 5 receives and wait in the dead-letter queue. Inspect, fix, then redrive with the audit-replay role. Owner: auditor."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.audit_dlq.name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "audit_backlog" {
  alarm_name          = "${local.name}-audit-oldest-message"
  alarm_description   = "The oldest audit event has waited over 15 minutes: the auditor is down or behind. Owner: auditor."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  dimensions          = { QueueName = aws_sqs_queue.audit.name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 900
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

# ----------------------------------------------------------------- entry

resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name          = "${local.name}-alb-target-5xx"
  alarm_description   = "Services returned more than 10 server errors in 5 minutes."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  dimensions          = { LoadBalancer = aws_lb.internal.arn_suffix }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 10
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "api_latency" {
  alarm_name          = "${local.name}-api-latency"
  alarm_description   = "API p90 latency above 2 seconds for 10 minutes."
  namespace           = "AWS/ApiGateway"
  metric_name         = "Latency"
  dimensions          = { ApiId = aws_apigatewayv2_api.this.id, Stage = "$default" }
  extended_statistic  = "p90"
  period              = 300
  evaluation_periods  = 2
  threshold           = 2000
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

# ------------------------------------------------------- Cognito triggers

resource "aws_cloudwatch_log_metric_filter" "audit_send_failed" {
  count          = var.enable_cognito_triggers ? 1 : 0
  name           = "${local.name}-audit-send-failed"
  log_group_name = aws_cloudwatch_log_group.cognito_triggers[0].name
  pattern        = "{ $.msg = \"AUDIT_SEND_FAILED\" }"

  metric_transformation {
    name          = "AuditSendFailed"
    namespace     = local.metric_namespace
    value         = "1"
    default_value = "0"
  }
}

resource "aws_cloudwatch_metric_alarm" "audit_send_failed" {
  count               = var.enable_cognito_triggers ? 1 : 0
  alarm_name          = "${local.name}-cognito-audit-send-failed"
  alarm_description   = "A login or password reset was not audited. Replay it from the log (services/cognito-triggers README). Owner: Feature 1."
  namespace           = local.metric_namespace
  metric_name         = "AuditSendFailed"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "cognito_triggers_errors" {
  count               = var.enable_cognito_triggers ? 1 : 0
  alarm_name          = "${local.name}-cognito-triggers-errors"
  alarm_description   = "The trigger failed, so Cognito refused that sign-in. It should always be 0. Owner: Feature 1."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.cognito_triggers[0].function_name }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "cognito_triggers_duration" {
  count               = var.enable_cognito_triggers ? 1 : 0
  alarm_name          = "${local.name}-cognito-triggers-slow"
  alarm_description   = "A trigger ran over 4 seconds, close to Cognito's 5-second limit. Owner: Feature 1."
  namespace           = "AWS/Lambda"
  metric_name         = "Duration"
  dimensions          = { FunctionName = aws_lambda_function.cognito_triggers[0].function_name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 4000
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

# ------------------------------------------------------------ chain-verify

resource "aws_cloudwatch_log_metric_filter" "chain_tamper" {
  name           = "${local.name}-chain-tamper-detected"
  log_group_name = module.chain_verify.log_group_name
  pattern        = "\"CHAIN_TAMPER_DETECTED\""

  metric_transformation {
    name          = "ChainTamperDetected"
    namespace     = local.metric_namespace
    value         = "1"
    default_value = "0"
  }
}

resource "aws_cloudwatch_metric_alarm" "chain_tamper" {
  alarm_name          = "${local.name}-chain-tamper-detected"
  alarm_description   = "chain-verify found a new problem in the audit chain (ADR-0022). Owner: auditor."
  namespace           = local.metric_namespace
  metric_name         = "ChainTamperDetected"
  statistic           = "Sum"
  period              = 3600
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

# A chain-verify run that exits non-zero could not finish its check.
resource "aws_cloudwatch_event_rule" "chain_verify_failed" {
  name        = "${local.name}-chain-verify-failed"
  description = "chain-verify stopped with a non-zero exit code"
  event_pattern = jsonencode({
    source        = ["aws.ecs"]
    "detail-type" = ["ECS Task State Change"]
    detail = {
      clusterArn = [aws_ecs_cluster.this.arn]
      group      = ["family:${module.chain_verify.task_definition_family}"]
      lastStatus = ["STOPPED"]
      containers = {
        name     = ["chain-verify"]
        exitCode = [{ "anything-but" = 0 }]
      }
    }
  })
}

resource "aws_cloudwatch_event_target" "chain_verify_failed" {
  rule = aws_cloudwatch_event_rule.chain_verify_failed.name
  arn  = aws_sns_topic.alarms.arn
}

# --------------------------------------------------------------- ingestion

resource "aws_cloudwatch_metric_alarm" "ingest_errors" {
  count               = var.enable_ingest_lambda ? 1 : 0
  alarm_name          = "${local.name}-transaction-ingest-errors"
  alarm_description   = "A transaction import run failed. Owner: Feature 3."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.ingest[0].function_name }
  statistic           = "Sum"
  period              = 900
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

# --------------------------------------------------------------- dashboard

resource "aws_cloudwatch_dashboard" "this" {
  dashboard_name = local.name
  dashboard_body = jsonencode({
    widgets = [
      {
        type = "metric", x = 0, y = 0, width = 12, height = 6
        properties = {
          title  = "API Gateway"
          region = local.region
          stat   = "Sum"
          period = 300
          metrics = [
            ["AWS/ApiGateway", "Count", "ApiId", aws_apigatewayv2_api.this.id],
            [".", "4xx", ".", "."],
            [".", "5xx", ".", "."],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 0, width = 12, height = 6
        properties = {
          title  = "ALB targets"
          region = local.region
          stat   = "Sum"
          period = 300
          metrics = [
            ["AWS/ApplicationELB", "RequestCount", "LoadBalancer", aws_lb.internal.arn_suffix],
            [".", "HTTPCode_Target_5XX_Count", ".", "."],
          ]
        }
      },
      {
        type = "metric", x = 0, y = 6, width = 12, height = 6
        properties = {
          title  = "Audit queue"
          region = local.region
          stat   = "Maximum"
          period = 300
          metrics = [
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", aws_sqs_queue.audit.name],
            [".", "ApproximateAgeOfOldestMessage", ".", "."],
            [".", "ApproximateNumberOfMessagesVisible", ".", aws_sqs_queue.audit_dlq.name],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 6, width = 12, height = 6
        properties = {
          title  = "ECS CPU by service"
          region = local.region
          stat   = "Average"
          period = 300
          metrics = [for s in ["user-service", "client-service", "account-service", "auditor", "transaction-service", "dashboard-service", "event-alert-service"] :
            ["AWS/ECS", "CPUUtilization", "ClusterName", aws_ecs_cluster.this.name, "ServiceName", s]
          ]
        }
      },
      {
        type = "metric", x = 0, y = 12, width = 24, height = 6
        properties = {
          title  = "RDS CPU"
          region = local.region
          stat   = "Average"
          period = 300
          metrics = [for k, db in module.db :
            ["AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", db.identifier]
          ]
        }
      },
    ]
  })
}
