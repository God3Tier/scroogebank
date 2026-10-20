# The business dashboard (XF2, ADR-0033) in its own stack (ADR-0047):
# dashboard-service's image repository, security group and ECS service. It
# runs inside the CRM stack's VPC, cluster, internal ALB and Service Connect
# namespace, and reads them from that stack's dashboard_inputs output. Apply
# the CRM stack first and destroy this one first.

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
  crm  = data.terraform_remote_state.crm.outputs.dashboard_inputs
  name = local.crm.name_prefix
  port = 8085

  # The ecs-service module names the task role <prefix>-<service>-task, so the
  # CRM stack can allow it on the audit queue before this stack exists.
  task_role_arn = "arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:role/${local.name}-dashboard-service-task"
}

# -------------------------------------------------------------------- image

resource "aws_ecr_repository" "dashboard" {
  name                 = "${local.name}/dashboard-service"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = !local.crm.deletion_protection

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "dashboard" {
  repository = aws_ecr_repository.dashboard.name
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
# dashboard, and the dashboard to the services it reads. The rules on the
# CRM stack's groups are separate resources, as the CRM stack's own are.

resource "aws_security_group" "dashboard" {
  name        = "${local.name}-dashboard"
  description = "dashboard-service tasks"
  vpc_id      = local.crm.vpc_id
  tags        = { Name = "${local.name}-dashboard" }

  lifecycle {
    # The audit queue denies every sender not on its list, so without this
    # the dashboard would run but lose its audit events.
    precondition {
      condition     = contains(local.crm.audit_producer_role_arns, local.task_role_arn)
      error_message = "The audit queue does not allow ${local.task_role_arn}. Add it to the CRM stack's extra_audit_producer_role_arns and apply that stack first."
    }
  }
}

resource "aws_vpc_security_group_ingress_rule" "from_alb" {
  security_group_id            = aws_security_group.dashboard.id
  referenced_security_group_id = local.crm.alb_sg
  ip_protocol                  = "tcp"
  from_port                    = local.port
  to_port                      = local.port
  description                  = "alb to dashboard"
}

resource "aws_vpc_security_group_egress_rule" "anywhere" {
  security_group_id = aws_security_group.dashboard.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
  description       = "AWS APIs and Cognito through the NAT gateway, and other tasks"
}

resource "aws_vpc_security_group_ingress_rule" "upstream" {
  for_each                     = local.crm.upstreams
  security_group_id            = each.value.security_group
  referenced_security_group_id = aws_security_group.dashboard.id
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
  description                  = "dashboard to ${each.key}"
}

# ------------------------------------------------------- dashboard-service

data "aws_iam_policy_document" "send_audit" {
  statement {
    sid       = "SendAuditEvents"
    actions   = ["sqs:SendMessage"]
    resources = [local.crm.audit_queue_arn]
  }
}

module "dashboard_service" {
  source = "../../modules/ecs-service"

  name               = "dashboard-service"
  name_prefix        = local.name
  cluster_arn        = local.crm.cluster_arn
  cluster_name       = local.crm.cluster_name
  subnet_ids         = local.crm.app_subnet_ids
  security_group_ids = [aws_security_group.dashboard.id]
  image              = "${aws_ecr_repository.dashboard.repository_url}:${var.image_tag}"
  # 1 vCPU, not 0.5: on 0.5, shared with the Service Connect proxy, the JVM
  # took 34 s to start and 1.5-3.4 s for its first call to each upstream.
  # Fargate pairs 1 vCPU with at least 2 GB.
  cpu              = 1024
  memory           = 2048
  cpu_architecture = local.crm.cpu_architecture
  port             = local.port
  app_protocol     = "http"

  environment = {
    OIDC_ISSUER_URI          = local.crm.cognito_issuer
    COGNITO_APP_CLIENT_ID    = local.crm.cognito_web_client_id
    AUDIT_QUEUE_URL          = local.crm.audit_queue_url
    AWS_REGION               = var.aws_region
    USER_SERVICE_BASE_URL    = local.crm.upstreams.user.url
    CLIENT_SERVICE_BASE_URL  = local.crm.upstreams.client.url
    ACCOUNT_SERVICE_BASE_URL = local.crm.upstreams.account.url
  }
  task_role_policies = { service = data.aws_iam_policy_document.send_audit.json }
  tls                = { mode = "java", ca_pem = local.crm.ca_pem }

  # A client only: nothing calls the dashboard inside the VPC.
  service_connect = {
    namespace_arn = local.crm.namespace_arn
    aliases       = []
  }

  load_balancer = merge(local.crm.alb, {
    protocol          = "HTTP"
    health_check_path = "/actuator/health"
    rules             = [{ priority = 50, paths = ["/api/dashboard", "/api/dashboard/*"] }]
  })

  # The CRM stack's running variable starts and stops these tasks too (ADR-0029).
  min_tasks          = local.crm.min_tasks
  max_tasks          = local.crm.max_tasks
  use_spot           = local.crm.use_spot
  log_retention_days = local.crm.log_retention_days
}

# ------------------------------------------------------------ CI delivery
# When the CRM stack has the GitHub deploy role (ADR-0028), let it push this
# image and roll out this service. The role already registers task
# definitions and updates any service in the cluster.

data "aws_iam_policy_document" "github_deploy" {
  count = local.crm.github_deploy_role_name == null ? 0 : 1

  statement {
    sid = "PushDashboardImage"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImages",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = [aws_ecr_repository.dashboard.arn]
  }

  statement {
    sid       = "PassDashboardRoles"
    actions   = ["iam:PassRole"]
    resources = [module.dashboard_service.task_role_arn, module.dashboard_service.execution_role_arn]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  count  = local.crm.github_deploy_role_name == null ? 0 : 1
  name   = "deploy-business-dashboard"
  role   = local.crm.github_deploy_role_name
  policy = data.aws_iam_policy_document.github_deploy[0].json
}
