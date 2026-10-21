# Agent engagement (ADR-0051) in its own stack, built like the business
# dashboard's (ADR-0047): engagement-service's image repository, security
# groups, engagement-db, TLS certificate and ECS service. It runs inside the
# CRM stack's VPC, cluster, internal ALB and Service Connect namespace, read
# from that stack's engagement_inputs output, and sends through the SES
# identity of the long-lived mail-identity stack (ADR-0053, ADR-0055). Apply
# mail-identity once, then the CRM stack, then this one; destroy this one
# before the CRM stack.

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
  crm  = data.terraform_remote_state.crm.outputs.engagement_inputs
  name = local.crm.name_prefix
  port = 8086

  account   = data.aws_caller_identity.current.account_id
  partition = data.aws_partition.current.partition

  # The ecs-service module names the task role <prefix>-<service>-task, so the
  # CRM stack can allow it on the audit queue before this stack exists.
  task_role_arn = "arn:${local.partition}:iam::${local.account}:role/${local.name}-engagement-service-task"

  # The mail-identity stack's names (ADR-0053 decisions 1 and 3). Without the
  # CRM stack's custom domain, mail comes from sender_address, and its domain
  # is the calendar UID suffix (ADR-0055).
  domain_mode            = local.crm.app_host != null
  sender_address         = coalesce(var.sender_address, "sender-address-unset@example.invalid")
  mail_domain            = local.domain_mode ? "mail.${local.crm.app_host}" : split("@", local.sender_address)[1]
  from_address           = local.domain_mode ? "no-reply@${local.mail_domain}" : local.sender_address
  identity_name          = local.domain_mode ? local.mail_domain : local.sender_address
  configuration_set_name = "${local.name}-engagement"

  # ADR-0051: at least 1 task and at most 2 while the CRM stack runs, none
  # while it is stopped.
  min_tasks = local.crm.min_tasks > 0 ? 1 : 0
  max_tasks = local.crm.max_tasks > 0 ? 2 : 0

  db_url = "jdbc:postgresql://${module.db.address}:5432/${module.db.db_name}"
}

# ------------------------------------------------------------ mail identity
# Read by name, so a missing or misnamed mail-identity stack fails the plan
# here instead of every send failing later.

data "aws_sesv2_email_identity" "mail" {
  email_identity = local.identity_name

  lifecycle {
    precondition {
      condition     = local.domain_mode || var.sender_address != null
      error_message = "The CRM stack has no domain_name, so there is no sending domain. Set sender_address here and in the mail-identity stack, and apply that stack (ADR-0055)."
    }
  }
}

data "aws_sesv2_configuration_set" "mail" {
  configuration_set_name = local.configuration_set_name
}

# -------------------------------------------------------------------- image

