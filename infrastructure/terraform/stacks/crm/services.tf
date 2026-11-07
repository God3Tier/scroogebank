# The ECS services (system-overview "Components"). Settings follow each
# service's README and configuration on dev; where the code and the target
# design differ, ADR-0042 records it. The Client Health API and its daily
# analysis task are deployed here. The alert worker and its Tokyo
# search belong to EDA-D1-02
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
  app_login_secret = { for k, s in aws_secretsmanager_secret.app_login : k => s.arn }

  # The logins each database's db-migrate creates, as Flyway placeholders read
  # from their secrets (data.tf): the service's login and password, or for
  # transactions and audit, their logins' passwords.
  migration_placeholders = merge(
    {
      for login, l in local.app_logins : l.db => {
        FLYWAY_PLACEHOLDERS_APP_LOGIN    = "${local.app_login_secret[login]}:username::"
        FLYWAY_PLACEHOLDERS_APP_PASSWORD = "${local.app_login_secret[login]}:password::"
      } if l.db != "transactions"
    },
    {
      transactions = {
        FLYWAY_PLACEHOLDERS_TRANSACTION_SERVICE_PASSWORD = "${local.app_login_secret["transaction_service"]}:password::"
        FLYWAY_PLACEHOLDERS_TRANSACTION_INGEST_PASSWORD  = "${local.app_login_secret["transaction_ingest"]}:password::"
      }
      audit = {
        FLYWAY_PLACEHOLDERS_AUDITOR_APP_PASSWORD  = "${aws_secretsmanager_secret.audit_login["auditor"].arn}:password::"
        FLYWAY_PLACEHOLDERS_CHAIN_VERIFY_PASSWORD = "${aws_secretsmanager_secret.audit_login["chain_verify"].arn}:password::"
      }
    },
  )

  # The schemas (ADR-0028 decision 3). Each task's db-migrate container
  # applies its database's directory in the db-migrate image as the master
  # user, checking RDS's certificate, then creates the logins and sets their
  # passwords, before the service starts. Only db-migrate gets the master
  # credentials. user-service's, event-alert-service's and Client Health's
  # files are named for Spring Boot's Flyway, which applies them locally but
  # is off in AWS.
  migrations = {
    for db in keys(local.databases) : db => {
      image    = local.image["db-migrate"]
      location = db
      url      = "jdbc:postgresql://${module.db[db].address}:5432/${module.db[db].db_name}?sslmode=verify-full&sslrootcert=/tls/rds-ca.pem"
      secrets = merge(
        {
          FLYWAY_USER     = "${local.db_master_secret[db]}:username::"
          FLYWAY_PASSWORD = "${local.db_master_secret[db]}:password::"
        },
        local.migration_placeholders[db],
      )
      environment = contains(["users", "alerts", "health"], db) ? { FLYWAY_SQL_MIGRATION_SEPARATOR = "__" } : {}
    }
  }
}

data "aws_iam_policy_document" "send_audit" {
  statement {
    sid       = "SendAuditEvents"
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.audit.arn]
  }
}

