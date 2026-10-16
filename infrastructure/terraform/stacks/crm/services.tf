# The ECS services (system-overview "Components"). Settings follow each
# service's README and configuration on dev; where the code and the target
# design differ, ADR-0042 records it. The health monitor is not deployable yet,
# and the alert worker and its Tokyo search belong to EDA-D1-02
# (docs/feature-plans/infrastructure_plan.md).

locals {
  app_subnet_ids = aws_subnet.app[*].id
  ca_pem         = tls_self_signed_cert.ca.cert_pem

  alb = {
    listener_arn   = aws_lb_listener.internal.arn
    alb_arn_suffix = aws_lb.internal.arn_suffix
    vpc_id         = aws_vpc.this.id
  }

  db_master_secret = { for k, db in module.db : k => db.master_secret_arn }
}

data "aws_iam_policy_document" "send_audit" {
  statement {
    sid       = "SendAuditEvents"
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.audit.arn]
  }
}

# ------------------------------------------------------------- user-service

data "aws_iam_policy_document" "user_service" {
  source_policy_documents = [data.aws_iam_policy_document.send_audit.json]

  statement {
    sid = "ManageCrmUsers"
    actions = [
      "cognito-idp:AdminAddUserToGroup",
      "cognito-idp:AdminCreateUser",
      "cognito-idp:AdminDisableUser",
      "cognito-idp:AdminGetUser",
      "cognito-idp:AdminRemoveUserFromGroup",
      "cognito-idp:AdminResetUserPassword",
      "cognito-idp:AdminUpdateUserAttributes",
    ]
    resources = [aws_cognito_user_pool.this.arn]
  }
}

module "user_service" {
  source = "../../modules/ecs-service"

  name               = "user-service"
  name_prefix        = local.name
  cluster_arn        = aws_ecs_cluster.this.arn
  cluster_name       = aws_ecs_cluster.this.name
  subnet_ids         = local.app_subnet_ids
  security_group_ids = [local.sg["user"]]
  image              = local.image["user-service"]
  cpu                = 512
  memory             = 1024
  cpu_architecture   = var.cpu_architecture
  port               = local.ports.user
  app_protocol       = "http"

  environment = {
    DB_HOST                  = module.db["users"].address
    DB_PORT                  = "5432"
    USER_DB_NAME             = module.db["users"].db_name
    OIDC_ISSUER_URI          = local.cognito_issuer
    COGNITO_USER_POOL_ID     = aws_cognito_user_pool.this.id
    COGNITO_APP_CLIENT_ID    = aws_cognito_user_pool_client.web.id
    AWS_REGION               = local.region
    AUDIT_QUEUE_URL          = aws_sqs_queue.audit.url
    AUDITOR_BASE_URL         = "https://${local.sc.auditor}:${local.ports.auditor_http}"
    AUDITOR_GRPC_TARGET      = "${local.sc.auditor}:${local.ports.auditor_grpc}"
    AUDITOR_GRPC_TLS_CA      = "/tls/ca.pem"
    ACCOUNT_SERVICE_BASE_URL = "https://${local.sc.account}:${local.ports.account}"
    SFTP_RECEIVER_BASE_URL   = "http://${local.sc.transaction}:${local.ports.transaction}"
    ROOT_ADMIN_EMAIL         = var.root_admin_email
  }
  secrets = {
    POSTGRES_USER     = "${local.db_master_secret["users"]}:username::"
    POSTGRES_PASSWORD = "${local.db_master_secret["users"]}:password::"
  }
  secret_arns        = [local.db_master_secret["users"]]
  task_role_policies = { service = data.aws_iam_policy_document.user_service.json }
  tls                = { mode = "java", ca_pem = local.ca_pem }

  service_connect = {
    namespace_arn = aws_service_discovery_http_namespace.this.arn
    aliases       = [{ port_name = "http", dns_name = local.sc.user, port = local.ports.user }]
  }

