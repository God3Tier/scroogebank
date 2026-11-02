# ----------------------------------------------------------------- the stack

variable "legal_entity" {
  description = "Legal entity this stack serves (ADR-0026). Only Singapore (sg) is deployed."
  type        = string
  default     = "sg"

  validation {
    condition     = can(regex("^[a-z]{2,8}$", var.legal_entity))
    error_message = "legal_entity must be 2 to 8 lower-case letters."
  }
}

variable "environment" {
  description = "staging or prod (ADR-0029)."
  type        = string

  validation {
    condition     = contains(["staging", "prod"], var.environment)
    error_message = "environment must be staging or prod."
  }
}

variable "aws_region" {
  description = "Region of the legal entity's stack."
  type        = string
  default     = "ap-southeast-1"
}

variable "dr_region" {
  description = "Region that receives AWS Backup copies."
  type        = string
  default     = "ap-southeast-2"
}

# --------------------------------------------------------- cost and lifecycle

variable "high_availability" {
  description = "The high-availability demo week (ADR-0029): Multi-AZ standbys and a NAT gateway per AZ."
  type        = bool
  default     = false
}

variable "api_throttle_rate_limit" {
  description = "API Gateway's steady request rate for the whole API, per second (ADR-0070): the requirement's 100 concurrent agents at about 100 requests a second, with twice the headroom."
  type        = number
  default     = 200
}

variable "api_throttle_burst_limit" {
  description = "API Gateway's burst for the whole API (ADR-0070), twice the rate."
  type        = number
  default     = 400
}

variable "enable_test_tooling" {
  description = "Test-only tooling for a load or security test (ADR-0071): a ZAP scanner task allowed into the internal ALB, and a bucket for test inputs and reports. Off outside a test; destroyed with the stack."
  type        = bool
  default     = false
}

variable "alert_holdings_mode" {
  description = "Where the event-alert API and worker read holdings (ADR-0066): fixture (the bundled synthetic JSON) or database (portfolios agents record against CRM accounts). Both read this one value, so they cannot differ. database needs the custom domain: the account directory is reached over HTTPS and never over HTTP. Apply the application's database migrations before switching to database."
  type        = string
  default     = "fixture"

  validation {
    condition     = contains(["fixture", "database"], var.alert_holdings_mode)
    error_message = "alert_holdings_mode must be fixture or database."
  }
}

variable "running" {
  description = "false is the time between sessions (ADR-0029 stop model): every service scales to 0 tasks, the schedules pause and the NAT gateways are removed (their Elastic IPs stay). Stop and start the RDS instances with scripts/aws-session.sh."
  type        = bool
  default     = false
}

variable "deletion_protection" {
  description = "Protect the databases, client-keys table, ALB and user pool from deletion, and keep buckets that still hold objects. Defaults to true for prod. Apply with false before terraform destroy."
  type        = bool
  default     = null
}

variable "use_fargate_spot" {
  description = "Run services on Fargate Spot. ADR-0029 uses it for staging."
  type        = bool
  default     = null
}

variable "cpu_architecture" {
  description = "X86_64, or ARM64 once CI builds arm64 images."
  type        = string
  default     = "X86_64"
}

variable "enable_container_insights" {
  description = "ECS Container Insights (extra CloudWatch metrics, billed)."
  type        = bool
  default     = false
}

# ------------------------------------------------------------------- images

variable "image_tag" {
  description = "Image tag deployed for every service unless image_tags overrides it. CI pushes one tag per commit."
  type        = string
  default     = "latest"
}

variable "image_tags" {
  description = "Per-service image tags, keyed by ECR repository name (for example account-service)."
  type        = map(string)
  default     = {}
}

# ------------------------------------------------------------------ identity

variable "cognito_domain_prefix" {
  description = "Prefix of the managed login domain (<prefix>.auth.<region>.amazoncognito.com). Defaults to the stack name plus a random suffix."
  type        = string
  default     = null
}

variable "local_dev_origins" {
  description = "Extra origins allowed as sign-in callbacks for an explicit local-development session (ADR-0025 decision 2). Keep empty in prod."
  type        = list(string)
  default     = []

  validation {
    condition     = var.environment != "prod" || length(var.local_dev_origins) == 0
    error_message = "Production must not allow local-development OAuth callback origins."
  }
}

variable "root_admin_email" {
  description = "The root admin user-service creates on first start. A mailbox the operator can read: Cognito e-mails the temporary password there. Changing it after the first start creates a second root admin."
  type        = string
}

