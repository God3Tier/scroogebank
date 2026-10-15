data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name       = "crm-${var.legal_entity}-${var.environment}"
  account_id = data.aws_caller_identity.current.account_id
  region     = var.aws_region
  is_prod    = var.environment == "prod"

  # Teardown: apply with deletion_protection = false, then destroy. That also
  # deletes the secrets at once (secret names are fixed, so a 7-day recovery
  # window would block re-creating the stack) and skips the RDS final
  # snapshots (fixed names, which a second destroy would collide with).
  deletion_protection = var.deletion_protection == null ? var.environment == "prod" : var.deletion_protection
  azs                 = slice(data.aws_availability_zones.available.names, 0, 2)

  # Tags on every resource (ADR-0026: LegalEntity for data segregation and
  # cost allocation).
  tags = {
    Project     = "cs301-crm"
    LegalEntity = var.legal_entity
    Environment = var.environment
    ManagedBy   = "terraform"
  }

  # ------------------------------------------------------------------------
  # Free plan (ADR-0042). An AWS Free account plan refuses some settings
  # outright (FreeTierRestrictionError): RDS instance classes above micro, and
  # automated backup retention above 1 day. The other changes keep spending
  # within the plan's credits; none of them changes how the services behave.
  # ------------------------------------------------------------------------
  free_plan = var.free_plan

  # The high-availability demo week (ADR-0029), never on the Free plan.
  high_availability = var.high_availability && !local.free_plan

  db_settings = {
    instance_class        = "db.t4g.micro"            # micro on both plans (ADR-0023)
    allocated_storage     = 20                        # the free-tier storage size
    max_allocated_storage = local.free_plan ? 0 : 100 # 0 turns storage auto scaling off
    backup_retention_days = local.free_plan ? 1 : 7   # point-in-time recovery window; 1 day is the Free plan's maximum
    multi_az              = local.high_availability
    deletion_protection   = local.deletion_protection
    skip_final_snapshot   = !local.deletion_protection
  }

  # DynamoDB: provisioned 5/5 stays inside the always-free 25 RCU/WCU;
  # point-in-time recovery is billed per GB, so it is off on the Free plan.
  dynamodb_billing_mode = local.free_plan ? "PROVISIONED" : "PAY_PER_REQUEST"
  dynamodb_pitr         = !local.free_plan

  # One Elastic IP per NAT gateway, kept even while the stack is stopped so the
  # SFTP server's allow-list never changes. The NAT gateways themselves (about
  # USD 43 a month each) exist only while running (ADR-0042 decision 4).
  nat_eip_count     = local.high_availability ? 2 : 1
  nat_gateway_count = var.running ? local.nat_eip_count : 0

  enable_waf    = local.is_prod && !local.free_plan # ADR-0029: no WAF in staging
  enable_backup = local.is_prod && !local.free_plan # AWS Backup with the cross-region copy

  log_retention_days = local.free_plan ? 7 : 30

  # Tasks per service. The stop model scales to 0 between sessions.
  min_tasks = var.running ? (local.free_plan ? 1 : 2) : 0
  max_tasks = var.running ? (local.free_plan ? 2 : 6) : 0

  use_fargate_spot = coalesce(var.use_fargate_spot, var.environment == "staging")

  # ------------------------------------------------------------------ images
  ecr_repositories = [
    "user-service",
    "client-service",
    "account-service",
    "auditor",
    "transaction-service",
    "dashboard-service",
    "event-alert-service",
  ]

  image = {
    for repo in local.ecr_repositories :
    repo => "${aws_ecr_repository.this[repo].repository_url}:${lookup(var.image_tags, repo, var.image_tag)}"
  }

  # ------------------------------------------------------------- Cognito
  cognito_issuer = "https://cognito-idp.${local.region}.amazonaws.com/${aws_cognito_user_pool.this.id}"

  # Service Connect names that services call each other by.
  sc = {
    user        = "user-service"
    client      = "client-service"
    account     = "account-service"
    auditor     = "auditor"
    transaction = "transaction-service"
  }

  alb_scheme = var.internal_alb_certificate_arn == null ? "http" : "https"
  alb_port   = var.internal_alb_certificate_arn == null ? 80 : 443
}