resource "aws_ecr_repository" "engagement" {
  name                 = "${local.name}/engagement-service"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = !local.crm.deletion_protection

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "engagement" {
  repository = aws_ecr_repository.engagement.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the 10 newest images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}

# ----------------------------------------------------------------- network
# The allowed-traffic chain (ADR-0019 decision 4): the internal ALB to the
# service, the service to client-service and to its own database.

resource "aws_security_group" "engagement" {
  name        = "${local.name}-engagement"
  description = "engagement-service tasks"
  vpc_id      = local.crm.vpc_id
  tags        = { Name = "${local.name}-engagement" }

  lifecycle {
    # The audit queue denies every sender not on its list, so without this
    # the service would run but lose its audit events.
    precondition {
      condition     = contains(local.crm.audit_producer_role_arns, local.task_role_arn)
      error_message = "The audit queue does not allow ${local.task_role_arn}. Add it to the CRM stack's extra_audit_producer_role_arns and apply that stack first."
    }
  }
}

resource "aws_vpc_security_group_ingress_rule" "from_alb" {
  security_group_id            = aws_security_group.engagement.id
  referenced_security_group_id = local.crm.alb_sg
  ip_protocol                  = "tcp"
  from_port                    = local.port
  to_port                      = local.port
  description                  = "alb to engagement"
}

resource "aws_vpc_security_group_egress_rule" "anywhere" {
  security_group_id = aws_security_group.engagement.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
  description       = "SES, Bedrock, Cognito and SQS through the NAT gateway, client-service and engagement-db"
}

resource "aws_vpc_security_group_ingress_rule" "to_client_service" {
  security_group_id            = local.crm.client.security_group
  referenced_security_group_id = aws_security_group.engagement.id
  ip_protocol                  = "tcp"
  from_port                    = local.crm.client.port
  to_port                      = local.crm.client.port
  description                  = "engagement to client"
}

resource "aws_security_group" "db" {
  name        = "${local.name}-engagement-db"
  description = "engagement-db"
  vpc_id      = local.crm.vpc_id
  tags        = { Name = "${local.name}-engagement-db" }
}

resource "aws_vpc_security_group_ingress_rule" "db_from_engagement" {
  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = aws_security_group.engagement.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "engagement to engagement-db"
}

# ---------------------------------------------------------------- database
# engagement-db on its own db.t4g.micro, single-AZ even in the
# high-availability week (ADR-0051 decision 1). It is tagged for AWS Backup
# in prod, as the CRM's databases are.

module "db" {
  source = "../../modules/postgres"

  identifier            = "${local.name}-engagement-db"
  db_name               = "engagement"
  instance_class        = local.crm.db.instance_class
  allocated_storage     = local.crm.db.allocated_storage
  max_allocated_storage = local.crm.db.max_allocated_storage
  backup_retention_days = local.crm.db.backup_retention_days
  multi_az              = false
  deletion_protection   = local.crm.db.deletion_protection
  skip_final_snapshot   = local.crm.db.skip_final_snapshot
  kms_key_arn           = local.crm.db.kms_key_arn
  subnet_group_name     = local.crm.db.subnet_group_name
  security_group_id     = aws_security_group.db.id
  parameter_group_name  = local.crm.db.parameter_group_name
  backup_tag            = local.crm.db.backup_tag
}

# The service's login reads and changes rows but cannot change the schema;
# db-migrate creates it and sets its password on every start (ADR-0028
# decision 3, infrastructure/db-migrate/app-login/afterMigrate.sql).
resource "random_password" "app_login" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "app_login" {
  name                    = "${local.name}/db/engagement/engagement_service"
  description             = "engagement-db login without schema rights"
  recovery_window_in_days = local.crm.deletion_protection ? 7 : 0
}

resource "aws_secretsmanager_secret_version" "app_login" {
  secret_id = aws_secretsmanager_secret.app_login.id
  secret_string = jsonencode({
    username = "engagement_service"
    password = random_password.app_login.result
  })
}

# --------------------------------------------------------------------- TLS
# The internal ALB reaches the service over HTTPS (INF-08) and does not check
# its targets' certificates, so the certificate is self-signed, as the
# business dashboard's is.

resource "tls_private_key" "engagement" {
  algorithm   = "ECDSA"
  ecdsa_curve = "P256"
}

resource "tls_self_signed_cert" "engagement" {
  private_key_pem = tls_private_key.engagement.private_key_pem
  dns_names       = ["engagement-service"]

  subject {
    common_name = "engagement-service"
  }

  validity_period_hours = 24 * 365
  early_renewal_hours   = 24 * 30
  allowed_uses          = ["digital_signature", "key_encipherment", "server_auth"]
}

resource "aws_secretsmanager_secret" "tls" {
  name                    = "${local.name}/tls/engagement"
  description             = "Self-signed TLS certificate and key for engagement-service"
  recovery_window_in_days = local.crm.deletion_protection ? 7 : 0
}

resource "aws_secretsmanager_secret_version" "tls" {
  secret_id = aws_secretsmanager_secret.tls.id
  secret_string = jsonencode({
    cert = tls_self_signed_cert.engagement.cert_pem
    key  = tls_private_key.engagement.private_key_pem
  })
}

# ------------------------------------------------------------ permissions

data "aws_iam_policy_document" "engagement" {
  statement {
    sid       = "SendAuditEvents"
    actions   = ["sqs:SendMessage"]
    resources = [local.crm.audit_queue_arn]
  }

  # Only as the sending identity, through the configuration set, from the
  # no-reply address or sender_address (ADR-0053 decision 9, ADR-0055).
  statement {
    sid       = "SendEngagementMail"
    actions   = ["ses:SendEmail", "ses:SendRawEmail"]
    resources = [data.aws_sesv2_email_identity.mail.arn, data.aws_sesv2_configuration_set.mail.arn]
    condition {
      test     = "StringEquals"
      variable = "ses:FromAddress"
      values   = [local.from_address]
    }
  }

  statement {
    sid       = "LookUpAgents"
    actions   = ["cognito-idp:ListUsers"]
    resources = [local.crm.cognito_user_pool_arn]
  }

  # The Asia-Pacific profile and its model in each region it may route to,
  # listed rather than a wildcard (ADR-0054, Security & Privacy Impact).
  statement {
    sid     = "InvokeDraftModel"
    actions = ["bedrock:InvokeModel"]
    resources = concat(
      ["arn:${local.partition}:bedrock:${var.aws_region}:${local.account}:inference-profile/${var.bedrock_model_id}"],
      [for region in var.bedrock_profile_regions : "arn:${local.partition}:bedrock:${region}::foundation-model/${var.bedrock_foundation_model_id}"],
    )
  }
}

# ------------------------------------------------------- engagement-service

module "engagement_service" {
  source = "../../modules/ecs-service"

  name               = "engagement-service"
  name_prefix        = local.name
  cluster_arn        = local.crm.cluster_arn
  cluster_name       = local.crm.cluster_name
  subnet_ids         = local.crm.app_subnet_ids
  security_group_ids = [aws_security_group.engagement.id]
  image              = "${aws_ecr_repository.engagement.repository_url}:${var.image_tag}"
  # 1 vCPU and 2 GB: 0.5 vCPU starts a Spring Boot service too slowly
  # (ADR-0051 decision 1, ADR-0042 Cost Impact).
  cpu              = 1024
  memory           = 2048
  cpu_architecture = local.crm.cpu_architecture
  port             = local.port
  port_name        = "https"

  environment = {
    ENGAGEMENT_DB_URL                  = "${local.db_url}?sslmode=require"
    SPRING_FLYWAY_ENABLED              = "false" # db-migrate applies the migrations
    COGNITO_ISSUER_URI                 = local.crm.cognito_issuer
    COGNITO_APP_CLIENT_ID              = local.crm.cognito_web_client_id
    COGNITO_USER_POOL_ID               = local.crm.cognito_user_pool_id
    CLIENT_SERVICE_BASE_URL            = local.crm.client.url
    ENGAGEMENT_AUDIT_PUBLISHER_ENABLED = "true"
    AUDIT_QUEUE_URL                    = local.crm.audit_queue_url
    AWS_REGION                         = var.aws_region
    ENGAGEMENT_MAIL_PROVIDER           = var.mail_enabled ? "ses" : "none"
    SES_FROM_ADDRESS                   = local.from_address
    SES_CONFIGURATION_SET              = local.configuration_set_name
    ENGAGEMENT_MAIL_DOMAIN             = local.mail_domain
    CRM_APP_URL                        = local.crm.app_url
    ENGAGEMENT_AI_ENABLED              = tostring(var.ai_enabled)
    BEDROCK_REGION                     = var.aws_region
    BEDROCK_MODEL_ID                   = var.bedrock_model_id
  }
  secrets = {
    ENGAGEMENT_DB_USERNAME = "${aws_secretsmanager_secret.app_login.arn}:username::"
    ENGAGEMENT_DB_PASSWORD = "${aws_secretsmanager_secret.app_login.arn}:password::"
  }
  # The master secret is for db-migrate only.
  secret_arns        = [aws_secretsmanager_secret.app_login.arn, module.db.master_secret_arn]
  task_role_policies = { service = data.aws_iam_policy_document.engagement.json }
  tls = {
    mode            = "java"
    ca_pem          = local.crm.ca_pem
    serves_tls      = true
    cert_secret_arn = aws_secretsmanager_secret.tls.arn
  }

  # db-migrate applies services/engagement-service's migrations as the master
  # user, checking RDS's certificate, then creates the login (ADR-0028
  # decision 3). The files are named for Spring Boot's Flyway, hence "__".
  migrations = {
    image    = local.crm.db_migrate_image
    location = "engagement"
    url      = "${local.db_url}?sslmode=verify-full&sslrootcert=/tls/rds-ca.pem"
    secrets = {
      FLYWAY_USER                      = "${module.db.master_secret_arn}:username::"
      FLYWAY_PASSWORD                  = "${module.db.master_secret_arn}:password::"
      FLYWAY_PLACEHOLDERS_APP_LOGIN    = "${aws_secretsmanager_secret.app_login.arn}:username::"
      FLYWAY_PLACEHOLDERS_APP_PASSWORD = "${aws_secretsmanager_secret.app_login.arn}:password::"
    }
    environment = { FLYWAY_SQL_MIGRATION_SEPARATOR = "__" }
  }

  # A client only: nothing calls engagement-service inside the VPC.
  service_connect = {
    namespace_arn = local.crm.namespace_arn
    aliases       = []
  }

  # Priority 70: the next free band after event-alert-service's 60.
  load_balancer = merge(local.crm.alb, {
    protocol          = "HTTPS"
    health_check_path = "/actuator/health"
    rules             = [{ priority = 70, paths = ["/api/engagement", "/api/engagement/*"] }]
  })

  # The CRM stack's running variable starts and stops these tasks too (ADR-0029).
  min_tasks          = local.min_tasks
  max_tasks          = local.max_tasks
  use_spot           = local.crm.use_spot
  log_retention_days = local.crm.log_retention_days

  depends_on = [aws_secretsmanager_secret_version.tls, aws_secretsmanager_secret_version.app_login]
}

# ------------------------------------------------------------------ alarms
# Mail that has waited over 5 minutes while the service runs: the dispatcher
# logs mail_dispatch_lagging (agent-engagement plan §13).

resource "aws_cloudwatch_log_metric_filter" "dispatch_lagging" {
  name           = "${local.name}-engagement-dispatch-lagging"
  log_group_name = module.engagement_service.log_group_name
  pattern        = "\"mail_dispatch_lagging\""

  metric_transformation {
    name          = "DispatchLagging"
    namespace     = "CRM/Engagement"
    value         = "1"
    default_value = "0"
  }
}

resource "aws_cloudwatch_metric_alarm" "dispatch_lagging" {
  alarm_name          = "${local.name}-engagement-dispatch-lagging"
  alarm_description   = "engagement-service has due mail that waited over 5 minutes."
  namespace           = "CRM/Engagement"
  metric_name         = aws_cloudwatch_log_metric_filter.dispatch_lagging.metric_transformation[0].name
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [local.crm.alarm_topic_arn]
}

# SES pauses an account whose bounce rate stays high, so warn well before.
resource "aws_cloudwatch_metric_alarm" "ses_bounce_rate" {
  alarm_name          = "${local.name}-engagement-ses-bounce-rate"
  alarm_description   = "The SES account's bounce rate is over 5%."
  namespace           = "AWS/SES"
  metric_name         = "Reputation.BounceRate"
  statistic           = "Maximum"
  period              = 3600
  evaluation_periods  = 1
  threshold           = 0.05
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [local.crm.alarm_topic_arn]
}

# ------------------------------------------------------------ CI delivery
# When the CRM stack has the GitHub deploy role (ADR-0028), let it push this
# image and roll out this service.

data "aws_iam_policy_document" "github_deploy" {
  count = local.crm.github_deploy_role_name == null ? 0 : 1

  statement {
    sid = "PushEngagementImage"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImages",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = [aws_ecr_repository.engagement.arn]
  }

  statement {
    sid       = "PassEngagementRoles"
    actions   = ["iam:PassRole"]
    resources = [module.engagement_service.task_role_arn, module.engagement_service.execution_role_arn]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  count  = local.crm.github_deploy_role_name == null ? 0 : 1
  name   = "deploy-agent-engagement"
  role   = local.crm.github_deploy_role_name
  policy = data.aws_iam_policy_document.github_deploy[0].json
}