# The API starts the alert worker's task when an agent adds a search domain (ADR-0080). Only that
# task definition, in this cluster, and only its own two roles may be handed to it. The worker's
# family and role names follow the ecs-service module ("<family>-task" and "<family>-exec"), so
# they are known here without reading the alert-worker stack, which is applied after this one.
data "aws_iam_policy_document" "start_alert_worker" {
  statement {
    sid       = "StartAlertWorker"
    actions   = ["ecs:RunTask"]
    resources = ["arn:${data.aws_partition.current.partition}:ecs:${local.region}:${local.account_id}:task-definition/${local.name}-event-alert-worker:*"]
    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [aws_ecs_cluster.this.arn]
    }
  }

  statement {
    sid     = "PassAlertWorkerRoles"
    actions = ["iam:PassRole"]
    resources = [
      "arn:${data.aws_partition.current.partition}:iam::${local.account_id}:role/${local.name}-event-alert-worker-task",
      "arn:${data.aws_partition.current.partition}:iam::${local.account_id}:role/${local.name}-event-alert-worker-exec",
    ]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "client_health_metrics" {
  statement {
    sid       = "PublishClientHealthMetrics"
    actions   = ["cloudwatch:PutMetricData"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "cloudwatch:namespace"
      values   = ["Scrooge/ClientHealth"]
    }
  }
}

# The analysis task words new explanations; the API service never calls
# Bedrock. The Asia-Pacific profile and its model in each region it may route
# to, listed rather than a wildcard (ADR-0067).
data "aws_iam_policy_document" "client_health_bedrock" {
  statement {
    sid     = "InvokeExplanationModel"
    actions = ["bedrock:InvokeModel"]
    resources = concat(
      ["arn:${data.aws_partition.current.partition}:bedrock:${local.region}:${local.account_id}:inference-profile/${var.client_health_bedrock_model_id}"],
      [for region in var.client_health_bedrock_profile_regions : "arn:${data.aws_partition.current.partition}:bedrock:${region}::foundation-model/${var.client_health_bedrock_foundation_model_id}"],
    )
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
  port_name          = "https"

  environment = {
    DB_HOST               = module.db["users"].address
    DB_PORT               = "5432"
    USER_DB_NAME          = module.db["users"].db_name
    OIDC_ISSUER_URI       = local.cognito_issuer
    COGNITO_USER_POOL_ID  = aws_cognito_user_pool.this.id
    COGNITO_APP_CLIENT_ID = aws_cognito_user_pool_client.web.id
    AWS_REGION            = local.region
    AUDIT_QUEUE_URL       = aws_sqs_queue.audit.url
    AUDITOR_BASE_URL      = "https://${local.sc.auditor}:${local.ports.auditor_http}"
    AUDITOR_GRPC_TARGET   = "${local.sc.auditor}:${local.ports.auditor_grpc}"
    AUDITOR_GRPC_TLS_CA   = "/tls/ca.pem"
    # Read from the Cognito user when Terraform creates it (ADR-0076), so that
    # user exists before user-service starts and adopts it, rather than
    # creating it and e-mailing a temporary password to an address with no
    # mailbox.
    ROOT_ADMIN_EMAIL      = try(aws_cognito_user.mfa_exempt[var.root_admin_email].attributes.email, var.root_admin_email)
    SPRING_FLYWAY_ENABLED = "false" # db-migrate applies its migrations
  }
  secrets = {
    POSTGRES_USER     = "${local.app_login_secret["user_service"]}:username::"
    POSTGRES_PASSWORD = "${local.app_login_secret["user_service"]}:password::"
  }
  # The master secret is for db-migrate only.
  secret_arns        = [local.app_login_secret["user_service"], local.db_master_secret["users"]]
  task_role_policies = { service = data.aws_iam_policy_document.user_service.json }
  tls = {
    mode            = "java"
    ca_pem          = local.ca_pem
    serves_tls      = true
    cert_secret_arn = aws_secretsmanager_secret.service_tls["user"].arn
  }
  migrations = local.migrations["users"]

  # dashboard-service calls it here and checks its certificate, so Service
  # Connect proxies the port as TCP (ADR-0042 decision 7).
  service_connect = {
    namespace_arn = aws_service_discovery_http_namespace.this.arn
    aliases       = [{ port_name = "https", dns_name = local.sc.user, port = local.ports.user }]
  }

  load_balancer = merge(local.alb, {
    protocol          = "HTTPS"
    health_check_path = "/actuator/health"
    rules = [
      { priority = 20, paths = ["/api/users", "/api/users/*", "/api/activities", "/api/activities/*"] },
      # An ALB rule takes at most 5 path values, so the audit log shares rule 21.
      { priority = 21, paths = ["/api/corrupted-logs", "/api/corrupted-logs/*", "/api/audit-logs", "/api/audit-logs/*"] },
    ]
  })

  min_tasks          = local.min_tasks
  max_tasks          = local.max_tasks
  use_spot           = local.use_fargate_spot
  log_retention_days = local.log_retention_days

  depends_on = [
    aws_ecs_cluster_capacity_providers.this,
    aws_secretsmanager_secret_version.service_tls,
    aws_secretsmanager_secret_version.app_login,
  ]
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
  # 0.5 vCPU, not the module's 0.25: decrypting each page of clients is
  # CPU-bound, and at the specified load (100 agents, 1 s ramp-up) two
  # 0.25 vCPU tasks gave a p95 of 9.1 s before any new task could start
  # (load test, 2026-11-03; ADR-0077).
  cpu              = 512
  memory           = 1024
  cpu_architecture = var.cpu_architecture
  port             = local.ports.client
  port_name        = "https"

  environment = {
    APP_ENV                            = "production"
    APP_ADDR                           = ":${local.ports.client}"
    APP_TLS_CERT_FILE                  = "/tls/cert.pem"
    APP_TLS_KEY_FILE                   = "/tls/key.pem"
    DB_HOST                            = module.db["clients"].address
    DB_PORT                            = "5432"
    DB_NAME                            = module.db["clients"].db_name
    DB_SSLMODE                         = "verify-full"
    DB_SSLROOTCERT                     = "/tls/rds-ca.pem"
    ACCOUNT_SERVICE_BASE_URL           = "https://${local.sc.account}:${local.ports.account}"
    USER_SERVICE_BASE_URL              = "https://${local.sc.user}:${local.ports.user}" # reassigning clients (ADR-0060)
    AWS_REGION                         = local.region
    AUDIT_QUEUE_URL                    = aws_sqs_queue.audit.url
    COGNITO_ISSUER_URI                 = local.cognito_issuer
    COGNITO_APP_CLIENT_ID              = aws_cognito_user_pool_client.web.id
    CLIENT_HEALTH_ASSIGNMENT_CLIENT_ID = aws_cognito_user_pool_client.client_health_read.id
    CLIENT_HEALTH_ASSIGNMENT_SCOPE     = "crm-clients/assignment.read"
    CLIENT_KEYS_TABLE                  = aws_dynamodb_table.client_keys.name
    CLIENT_DATA_KEY_ID                 = aws_kms_key.client_data.arn
    CLIENT_INDEX_KEY_ID                = aws_kms_key.client_index.arn
    CLIENT_DOCUMENTS_BUCKET            = aws_s3_bucket.documents.bucket
  }
  secrets = {
    DB_USER     = "${local.app_login_secret["client_service"]}:username::"
    DB_PASSWORD = "${local.app_login_secret["client_service"]}:password::"
  }
  # The master secret is for db-migrate only.
  secret_arns        = [local.app_login_secret["client_service"], local.db_master_secret["clients"]]
  task_role_policies = { service = data.aws_iam_policy_document.client_service.json }
  tls = {
    mode            = "go"
    ca_pem          = local.ca_pem
    serves_tls      = true
    cert_secret_arn = aws_secretsmanager_secret.service_tls["client"].arn
  }
  migrations = local.migrations["clients"]

  service_connect = {
    namespace_arn = aws_service_discovery_http_namespace.this.arn
    aliases       = [{ port_name = "https", dns_name = local.sc.client, port = local.ports.client }]
  }

  load_balancer = merge(local.alb, {
    protocol          = "HTTPS"
    health_check_path = "/health"
    rules = [
      { priority = 30, paths = ["/api/clients", "/api/clients/*"] },
      { priority = 31, paths = ["/internal/v1/clients/*"] },
    ]
  })

  min_tasks          = local.min_tasks
  max_tasks          = local.max_tasks
  use_spot           = local.use_fargate_spot
  log_retention_days = local.log_retention_days

  depends_on = [
    aws_ecs_cluster_capacity_providers.this,
    aws_secretsmanager_secret_version.service_tls,
    aws_secretsmanager_secret_version.app_login,
  ]
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
    # The event-alert service's own app client, for /internal/alerts/accounts/lookup (ADR-0066).
    COGNITO_ALERT_DIRECTORY_CLIENT_IDS = aws_cognito_user_pool_client.alert_directory.id
  }
  secrets = {
    DB_USER     = "${local.app_login_secret["account_service"]}:username::"
    DB_PASSWORD = "${local.app_login_secret["account_service"]}:password::"
  }
  # The master secret is for db-migrate only.
  secret_arns        = [local.app_login_secret["account_service"], local.db_master_secret["accounts"]]
  task_role_policies = { service = data.aws_iam_policy_document.send_audit.json }
  tls = {
    mode            = "go"
    ca_pem          = local.ca_pem
    serves_tls      = true
    cert_secret_arn = aws_secretsmanager_secret.service_tls["account"].arn
  }
  migrations = local.migrations["accounts"]

  service_connect = {
    namespace_arn = aws_service_discovery_http_namespace.this.arn
    aliases       = [{ port_name = "https", dns_name = local.sc.account, port = local.ports.account }]
  }

  # /internal/accounts/lookup is for the ingest Lambda, which cannot use
  # Service Connect (ADR-0037, ADR-0019 decision 4). The event-alert worker is
  # a run-once task with no Service Connect proxy, so it reaches
  # /internal/alerts/accounts/lookup the same way (ADR-0066). API Gateway has
  # no route for /internal/*, so neither is public.
  load_balancer = merge(local.alb, {
    protocol          = "HTTPS"
    health_check_path = "/health"
    rules = [
      { priority = 40, paths = ["/api/accounts", "/api/accounts/*"] },
      { priority = 41, paths = ["/internal/accounts/*", "/internal/alerts/accounts/*"] },
    ]
  })

  min_tasks          = local.min_tasks
  max_tasks          = local.max_tasks
  use_spot           = local.use_fargate_spot
  log_retention_days = local.log_retention_days

  depends_on = [
    aws_ecs_cluster_capacity_providers.this,
    aws_secretsmanager_secret_version.service_tls,
    aws_secretsmanager_secret_version.app_login,
  ]
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
  # The master secret and chain-verify's password are for db-migrate only.
  secret_arns = concat(
    [for login in aws_secretsmanager_secret.audit_login : login.arn],
    [local.db_master_secret["audit"]],
  )
  task_role_policies = { service = data.aws_iam_policy_document.auditor.json }
  tls = {
    mode            = "go"
    ca_pem          = local.ca_pem
    serves_tls      = true
    cert_secret_arn = aws_secretsmanager_secret.service_tls["auditor"].arn
  }
  migrations = local.migrations["audit"]

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
# The SFTP receiver's read API. It checks each caller's token and asks
# account-service about ownership with it, so the ALB and dashboard-service
# call it directly (ADR-0064). Client Health uses a separately scoped private
# history route and the Client Health task's security group.

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
  port_name          = "https"

  environment = {
    APP_ENV                   = "production"
    APP_ADDR                  = ":${local.ports.transaction}"
    COGNITO_ISSUER_URI        = local.cognito_issuer
    COGNITO_HEALTH_CLIENT_IDS = aws_cognito_user_pool_client.client_health_read.id
    APP_TLS_CERT_FILE         = "/tls/cert.pem"
    APP_TLS_KEY_FILE          = "/tls/key.pem"
    DB_HOST                   = module.db["transactions"].address
    DB_PORT                   = "5432"
    DB_NAME                   = module.db["transactions"].db_name
    DB_SSLMODE                = "verify-full"
    DB_SSLROOTCERT            = "/tls/rds-ca.pem"
    ACCOUNT_SERVICE_BASE_URL  = "https://${local.sc.account}:${local.ports.account}"
    AWS_REGION                = local.region
    AUDIT_QUEUE_URL           = aws_sqs_queue.audit.url
    COGNITO_APP_CLIENT_ID     = aws_cognito_user_pool_client.web.id
  }
  secrets = {
    DB_USER     = "${local.app_login_secret["transaction_service"]}:username::"
    DB_PASSWORD = "${local.app_login_secret["transaction_service"]}:password::"
  }
  # The master secret and the ingest Lambda's password are for db-migrate only.
  secret_arns = [
    local.app_login_secret["transaction_service"],
    local.app_login_secret["transaction_ingest"],
    local.db_master_secret["transactions"],
  ]
  task_role_policies = { service = data.aws_iam_policy_document.send_audit.json }
  tls = {
    mode            = "go"
    ca_pem          = local.ca_pem
    serves_tls      = true
    cert_secret_arn = aws_secretsmanager_secret.service_tls["transaction"].arn
  }
  migrations = local.migrations["transactions"]

  # dashboard-service calls it here and checks its certificate, so Service
  # Connect proxies the port as TCP (ADR-0042 decision 7).
  service_connect = {
    namespace_arn = aws_service_discovery_http_namespace.this.arn
    aliases       = [{ port_name = "https", dns_name = local.sc.transaction, port = local.ports.transaction }]
  }

  # Ahead of account-service's /api/accounts/* rule (priority 40).
  load_balancer = merge(local.alb, {
    protocol          = "HTTPS"
    health_check_path = "/health"
    rules = [
      { priority = 15, paths = ["/api/accounts/*/transactions", "/api/transactions"] },
      { priority = 16, paths = ["/internal/v1/transactions"] },
    ]
  })

  min_tasks          = local.min_tasks
  max_tasks          = local.max_tasks
  use_spot           = local.use_fargate_spot
  log_retention_days = local.log_retention_days

  depends_on = [
    aws_ecs_cluster_capacity_providers.this,
    aws_secretsmanager_secret_version.service_tls,
    aws_secretsmanager_secret_version.app_login,
    aws_secretsmanager_secret_version.client_health_read,
  ]
}

# dashboard-service runs in this cluster but is deployed by the
# business-dashboard stack (ADR-0047), from the dashboard_inputs output.

# ------------------------------------------------------ event-alert-service
# api mode only. The worker and its schedule are in stacks/alert-worker
# (ADR-0048); AgentCore in Tokyo waits for the live spike (EDA-D1-02).
# Ingestion and the model stay switched off.

locals {
  # What the API needs to start the worker once for an agent's new domain (ADR-0080). The worker runs in
  # this cluster, in the same subnets and security group as the API, under the family named here.
  alert_search_on_add_environment = var.alert_search_on_add ? {
    ALERT_SEARCH_ON_ADD_MODE            = "ecs"
    ALERT_SEARCH_ON_ADD_CLUSTER         = aws_ecs_cluster.this.name
    ALERT_SEARCH_ON_ADD_TASK_DEFINITION = "${local.name}-event-alert-worker"
    ALERT_SEARCH_ON_ADD_SUBNETS         = join(",", local.app_subnet_ids)
    ALERT_SEARCH_ON_ADD_SECURITY_GROUP  = local.sg["alerts"]
  } : {}

  # Holdings source for the API and the worker (ADR-0066). The worker reads the same
  # values from alerts_worker_inputs, so the two cannot disagree. The directory is
  # reached through the internal ALB over HTTPS with the alert-directory app client;
  # its secret comes from Secrets Manager and is never an environment value.
  alert_database_holdings = var.alert_holdings_mode == "database"
  alert_holdings_environment = merge(
    { ALERT_HOLDINGS_MODE = var.alert_holdings_mode },
    local.alert_database_holdings ? {
      ALERT_CRM_ACCOUNT_DIRECTORY_URL = "${local.alb_base_url}/internal/alerts/accounts/lookup"
      ALERT_CRM_TOKEN_ENDPOINT        = "https://${aws_cognito_user_pool_domain.this.domain}.auth.${local.region}.amazoncognito.com/oauth2/token"
      ALERT_CRM_CLIENT_ID             = aws_cognito_user_pool_client.alert_directory.id
      ALERT_CRM_SCOPE                 = "crm-accounts/alert-directory"
    } : {},
  )
  alert_holdings_secrets = local.alert_database_holdings ? {
    ALERT_CRM_CLIENT_SECRET = "${aws_secretsmanager_secret.alert_directory.arn}:client_secret::"
  } : {}
  alert_holdings_secret_arns = local.alert_database_holdings ? [aws_secretsmanager_secret.alert_directory.arn] : []
}

# Database holdings read the account directory over HTTPS. Without the custom domain
# the internal ALB serves plain HTTP, and the service refuses a non-HTTPS directory URL.
resource "terraform_data" "alert_holdings_guard" {
  lifecycle {
    precondition {
      condition     = !local.alert_database_holdings || local.custom_domain
      error_message = "alert_holdings_mode = database needs domain_name: the account directory is reached over HTTPS through the internal ALB."
    }
  }
}

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
  port_name          = "https"

  environment = merge(local.alert_holdings_environment, {
    EVENT_ALERT_DB_URL            = "jdbc:postgresql://${module.db["alerts"].address}:5432/${module.db["alerts"].db_name}?sslmode=require"
    COGNITO_ISSUER_URI            = local.cognito_issuer
    COGNITO_APP_CLIENT_ID         = aws_cognito_user_pool_client.web.id
    ALERT_MODE                    = "API"
    ALERT_AUDIT_PUBLISHER_ENABLED = "true"
    AUDIT_QUEUE_URL               = aws_sqs_queue.audit.url
    AWS_REGION                    = local.region
    ALERT_INGESTION_ENABLED       = "false"
    ALERT_AI_ENABLED              = "false"
    ALERT_SCHEDULE_INTERVAL       = var.alert_schedule_interval # what the page says; the schedule is stacks/alert-worker's
    SPRING_FLYWAY_ENABLED         = "false" # db-migrate applies its migrations
  }, local.alert_search_on_add_environment)
  secrets = merge(local.alert_holdings_secrets, {
    EVENT_ALERT_DB_USERNAME = "${local.app_login_secret["event_alert_service"]}:username::"
    EVENT_ALERT_DB_PASSWORD = "${local.app_login_secret["event_alert_service"]}:password::"
  })
  # The master secret is for db-migrate only.
  secret_arns = concat([local.app_login_secret["event_alert_service"], local.db_master_secret["alerts"]], local.alert_holdings_secret_arns)
  task_role_policies = merge(
    { service = data.aws_iam_policy_document.send_audit.json },
    var.alert_search_on_add ? { search_on_add = data.aws_iam_policy_document.start_alert_worker.json } : {},
  )
  tls = {
    mode            = "java"
    ca_pem          = local.ca_pem
    serves_tls      = true
    cert_secret_arn = aws_secretsmanager_secret.service_tls["alerts"].arn
  }
  migrations = local.migrations["alerts"]

  load_balancer = merge(local.alb, {
    protocol          = "HTTPS"
    health_check_path = "/actuator/health"
    rules = [
      { priority = 60, paths = ["/api/alerts", "/api/alerts/*"] },
      { priority = 61, paths = ["/api/alert-portfolios", "/api/alert-portfolios/*"] },
    ]
  })

  min_tasks          = local.min_tasks
  max_tasks          = local.max_tasks
  use_spot           = local.use_fargate_spot
  log_retention_days = local.log_retention_days

  depends_on = [
    aws_ecs_cluster_capacity_providers.this,
    aws_secretsmanager_secret_version.service_tls,
    aws_secretsmanager_secret_version.app_login,
  ]
}

