variable "legal_entity" {
  description = "Legal entity of the CRM stack this mail identity serves (ADR-0026)."
  type        = string
  default     = "sg"

  validation {
    condition     = can(regex("^[a-z]{2,8}$", var.legal_entity))
    error_message = "legal_entity must be 2 to 8 lower-case letters."
  }
}

variable "environment" {
  description = "staging or prod (ADR-0029), the same as the CRM stack's."
  type        = string

  validation {
    condition     = contains(["staging", "prod"], var.environment)
    error_message = "environment must be staging or prod."
  }
}

variable "aws_region" {
  description = "Region SES sends from: the CRM stack's (ADR-0053 decision 1)."
  type        = string
  default     = "ap-southeast-1"
}

variable "domain_name" {
  description = "The registered domain whose Route 53 hosted zone already exists, the CRM stack's domain_name. The sending domain is mail.<app host> under it."
  type        = string
}

variable "verified_recipients" {
  description = "Team inboxes SES may send to while the account is in the SES sandbox (ADR-0053 decision 8). Each gets a verification email to click. Real addresses go only in the git-ignored tfvars."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for address in var.verified_recipients : can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", address))])
    error_message = "Each verified recipient must be an email address."
  }
}