  load_balancer = merge(local.alb, {
    protocol          = "HTTP"
    health_check_path = "/actuator/health"
    rules = [
      { priority = 10, paths = ["/api/accounts/*/transactions", "/api/transactions"] },
      { priority = 20, paths = ["/api/users", "/api/users/*", "/api/activities", "/api/activities/*"] },
      { priority = 21, paths = ["/api/corrupted-logs", "/api/corrupted-logs/*"] },
    ]
  })

  min_tasks          = local.min_tasks
  max_tasks          = local.max_tasks
  use_spot           = local.use_fargate_spot
  log_retention_days = local.log_retention_days

  depends_on = [aws_ecs_cluster_capacity_providers.this]
}

# ----------------------------------------------------------- client-service
# Implemented today as client-profile-management-service.

data "aws_iam_policy_document" "client_service" {
  source_policy_documents = [data.aws_iam_policy_document.send_audit.json]

  # ADR-0030: data keys only for the client named in the encryption context.
  statement {
    sid       = "ClientDataKeys"
    actions   = ["kms:GenerateDataKey", "kms:Decrypt"]
    resources = [aws_kms_key.client_data.arn]
    condition {
      test     = "ForAnyValue:StringEquals"
      variable = "kms:EncryptionContextKeys"
      values   = ["client_id"]
    }
  }

  statement {
    sid       = "BlindIndexes"
    actions   = ["kms:GenerateMac"]
    resources = [aws_kms_key.client_index.arn]
  }

  statement {
    sid       = "ClientKeyTable"
    actions   = ["dynamodb:PutItem", "dynamodb:GetItem", "dynamodb:DeleteItem"]
    resources = [aws_dynamodb_table.client_keys.arn]
  }

  statement {
    sid       = "IdentityDocuments"
    actions   = ["s3:PutObject", "s3:GetObject"]
    resources = ["${aws_s3_bucket.documents.arn}/clients/*"]
  }

  statement {
    sid       = "IdentityDocumentsKey"
    actions   = ["kms:GenerateDataKey", "kms:Decrypt"]
    resources = [aws_kms_key.documents.arn]
  }
}

module "client_service" {
  source = "../../modules/ecs-service"

  name               = "client-service"
  name_prefix        = local.name
  cluster_arn        = aws_ecs_cluster.this.arn
  cluster_name       = aws_ecs_cluster.this.name
  subnet_ids         = local.app_subnet_ids
  security_group_ids = [local.sg["client"]]
  image              = local.image["client-service"]
  cpu_architecture   = var.cpu_architecture
  port               = local.ports.client
  port_name          = "https"

  environment = {
    APP_ENV                  = "production"
    APP_ADDR                 = ":${local.ports.client}"
    APP_TLS_CERT_FILE        = "/tls/cert.pem"
    APP_TLS_KEY_FILE         = "/tls/key.pem"
    DB_HOST                  = module.db["clients"].address
    DB_PORT                  = "5432"
    DB_NAME                  = module.db["clients"].db_name
    DB_SSLMODE               = "verify-full"
    DB_SSLROOTCERT           = "/tls/rds-ca.pem"
    ACCOUNT_SERVICE_BASE_URL = "https://${local.sc.account}:${local.ports.account}"
    AWS_REGION               = local.region
    AUDIT_QUEUE_URL          = aws_sqs_queue.audit.url
    COGNITO_ISSUER_URI       = local.cognito_issuer
    COGNITO_APP_CLIENT_ID    = aws_cognito_user_pool_client.web.id
    CLIENT_KEYS_TABLE        = aws_dynamodb_table.client_keys.name
    CLIENT_DATA_KEY_ID       = aws_kms_key.client_data.arn
    CLIENT_INDEX_KEY_ID      = aws_kms_key.client_index.arn
    CLIENT_DOCUMENTS_BUCKET  = aws_s3_bucket.documents.bucket
  }
  secrets = {
    DB_USER     = "${local.db_master_secret["clients"]}:username::"
    DB_PASSWORD = "${local.db_master_secret["clients"]}:password::"
  }
  secret_arns        = [local.db_master_secret["clients"]]
  task_role_policies = { service = data.aws_iam_policy_document.client_service.json }
  tls = {
    mode            = "go"
    ca_pem          = local.ca_pem
    serves_tls      = true
    cert_secret_arn = aws_secretsmanager_secret.service_tls["client"].arn
  }

