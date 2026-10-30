variable "aws_region" {
  description = "Home region of the account's CRM stacks."
  type        = string
  default     = "ap-southeast-1"
}

variable "state_bucket_name" {
  description = "Name of the Terraform state bucket. Defaults to crm-tfstate-<account id>."
  type        = string
  default     = null
}

variable "enable_github_oidc" {
  description = "Create the GitHub Actions OIDC provider, for a deploy workflow (ADR-0028). Off while deployments are run by hand; set false too if the account already has one."
  type        = bool
  default     = false
}

variable "trail_retention_days" {
  description = "Days to keep CloudTrail log files."
  type        = number
  default     = 90
}
