variable "legal_entity" {
  description = "Legal entity of the CRM stack this belongs to (ADR-0026)."
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
  description = "Region of the CRM stack. Bedrock Runtime is always called here (ADR-0054 decision 3)."
  type        = string
  default     = "ap-southeast-1"
}

variable "state_bucket" {
  description = "The bootstrap stack's state_bucket output. The CRM stack's state is read from crm/<legal_entity>/<environment>.tfstate in it."
  type        = string
}

variable "image_tag" {
  description = "engagement-service image tag. CI pushes one tag per commit."
  type        = string
  default     = "latest"
}

variable "mail_enabled" {
  description = "Send mail through SES. Off, the service queues invites and reminders but sends nothing, and draft sends return 503."
  type        = bool
  default     = true
}

variable "sender_address" {
  description = "The verified address mail comes from when the CRM stack has no domain_name (ADR-0055); the same value as the mail-identity stack's sender_address. Ignored when the CRM stack has a domain. Real addresses go only in the git-ignored tfvars."
  type        = string
  default     = null

  validation {
    condition     = var.sender_address == null || can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.sender_address))
    error_message = "sender_address must be an email address."
  }
}

variable "ai_enabled" {
  description = "The AI drafting kill switch, ENGAGEMENT_AI_ENABLED (ADR-0054 decision 7). Off, every draft is a template. Turn it on only for test and demo runs."
  type        = bool
  default     = false
}

variable "alert_reports_enabled" {
  description = "The rollout kill switch for emailing event reports to clients, ENGAGEMENT_ALERT_REPORTS_ENABLED (ADR-0084). On by default; set false to stop any report being queued or sent. A report is still sent only when an agent approves it, and mail still needs the SES provider and a verified sender."
  type        = bool
  default     = true
}

variable "bedrock_model_id" {
  description = "The inference profile drafts use (ADR-0067, which replaced ADR-0054 decision 3's Nova Micro). A global profile is not allowed."
  type        = string
  default     = "apac.amazon.nova-lite-v1:0"

  validation {
    condition     = startswith(var.bedrock_model_id, "apac.")
    error_message = "Only an Asia-Pacific (apac.) inference profile is allowed (ADR-0054, ADR-0067)."
  }
}

variable "bedrock_foundation_model_id" {
  description = "The foundation model behind bedrock_model_id."
  type        = string
  default     = "amazon.nova-lite-v1:0"
}

variable "bedrock_profile_regions" {
  description = "Where the inference profile may run the request, as recorded in ADR-0067. Re-check with aws bedrock get-inference-profile before the demo."
  type        = list(string)
  default     = ["ap-southeast-1", "ap-southeast-2", "ap-northeast-1", "ap-northeast-2", "ap-northeast-3", "ap-south-1"]
}
