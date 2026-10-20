variable "legal_entity" {
  description = "Legal entity of the CRM stack this worker belongs to (ADR-0026)."
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
  description = "Region of the CRM stack."
  type        = string
  default     = "ap-southeast-1"
}

variable "state_bucket" {
  description = "The bootstrap stack's state_bucket output. The CRM stack's state is read from crm/<legal_entity>/<environment>.tfstate in it."
  type        = string
}

variable "ingestion_enabled" {
  description = "Let the worker search public news (ALERT_INGESTION_ENABLED) and turn its schedule on. Keep false until the AgentCore client exists and the live spike has passed (ADR-0031)."
  type        = bool
  default     = false
}

variable "ai_enabled" {
  description = "Let the worker call Bedrock (ALERT_AI_ENABLED) and give its task role bedrock:InvokeModel on the pinned profile. Keep false until the AI benchmark gates pass (ADR-0010)."
  type        = bool
  default     = false
}

variable "schedule" {
  description = "EventBridge Scheduler expression for worker runs (ADR-0031 decision 1: every four hours)."
  type        = string
  default     = "rate(4 hours)"
}

variable "bedrock_region" {
  description = "Region that holds the global inference profile the worker calls (ALERT_AI_BEDROCK_REGION). Check that the account's service control policy allows Bedrock there."
  type        = string
  default     = "us-east-1"
}

variable "agentcore_gateway_arn" {
  description = "ARN of the Tokyo AgentCore Gateway the worker searches through. Created after the live spike; null until then, and the worker gets no AgentCore permission."
  type        = string
  default     = null
}
