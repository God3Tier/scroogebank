# The event-alert worker (X-factor 1, ADR-0048) in its own stack: one ingestion
# window per run, started by EventBridge Scheduler on an ECS Fargate task. It
# runs inside the CRM stack's cluster, subnets and alerts security group, and
# reads them from that stack's alerts_worker_inputs output. It is the same
# image as the event-alert-service API, started with ALERT_MODE=WORKER
# (ADR-0007), so it needs no repository, database or security group of its own.
# Apply the CRM stack first and destroy this one first. The Tavily search API
# is called directly from this worker (ADR-0050).

data "terraform_remote_state" "crm" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = "crm/${var.legal_entity}/${var.environment}.tfstate"
    region = var.aws_region
  }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  crm        = data.terraform_remote_state.crm.outputs.alerts_worker_inputs
  name       = local.crm.name_prefix
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition

  # The worker only does anything when ingestion is on. With it off a run exits
  # at once as DISABLED, so the schedule stays off and saves the task starts.
  # The CRM stack's running variable pauses it too (ADR-0029): apply this
  # stack again after every CRM apply that changes it.
  schedule_on = local.crm.running && var.ingestion_enabled

  # Holdings come from the same place as the API's (ADR-0066): the CRM stack decides, so the
  # two cannot disagree. In database mode the worker needs the directory's HTTPS URL, token
  # endpoint, app client and the client's secret; each is required and absent values stop the
  # worker at start-up rather than falling back to fixture data.
  database_holdings = local.crm.holdings_mode == "database"

  # The global inference profile the service pins (application.yml
  # alert.ai.model), and the foundation model behind it. A global profile can
  # route to any Region, so the model ARN is Region-wildcarded and tied to the
  # profile by the bedrock:InferenceProfileArn condition.
  #
  # Nova 2 Lite is the accepted production model (ADR-0050). It is called from
  # Singapore through its global inference profile; live-news retrieval is a
  # separate, direct Tavily call and does not require a Tokyo Bedrock endpoint.
  bedrock_profile_id  = "global.amazon.nova-2-lite-v1:0"
  bedrock_model_id    = "amazon.nova-2-lite-v1:0"
  bedrock_profile_arn = "arn:${local.partition}:bedrock:${var.bedrock_region}:${local.account_id}:inference-profile/${local.bedrock_profile_id}"
}

# ----------------------------------------------------------------- the task

data "aws_iam_policy_document" "bedrock" {
  statement {
    sid       = "InvokeThePinnedProfile"
    actions   = ["bedrock:InvokeModel"] # the Converse API is authorised as InvokeModel
    resources = [local.bedrock_profile_arn]
  }

  statement {
    sid       = "InvokeTheModelOnlyThroughThatProfile"
    actions   = ["bedrock:InvokeModel"]
    resources = ["arn:${local.partition}:bedrock:*::foundation-model/${local.bedrock_model_id}"]
    condition {
      test     = "StringLike"
      variable = "bedrock:InferenceProfileArn"
      values   = [local.bedrock_profile_arn]
    }
  }
}

data "aws_iam_policy_document" "metrics" {
  statement {
    sid       = "PutWorkerMetricsToOneNamespace"
    actions   = ["cloudwatch:PutMetricData"]
    resources = ["*"] # PutMetricData has no resource-level ARN; the condition narrows it
    condition {
      test     = "StringEquals"
      variable = "cloudwatch:namespace"
      values   = [var.cloudwatch_namespace]
    }
  }
}

resource "aws_secretsmanager_secret" "tavily" {
  # Prefix permits a clean redeploy while an old destroyed stack's secret is
  # still inside its seven-day recovery window.
  name_prefix             = "${local.name}-alert-worker-tavily-"
  description             = "Tavily API key for the event-alert worker (populate out of band; never put the value in Terraform)"
  recovery_window_in_days = 7
}

module "event_alert_worker" {
  source = "../../modules/ecs-service"