  service_connect = {
    namespace_arn = aws_service_discovery_http_namespace.this.arn
    aliases       = [{ port_name = "https", dns_name = local.sc.client, port = local.ports.client }]
  }

  load_balancer = merge(local.alb, {
    protocol          = "HTTPS"
    health_check_path = "/health"
    rules             = [{ priority = 30, paths = ["/api/clients", "/api/clients/*"] }]
  })

  min_tasks          = local.min_tasks
  max_tasks          = local.max_tasks
  use_spot           = local.use_fargate_spot
  log_retention_days = local.log_retention_days

  depends_on = [aws_ecs_cluster_capacity_providers.this, aws_secretsmanager_secret_version.service_tls]
}

# ---------------------------------------------------------- account-service

module "account_service" {
  source = "../../modules/ecs-service"

  name               = "account-service"
  name_prefix        = local.name
  cluster_arn        = aws_ecs_cluster.this.arn
  cluster_name       = aws_ecs_cluster.this.name
  subnet_ids         = local.app_subnet_ids
  security_group_ids = [local.sg["account"]]
  image              = local.image["account-service"]
  cpu_architecture   = var.cpu_architecture
  port               = local.ports.account
  port_name          = "https"

  environment = {
    APP_ENV                    = "production"
    APP_ADDR                   = ":${local.ports.account}"
    APP_TLS_CERT_FILE          = "/tls/cert.pem"
    APP_TLS_KEY_FILE           = "/tls/key.pem"
    DB_HOST                    = module.db["accounts"].address
    DB_PORT                    = "5432"
    DB_NAME                    = module.db["accounts"].db_name
    DB_SSLMODE                 = "verify-full"
    DB_SSLROOTCERT             = "/tls/rds-ca.pem"
    CLIENT_SERVICE_BASE_URL    = "https://${local.sc.client}:${local.ports.client}"
    AWS_REGION                 = local.region
    AUDIT_QUEUE_URL            = aws_sqs_queue.audit.url
    COGNITO_ISSUER_URI         = local.cognito_issuer
    COGNITO_APP_CLIENT_ID      = aws_cognito_user_pool_client.web.id
    COGNITO_SERVICE_CLIENT_IDS = aws_cognito_user_pool_client.transaction_ingest.id
  }
  secrets = {
    DB_USER     = "${local.db_master_secret["accounts"]}:username::"
    DB_PASSWORD = "${local.db_master_secret["accounts"]}:password::"
  }
  secret_arns        = [local.db_master_secret["accounts"]]
  task_role_policies = { service = data.aws_iam_policy_document.send_audit.json }
  tls = {
    mode            = "go"
    ca_pem          = local.ca_pem
    serves_tls      = true
    cert_secret_arn = aws_secretsmanager_secret.service_tls["account"].arn
  }

  service_connect = {
    namespace_arn = aws_service_discovery_http_namespace.this.arn
    aliases       = [{ port_name = "https", dns_name = local.sc.account, port = local.ports.account }]
  }

  # /internal/accounts/lookup is for the ingest Lambda, which cannot use
  # Service Connect (ADR-0037, ADR-0019 decision 4).
  load_balancer = merge(local.alb, {
    protocol          = "HTTPS"
    health_check_path = "/health"
    rules = [
      { priority = 40, paths = ["/api/accounts", "/api/accounts/*"] },
      { priority = 41, paths = ["/internal/accounts/*"] },
    ]
  })

