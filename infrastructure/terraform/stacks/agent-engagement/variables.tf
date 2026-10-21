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

variable "ai_enabled" {
  description = "The AI drafting kill switch, ENGAGEMENT_AI_ENABLED (ADR-0054 decision 7). Off, every draft is a template. Turn it on only for test and demo runs."
  type        = bool
  default     = false
}

variable "bedrock_model_id" {
  description = "The inference profile drafts use (ADR-0054 decision 3). Nova Lite's Asia-Pacific profile may replace it if Nova Micro fails the evaluation; a global profile is not allowed."
  type        = string
  default     = "apac.amazon.nova-micro-v1:0"

  validation {
    condition     = startswith(var.bedrock_model_id, "apac.")
    error_message = "Only an Asia-Pacific (apac.) inference profile is allowed (ADR-0054 decision 3)."
  }
}

variable "bedrock_foundation_model_id" {
  description = "The foundation model behind bedrock_model_id."
  type        = string
  default     = "amazon.nova-micro-v1:0"
}

variable "bedrock_profile_regions" {
  description = "Where the inference profile may run the request, as recorded in ADR-0054. Re-check with aws bedrock get-inference-profile before the demo."
  type        = list(string)
  default     = ["ap-southeast-1", "ap-southeast-2", "ap-northeast-1", "ap-northeast-2", "ap-northeast-3", "ap-south-1"]
}