# --------------------------------------------------------------- networking

variable "domain_name" {
  description = "A domain registered in Route 53 in this account, such as example.com, whose public hosted zone Route 53 created (ADR-0042 decision 8). Set, the app is served at <legal_entity>[-<environment>].<domain_name> and the internal ALB listens on HTTPS. Null, the app uses the default *.cloudfront.net address and the ALB listens on HTTP inside the VPC."
  type        = string
  default     = null

  validation {
    condition     = var.domain_name == null || can(regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$", var.domain_name))
    error_message = "domain_name must be a lower-case domain such as example.com, without a trailing dot."
  }
}

# ------------------------------------------------------- transaction import

variable "enable_ingest_lambda" {
  description = "Deploy the transaction-ingest Lambda (ADR-0024). Needs ingest_lambda_zip and the sftp-server stack's outputs; see docs/runbooks/deploy-aws.md."
  type        = bool
  default     = false
}

variable "ingest_lambda_zip" {
  description = "Path to the ingest Lambda's zip, built by `make lambda` in services/sftp: a bootstrap binary for provided.al2023 on arm64, and rds-ca.pem."
  type        = string
  default     = null
}

variable "sftp_server_address" {
  description = "host:port of the external SFTP server, from the sftp-server stack's outputs."
  type        = string
  default     = null
}

variable "sftp_host_key_fingerprint" {
  description = "SHA256 fingerprint of the SFTP server's host key, from the sftp-server stack's outputs."
  type        = string
  default     = null
}

# ----------------------------------------------------------- Cognito triggers

variable "enable_cognito_triggers" {
  description = "Deploy the login and password-reset audit triggers (ADR-0040). Needs the built zip. Keep true: false is for the mocked tests, and leaves sign-ins unaudited."
  type        = bool
  default     = true
}

variable "cognito_triggers_zip" {
  description = "Path to services/cognito-triggers/target/cognito-triggers.zip, relative to this directory."
  type        = string
  default     = "../../../../services/cognito-triggers/target/cognito-triggers.zip"
}

# ------------------------------------------------------- alarms and delivery

variable "alarm_email" {
  description = "E-mail address subscribed to the alarm topic. Confirm the subscription e-mail AWS sends."
  type        = string
  default     = null
}

variable "enable_github_deploy_role" {
  description = "Create the role a GitHub Actions deploy workflow assumes through OIDC (ADR-0028). Needs the bootstrap stack's OIDC provider. Off while deployments are run by hand."
  type        = bool
  default     = false
}

variable "github_repository" {
  description = "owner/name of the GitHub repository allowed to deploy through OIDC (ADR-0028)."
  type        = string
  default     = "cs301-itsa/project-2026-27t1-g3-t6"
}

variable "github_deploy_branches" {
  description = "Branches whose workflows may assume the deploy role. Defaults to main for prod and staging for staging."
  type        = list(string)
  default     = null
}

variable "extra_audit_producer_role_arns" {
  description = "Other IAM roles allowed to send to the audit queue, such as the alert worker's (EDA-D1-02)."
  type        = list(string)
  default     = []
}

variable "client_health_ai_enabled" {
  description = "The Client Health wording kill switch, CLIENT_HEALTH_AI_ENABLED (ADR-0034 decision 5). Off, every explanation is a template. On in prod (ADR-0067)."
  type        = bool
  default     = false
}

variable "client_health_bedrock_model_id" {
  description = "The inference profile the analysis task words explanations with (ADR-0067); a global profile is not allowed."
  type        = string
  default     = "apac.amazon.nova-lite-v1:0"

  validation {
    condition     = startswith(var.client_health_bedrock_model_id, "apac.")
    error_message = "Only an Asia-Pacific (apac.) inference profile is allowed (ADR-0067)."
  }
}

variable "client_health_bedrock_foundation_model_id" {
  description = "The foundation model behind client_health_bedrock_model_id."
  type        = string
  default     = "amazon.nova-lite-v1:0"
}

variable "client_health_bedrock_profile_regions" {
  description = "Where the inference profile may run the request, as recorded in ADR-0067. Re-check with aws bedrock get-inference-profile before the demo."
  type        = list(string)
  default     = ["ap-southeast-1", "ap-southeast-2", "ap-northeast-1", "ap-northeast-2", "ap-northeast-3", "ap-south-1"]
}