  min_tasks          = local.min_tasks
  max_tasks          = local.max_tasks
  use_spot           = local.use_fargate_spot
  log_retention_days = local.log_retention_days

  depends_on = [aws_ecs_cluster_capacity_providers.this, aws_secretsmanager_secret_version.service_tls]
}

# ------------------------------------------------------------------ auditor

data "aws_iam_policy_document" "auditor" {
  statement {
    sid = "ConsumeAuditEvents"
    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility",
      "sqs:GetQueueAttributes",
    ]
    resources = [aws_sqs_queue.audit.arn]
  }

  statement {
    sid       = "RejectToDeadLetterQueue"
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.audit_dlq.arn]
  }
}

locals {
  # The auditor's database settings use these names (src/internal/bootstrap).
  audit_db_env = {
    HOST    = module.db["audit"].address
    DB_NAME = module.db["audit"].db_name
    SSLMODE = "verify-full" # verified against the RDS CA in SSL_CERT_FILE
  }
}

module "auditor" {
  source = "../../modules/ecs-service"

  name               = "auditor"
  name_prefix        = local.name
  cluster_arn        = aws_ecs_cluster.this.arn
  cluster_name       = aws_ecs_cluster.this.name
  subnet_ids         = local.app_subnet_ids
  security_group_ids = [local.sg["auditor"]]
  image              = local.image["auditor"]
  cpu_architecture   = var.cpu_architecture

  # Both ports serve TLS themselves, so Service Connect proxies them as TCP
  # and balances connections, not calls (ADR-0042 decision 7).
  port        = local.ports.auditor_http
  port_name   = "https"
  extra_ports = [{ name = "grpc", port = local.ports.auditor_grpc }]

  environment = merge(local.audit_db_env, {
    HTTP_PORT             = tostring(local.ports.auditor_http)
    GRPC_PORT             = ":${local.ports.auditor_grpc}"
    AUDITOR_TLS_CERT_FILE = "/tls/cert.pem"
    AUDITOR_TLS_KEY_FILE  = "/tls/key.pem"
    AUDIT_QUEUE_URL       = aws_sqs_queue.audit.url
    AUDIT_DLQ_URL         = aws_sqs_queue.audit_dlq.url
    AWS_REGION            = local.region
    COGNITO_ISSUER_URI    = local.cognito_issuer
    COGNITO_APP_CLIENT_ID = aws_cognito_user_pool_client.web.id
  })
  secrets = {
    USER     = "${aws_secretsmanager_secret.audit_login["auditor"].arn}:username::"
    PASSWORD = "${aws_secretsmanager_secret.audit_login["auditor"].arn}:password::"
  }
  secret_arns        = [aws_secretsmanager_secret.audit_login["auditor"].arn]
  task_role_policies = { service = data.aws_iam_policy_document.auditor.json }
  tls = {
    mode            = "go"
    ca_pem          = local.ca_pem
    serves_tls      = true
    cert_secret_arn = aws_secretsmanager_secret.service_tls["auditor"].arn
  }

  service_connect = {
    namespace_arn = aws_service_discovery_http_namespace.this.arn
    aliases = [
      { port_name = "https", dns_name = local.sc.auditor, port = local.ports.auditor_http },
      { port_name = "grpc", dns_name = local.sc.auditor, port = local.ports.auditor_grpc },
    ]
  }

  scaling_queue_name = aws_sqs_queue.audit.name

  min_tasks          = local.min_tasks
  max_tasks          = local.max_tasks
  use_spot           = local.use_fargate_spot
  log_retention_days = local.log_retention_days

  depends_on = [
    aws_ecs_cluster_capacity_providers.this,
    aws_secretsmanager_secret_version.service_tls,
    aws_secretsmanager_secret_version.audit_login,
  ]
}

