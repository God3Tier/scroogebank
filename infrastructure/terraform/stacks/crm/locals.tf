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

  # The high-availability demo week (ADR-0029).
  high_availability = var.high_availability

  db_settings = {
    instance_class        = "db.t4g.micro" # ADR-0023
    allocated_storage     = 20             # the smallest gp3 volume RDS allows
    max_allocated_storage = 100            # storage auto scaling limit
    backup_retention_days = 7              # the point-in-time recovery window
    multi_az              = local.high_availability
    deletion_protection   = local.deletion_protection
    skip_final_snapshot   = !local.deletion_protection
  }

  # One Elastic IP per NAT gateway, kept even while the stack is stopped so the
  # SFTP server's allow-list never changes. The NAT gateways themselves (about
  # USD 43 a month each) exist only while running (ADR-0042 decision 4).
  nat_eip_count     = local.high_availability ? 2 : 1
  nat_gateway_count = var.running ? local.nat_eip_count : 0

  enable_waf    = local.is_prod # ADR-0029: no WAF in staging
  enable_backup = local.is_prod # AWS Backup with the cross-region copy

  log_retention_days = 30

  # Tasks per service. One task keeps Fargate cost down; auto scaling adds
  # more under load. The stop model scales to 0 between sessions.
  min_tasks = var.running ? 1 : 0
  max_tasks = var.running ? 6 : 0

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
