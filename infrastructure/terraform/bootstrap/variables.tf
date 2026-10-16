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

variable "total_budget_usd" {
  description = "The project's hard spending limit for the whole project (project_plan.md section 1), counted from budget_start_date."
  type        = number
  default     = 300
}

variable "budget_start_date" {
  description = "YYYY-MM-DD from which crm-total counts spending: the first day the project could spend on this account. Spending before it is not counted."
  type        = string

  validation {
    condition     = can(regex("^\\d{4}-\\d{2}-\\d{2}$", var.budget_start_date))
    error_message = "budget_start_date must be YYYY-MM-DD."
  }
}

variable "bedrock_budget_usd" {
  description = "Monthly Bedrock budget."
  type        = number
  default     = 25
}

variable "budget_emails" {
  description = "Who receives budget alerts."
  type        = list(string)

  validation {
    condition     = length(var.budget_emails) > 0
    error_message = "Name at least one address for budget alerts."
  }
}