# chain-verify: the auditor's image with another entry point, started daily by
# EventBridge Scheduler (scheduler.tf, ADR-0022 decision 3).
data "aws_iam_policy_document" "chain_verify" {
  statement {
    sid       = "WriteAnchors"
    actions   = ["s3:PutObject", "s3:PutObjectRetention", "s3:GetObject"]
    resources = ["${aws_s3_bucket.anchor.arn}/*"]
  }

  statement {
    sid       = "FindLatestAnchor"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.anchor.arn]
  }
}

module "chain_verify" {
  source = "../../modules/ecs-service"

  name               = "chain-verify"
  name_prefix        = local.name
  create_service     = false
  cluster_arn        = aws_ecs_cluster.this.arn
  cluster_name       = aws_ecs_cluster.this.name
  subnet_ids         = local.app_subnet_ids
  security_group_ids = [local.sg["chain_verify"]]
  image              = local.image["auditor"]
  entry_point        = ["/chain-verify"]
  cpu_architecture   = var.cpu_architecture

  environment = merge(local.audit_db_env, {
    CHAIN_ANCHOR_BUCKET = aws_s3_bucket.anchor.bucket
    AWS_REGION          = local.region
  })
  secrets = {
    USER     = "${aws_secretsmanager_secret.audit_login["chain_verify"].arn}:username::"
    PASSWORD = "${aws_secretsmanager_secret.audit_login["chain_verify"].arn}:password::"
  }
  secret_arns        = [aws_secretsmanager_secret.audit_login["chain_verify"].arn]
  task_role_policies = { service = data.aws_iam_policy_document.chain_verify.json }
  tls                = { mode = "go", ca_pem = local.ca_pem }

  log_retention_days = local.log_retention_days
}

# ------------------------------------------------------ transaction-service
# The SFTP receiver's read API. It checks no token, so it has no ALB route
# and only user-service may reach it (security_groups.tf).

module "transaction_service" {
  source = "../../modules/ecs-service"

  name               = "transaction-service"
  name_prefix        = local.name
  cluster_arn        = aws_ecs_cluster.this.arn
  cluster_name       = aws_ecs_cluster.this.name
  subnet_ids         = local.app_subnet_ids
  security_group_ids = [local.sg["transaction"]]
  image              = local.image["transaction-service"]
  cpu_architecture   = var.cpu_architecture
  port               = local.ports.transaction
  app_protocol       = "http"

  environment = {
    APP_ADDR       = ":${local.ports.transaction}"
    DB_HOST        = module.db["transactions"].address
    DB_PORT        = "5432"
    DB_NAME        = module.db["transactions"].db_name
    DB_SSLMODE     = "verify-full"
    DB_SSLROOTCERT = "/tls/rds-ca.pem"
  }
  secrets = {
    DB_USER     = "${local.db_master_secret["transactions"]}:username::"
    DB_PASSWORD = "${local.db_master_secret["transactions"]}:password::"
  }
  secret_arns = [local.db_master_secret["transactions"]]
  tls         = { mode = "go", ca_pem = local.ca_pem }

  service_connect = {
    namespace_arn = aws_service_discovery_http_namespace.this.arn
    aliases       = [{ port_name = "http", dns_name = local.sc.transaction, port = local.ports.transaction }]
  }

  min_tasks          = local.min_tasks
  max_tasks          = local.max_tasks
  use_spot           = local.use_fargate_spot
  log_retention_days = local.log_retention_days

  depends_on = [aws_ecs_cluster_capacity_providers.this]
}

# -------------------------------------------------------- dashboard-service

module "dashboard_service" {
  source = "../../modules/ecs-service"

  name               = "dashboard-service"
  name_prefix        = local.name
  cluster_arn        = aws_ecs_cluster.this.arn
  cluster_name       = aws_ecs_cluster.this.name
  subnet_ids         = local.app_subnet_ids
  security_group_ids = [local.sg["dashboard"]]
  image              = local.image["dashboard-service"]
  cpu                = 512
  memory             = 1024
  cpu_architecture   = var.cpu_architecture
  port               = local.ports.dashboard
  app_protocol       = "http"