# ------------------------------------------------ client-health-monitor-service
# API mode. Assignment lookup uses the scoped machine client when the internal
# ALB has HTTPS; without a custom domain it stays blank and fails closed.

module "client_health_service" {
  source = "../../modules/ecs-service"

  name               = "client-health-monitor-service"
  name_prefix        = local.name
  cluster_arn        = aws_ecs_cluster.this.arn
  cluster_name       = aws_ecs_cluster.this.name
  subnet_ids         = local.app_subnet_ids
  security_group_ids = [local.sg["client_health"]]
  image              = local.image["client-health-monitor-service"]
  cpu                = 512
  memory             = 1024
  cpu_architecture   = var.cpu_architecture
  port               = local.ports.health
  port_name          = "https"

  environment = {
    SPRING_DATASOURCE_URL                                = "jdbc:postgresql://${module.db["health"].address}:5432/${module.db["health"].db_name}?sslmode=require"
    SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI = local.cognito_issuer
    IDP_COGNITO_APP_CLIENT_ID                            = aws_cognito_user_pool_client.web.id
    CLIENT_HEALTH_MODE                                   = "API"
    CLIENT_HEALTH_AI_ENABLED                             = "false"
    CLIENT_HEALTH_AUDIT_PUBLISHER_ENABLED                = "true"
    CLIENT_HEALTH_AUDIT_QUEUE_URL                        = aws_sqs_queue.audit.url
    CLIENT_HEALTH_AUDIT_REGION                           = local.region
    CLIENT_HEALTH_CLOUDWATCH_METRICS_ENABLED             = "true"
    CLIENT_HEALTH_CLOUDWATCH_METRICS_NAMESPACE           = "Scrooge/ClientHealth"
    CLIENT_HEALTH_CLOUDWATCH_METRICS_REGION              = local.region
    CLIENT_HEALTH_CLOUDWATCH_METRICS_STEP                = "1m"
    CLIENT_HEALTH_ASSIGNMENT_BASE_URL                    = local.custom_domain ? local.alb_base_url : ""
    CLIENT_HEALTH_ASSIGNMENT_OAUTH_TOKEN_URI             = local.cognito_token_url
    CLIENT_HEALTH_ASSIGNMENT_OAUTH_SCOPE                 = "crm-clients/assignment.read"
    SPRING_FLYWAY_ENABLED                                = "false" # db-migrate applies its migrations
  }
  secrets = {
    SPRING_DATASOURCE_USERNAME                   = "${local.app_login_secret["client_health_service"]}:username::"
    SPRING_DATASOURCE_PASSWORD                   = "${local.app_login_secret["client_health_service"]}:password::"
    CLIENT_HEALTH_ASSIGNMENT_OAUTH_CLIENT_ID     = "${aws_secretsmanager_secret.client_health_read.arn}:client_id::"
    CLIENT_HEALTH_ASSIGNMENT_OAUTH_CLIENT_SECRET = "${aws_secretsmanager_secret.client_health_read.arn}:client_secret::"
  }
  # The master secret is for db-migrate only.
  secret_arns = [
    local.app_login_secret["client_health_service"],
    local.db_master_secret["health"],
    aws_secretsmanager_secret.client_health_read.arn,
  ]
  task_role_policies = {
    audit   = data.aws_iam_policy_document.send_audit.json
    metrics = data.aws_iam_policy_document.client_health_metrics.json
  }
  tls = {
    mode            = "java"
    ca_pem          = local.ca_pem
    serves_tls      = true
    cert_secret_arn = aws_secretsmanager_secret.service_tls["health"].arn
  }
  migrations = local.migrations["health"]