  name               = "event-alert-worker"
  name_prefix        = local.name
  create_service     = false
  cluster_arn        = local.crm.cluster_arn
  cluster_name       = local.crm.cluster_name
  subnet_ids         = local.crm.subnet_ids
  security_group_ids = [local.crm.alerts_service_sg]
  image              = local.crm.image
  cpu                = 512
  memory             = 1024
  cpu_architecture   = local.crm.cpu_architecture

  environment = merge({
    ALERT_MODE                          = "WORKER"
    ALERT_HOLDINGS_MODE                 = local.crm.holdings_mode
    EVENT_ALERT_DB_URL                  = "jdbc:postgresql://${local.crm.alerts_db_address}:5432/${local.crm.alerts_db_name}?sslmode=require"
    SPRING_FLYWAY_ENABLED               = "false" # db-migrate applies the migrations, as for the API (found by the AWS test)
    AWS_REGION                          = var.aws_region
    ALERT_INGESTION_ENABLED             = tostring(var.ingestion_enabled)
    ALERT_SEARCH_PROVIDER               = "tavily"
    ALERT_MINIMUM_SEARCH_CLIENT_SHARE   = tostring(var.minimum_search_client_share)
    ALERT_MINIMUM_ACCOUNT_CONCENTRATION = tostring(var.minimum_account_concentration)
    ALERT_INGESTION_APPROVED_DOMAINS    = join(",", var.approved_news_domains)
    ALERT_AI_ENABLED                    = tostring(var.ai_enabled)
    ALERT_AI_PROVIDER                   = var.ai_enabled ? "bedrock" : "none"
    ALERT_AI_BEDROCK_REGION             = var.bedrock_region
    # The API process publishes the audit outbox, including rows a worker run
    # leaves behind, so the worker needs no queue access.
    ALERT_AUDIT_PUBLISHER_ENABLED    = "false"
    ALERT_CLOUDWATCH_METRICS_ENABLED = tostring(var.cloudwatch_metrics_enabled)
    ALERT_CLOUDWATCH_NAMESPACE       = var.cloudwatch_namespace
    },
    # Keep the task definition explicit even though application.yml has the
    # same default, so a deployment cannot drift when application defaults move.
    !var.ai_enabled ? {} : { ALERT_AI_MODEL = local.bedrock_profile_id },
    !local.database_holdings ? {} : {
      ALERT_CRM_ACCOUNT_DIRECTORY_URL = local.crm.crm_account_directory_url
      ALERT_CRM_TOKEN_ENDPOINT        = local.crm.crm_token_endpoint
      ALERT_CRM_CLIENT_ID             = local.crm.crm_client_id
      ALERT_CRM_SCOPE                 = local.crm.crm_scope
    },
  )
  secrets = merge(
    {
      EVENT_ALERT_DB_USERNAME = "${local.crm.alerts_db_secret_arn}:username::"
      EVENT_ALERT_DB_PASSWORD = "${local.crm.alerts_db_secret_arn}:password::"
      ALERT_TAVILY_API_KEY    = aws_secretsmanager_secret.tavily.arn
    },
    !local.database_holdings ? {} : { ALERT_CRM_CLIENT_SECRET = "${local.crm.crm_client_secret_arn}:client_secret::" },
  )
  secret_arns = concat(
    [local.crm.alerts_db_secret_arn, aws_secretsmanager_secret.tavily.arn],
    local.database_holdings ? [local.crm.crm_client_secret_arn] : [],
  )

  # Only what the switched-on capabilities need (least privilege). The keys
  # depend on variables only, so they are known at plan time.
  task_role_policies = merge(
    var.ai_enabled ? { bedrock = data.aws_iam_policy_document.bedrock.json } : {},
    var.cloudwatch_metrics_enabled ? { metrics = data.aws_iam_policy_document.metrics.json } : {},
  )

  use_spot           = local.crm.use_spot
  log_retention_days = local.crm.log_retention_days
}

# -------------------------------------------------------------- the schedule

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
  name               = "${local.name}-alert-worker-scheduler"
  assume_role_policy = data.aws_iam_policy_document.scheduler_assume.json
}