  environment = {
    OIDC_ISSUER_URI          = local.cognito_issuer
    COGNITO_APP_CLIENT_ID    = aws_cognito_user_pool_client.web.id
    AUDIT_QUEUE_URL          = aws_sqs_queue.audit.url
    AWS_REGION               = local.region
    USER_SERVICE_BASE_URL    = "http://${local.sc.user}:${local.ports.user}"
    CLIENT_SERVICE_BASE_URL  = "https://${local.sc.client}:${local.ports.client}"
    ACCOUNT_SERVICE_BASE_URL = "https://${local.sc.account}:${local.ports.account}"
  }
  task_role_policies = { service = data.aws_iam_policy_document.send_audit.json }
  tls                = { mode = "java", ca_pem = local.ca_pem }

  # A client only: nothing calls the dashboard inside the VPC.
  service_connect = {
    namespace_arn = aws_service_discovery_http_namespace.this.arn
    aliases       = []
  }

  load_balancer = merge(local.alb, {
    protocol          = "HTTP"
    health_check_path = "/actuator/health"
    rules             = [{ priority = 50, paths = ["/api/dashboard", "/api/dashboard/*"] }]
  })

  min_tasks          = local.min_tasks
  max_tasks          = local.max_tasks
  use_spot           = local.use_fargate_spot
  log_retention_days = local.log_retention_days

  depends_on = [aws_ecs_cluster_capacity_providers.this]
}

# ------------------------------------------------------ event-alert-service
# api mode only. The worker, its schedule, AgentCore in Tokyo and Bedrock
# access are EDA-D1-02 (Azeez); ingestion and the model stay switched off.

module "event_alert_service" {
  source = "../../modules/ecs-service"

  name               = "event-alert-service"
  name_prefix        = local.name
  cluster_arn        = aws_ecs_cluster.this.arn
  cluster_name       = aws_ecs_cluster.this.name
  subnet_ids         = local.app_subnet_ids
  security_group_ids = [local.sg["alerts"]]
  image              = local.image["event-alert-service"]
  cpu                = 512
  memory             = 1024
  cpu_architecture   = var.cpu_architecture
  port               = local.ports.alerts
  app_protocol       = "http"

  environment = {
    EVENT_ALERT_DB_URL            = "jdbc:postgresql://${module.db["alerts"].address}:5432/${module.db["alerts"].db_name}?sslmode=require"
    COGNITO_ISSUER_URI            = local.cognito_issuer
    ALERT_MODE                    = "API"
    ALERT_AUDIT_PUBLISHER_ENABLED = "true"
    AUDIT_QUEUE_URL               = aws_sqs_queue.audit.url
    AWS_REGION                    = local.region
    ALERT_INGESTION_ENABLED       = "false"
    ALERT_AI_ENABLED              = "false"
  }
  secrets = {
    EVENT_ALERT_DB_USERNAME = "${local.db_master_secret["alerts"]}:username::"
    EVENT_ALERT_DB_PASSWORD = "${local.db_master_secret["alerts"]}:password::"
  }
  secret_arns        = [local.db_master_secret["alerts"]]
  task_role_policies = { service = data.aws_iam_policy_document.send_audit.json }

  load_balancer = merge(local.alb, {
    protocol          = "HTTP"
    health_check_path = "/actuator/health"
    rules             = [{ priority = 60, paths = ["/api/alerts", "/api/alerts/*"] }]
  })

  min_tasks          = local.min_tasks
  max_tasks          = local.max_tasks
  use_spot           = local.use_fargate_spot
  log_retention_days = local.log_retention_days

  depends_on = [aws_ecs_cluster_capacity_providers.this]
}