  load_balancer = merge(local.alb, {
    protocol          = "HTTPS"
    health_check_path = "/actuator/health/readiness"
    rules = [{
      priority = 65
      paths    = ["/api/relationship-health", "/api/relationship-health/*"]
    }]
  })

  min_tasks          = local.min_tasks
  max_tasks          = local.max_tasks
  use_spot           = local.use_fargate_spot
  log_retention_days = local.log_retention_days

  depends_on = [
    aws_ecs_cluster_capacity_providers.this,
    aws_secretsmanager_secret_version.service_tls,
    aws_secretsmanager_secret_version.app_login,
    aws_secretsmanager_secret_version.client_health_read,
  ]
}

# One-shot daily analysis task from the same image (ADR-0034). It has no
# service, port or audit publisher: state and audit rows are committed to
# health-db, and the API service relays the outbox.
module "client_health_analysis" {
  source = "../../modules/ecs-service"

  name               = "client-health-analysis"
  name_prefix        = local.name
  create_service     = false
  cluster_arn        = aws_ecs_cluster.this.arn
  cluster_name       = aws_ecs_cluster.this.name
  subnet_ids         = local.app_subnet_ids
  security_group_ids = [local.sg["client_health"]]
  image              = local.image["client-health-monitor-service"]
  cpu                = 512
  memory             = 1024
  cpu_architecture   = var.cpu_architecture