data "aws_iam_policy_document" "scheduler" {
  statement {
    sid       = "StartAlertWorker"
    actions   = ["ecs:RunTask"]
    resources = ["arn:${local.partition}:ecs:${var.aws_region}:${local.account_id}:task-definition/${module.event_alert_worker.task_definition_family}:*"]
    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [local.crm.cluster_arn]
    }
  }

  statement {
    sid       = "PassAlertWorkerRoles"
    actions   = ["iam:PassRole"]
    resources = [module.event_alert_worker.task_role_arn, module.event_alert_worker.execution_role_arn]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "scheduler" {
  name   = "start-alert-worker"
  role   = aws_iam_role.scheduler.id
  policy = data.aws_iam_policy_document.scheduler.json
}

resource "aws_scheduler_schedule" "alert_worker" {
  name                         = "${local.name}-alert-worker"
  description                  = "One alert ingestion window every four hours at fixed times (ADR-0031, ADR-0048, ADR-0081)"
  schedule_expression          = var.schedule
  schedule_expression_timezone = "Asia/Singapore"
  state                        = local.schedule_on ? "ENABLED" : "DISABLED"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = local.crm.cluster_arn
    role_arn = aws_iam_role.scheduler.arn

    ecs_parameters {
      task_definition_arn = module.event_alert_worker.task_definition_arn
      launch_type         = "FARGATE"
      task_count          = 1

      network_configuration {
        subnets          = local.crm.subnet_ids
        security_groups  = [local.crm.alerts_service_sg]
        assign_public_ip = false
      }
    }

    # A failed run is reported by the alarm below. Retrying would repeat the
    # searches, and the next scheduled run resumes from the watermark anyway.
    retry_policy {
      maximum_retry_attempts = 0
    }
  }
}

# ------------------------------------------------------ a run on every apply

# A schedule's first run comes at its next fixed time, up to four hours after it is created or
# switched on, so a fresh deployment would otherwise show no alerts for hours.
# This starts one run at the end of every apply (ADR-0080); the schedule then
# carries on as before. The worker's run lock stops it overlapping a scheduled
# run, but a run started here does search, so it spends that run's Tavily
# credits. It needs the AWS CLI and the applier's credentials; a failed start
# only warns, because the schedule still runs. Turn it off with
# run_on_apply = false.
resource "terraform_data" "run_on_apply" {
  count = local.schedule_on && var.run_on_apply ? 1 : 0

  # A new value on every apply, so the run starts every time.
  triggers_replace = timestamp()

  provisioner "local-exec" {
    command    = "aws ecs run-task --region ${var.aws_region} --cluster ${local.crm.cluster_name} --launch-type FARGATE --task-definition ${module.event_alert_worker.task_definition_arn} --network-configuration \"awsvpcConfiguration={subnets=[${join(",", local.crm.subnet_ids)}],securityGroups=[${local.crm.alerts_service_sg}],assignPublicIp=DISABLED}\" --query tasks[0].taskArn --output text"
    on_failure = continue
  }

  depends_on = [aws_scheduler_schedule.alert_worker]
}

# ---------------------------------------------------------------- the alarm

# The worker exits 1 when its window failed (WorkerRunner), so a non-zero exit
# is the alarm signal (ADR-0031: an alarm on failed runs). It reports to the
# CRM stack's alarm topic, as chain-verify's does.
resource "aws_cloudwatch_event_rule" "failed" {
  name        = "${local.name}-alert-worker-failed"
  description = "The alert worker stopped with a non-zero exit code"
  event_pattern = jsonencode({
    source        = ["aws.ecs"]
    "detail-type" = ["ECS Task State Change"]
    detail = {
      clusterArn = [local.crm.cluster_arn]
      group      = ["family:${module.event_alert_worker.task_definition_family}"]
      lastStatus = ["STOPPED"]
      containers = {
        name     = ["event-alert-worker"]
        exitCode = [{ "anything-but" = 0 }]
      }
    }
  })
}

resource "aws_cloudwatch_event_target" "failed" {
  rule = aws_cloudwatch_event_rule.failed.name
  arn  = local.crm.alarm_topic_arn
}