  environment = {
    SPRING_DATASOURCE_URL                                = "jdbc:postgresql://${module.db["health"].address}:5432/${module.db["health"].db_name}?sslmode=require"
    SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI = local.cognito_issuer
    IDP_COGNITO_APP_CLIENT_ID                            = aws_cognito_user_pool_client.web.id
    CLIENT_HEALTH_MODE                                   = "ANALYSIS"
    CLIENT_HEALTH_ANALYSIS_LEASE_DURATION                = "60m"
    CLIENT_HEALTH_AI_ENABLED                             = tostring(var.client_health_ai_enabled)
    CLIENT_HEALTH_AI_MODEL_ID                            = var.client_health_bedrock_model_id
    CLIENT_HEALTH_AUDIT_PUBLISHER_ENABLED                = "false"
    CLIENT_HEALTH_CLOUDWATCH_METRICS_ENABLED             = "true"
    CLIENT_HEALTH_CLOUDWATCH_METRICS_NAMESPACE           = "Scrooge/ClientHealth"
    CLIENT_HEALTH_CLOUDWATCH_METRICS_REGION              = local.region
    CLIENT_HEALTH_CLOUDWATCH_METRICS_STEP                = "1m"
    CLIENT_HEALTH_TRANSACTION_ENDPOINT                   = local.custom_domain ? "${local.alb_base_url}/internal/v1/transactions" : ""
    CLIENT_HEALTH_TRANSACTION_TOKEN_URI                  = local.cognito_token_url
    CLIENT_HEALTH_TRANSACTION_SCOPE                      = "crm-transactions/read"
    CLIENT_HEALTH_ASSIGNMENT_BASE_URL                    = local.custom_domain ? local.alb_base_url : ""
    CLIENT_HEALTH_ASSIGNMENT_OAUTH_TOKEN_URI             = local.cognito_token_url
    CLIENT_HEALTH_ASSIGNMENT_OAUTH_SCOPE                 = "crm-clients/assignment.read"
    SPRING_FLYWAY_ENABLED                                = "false"
  }
  secrets = {
    SPRING_DATASOURCE_USERNAME                   = "${local.app_login_secret["client_health_service"]}:username::"
    SPRING_DATASOURCE_PASSWORD                   = "${local.app_login_secret["client_health_service"]}:password::"
    CLIENT_HEALTH_TRANSACTION_CLIENT_ID          = "${aws_secretsmanager_secret.client_health_read.arn}:client_id::"
    CLIENT_HEALTH_TRANSACTION_CLIENT_SECRET      = "${aws_secretsmanager_secret.client_health_read.arn}:client_secret::"
    CLIENT_HEALTH_ASSIGNMENT_OAUTH_CLIENT_ID     = "${aws_secretsmanager_secret.client_health_read.arn}:client_id::"
    CLIENT_HEALTH_ASSIGNMENT_OAUTH_CLIENT_SECRET = "${aws_secretsmanager_secret.client_health_read.arn}:client_secret::"
  }
  secret_arns = [
    local.app_login_secret["client_health_service"],
    aws_secretsmanager_secret.client_health_read.arn,
  ]
  task_role_policies = {
    metrics = data.aws_iam_policy_document.client_health_metrics.json
    bedrock = data.aws_iam_policy_document.client_health_bedrock.json
  }

  log_retention_days = local.log_retention_days

  depends_on = [
    module.client_health_service,
    aws_secretsmanager_secret_version.app_login,
    aws_secretsmanager_secret_version.client_health_read,
  ]
}
