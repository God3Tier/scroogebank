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
  description = "Let the worker search public news through Tavily (ALERT_INGESTION_ENABLED) and turn its schedule on. Keep false until the API key is populated and the live benchmark has passed (ADR-0050)."
  type        = bool
  default     = false
}

variable "ai_enabled" {
  description = "Let the worker call Bedrock (ALERT_AI_ENABLED) and give its task role bedrock:InvokeModel on the pinned profile. Keep false until the AI benchmark gates pass (ADR-0010)."
  type        = bool
  default     = false
}

variable "schedule" {
  description = "EventBridge Scheduler expression for worker runs, in Singapore time. The default is every four hours at fixed times, 00:00, 04:00, 08:00, 12:00, 16:00 and 20:00 (ADR-0031 decision 1, ADR-0081), so the Event Alerts page can show the next one. If you change it, change ALERT_SCHEDULE_INTERVAL on the alerts service to match."
  type        = string
  default     = "cron(0 0/4 * * ? *)"
}

variable "run_on_apply" {
  description = "Start one worker run at the end of every apply, so alerts do not wait for the schedule's first run (ADR-0080). It spends a run's Tavily credits each time and needs the AWS CLI where terraform runs. Only has an effect while ingestion_enabled is true and the CRM stack is running."
  type        = bool
  default     = true
}

variable "minimum_search_client_share" {
  description = "Inclusive distinct-client share required by the common-holding search rule."
  type        = number
  default     = 0.20
  validation {
    condition     = var.minimum_search_client_share > 0 && var.minimum_search_client_share <= 1
    error_message = "minimum_search_client_share must be greater than zero and at most one."
  }
}

variable "minimum_account_concentration" {
  description = "Inclusive per-account SGD portfolio concentration required by the concentration search rule."
  type        = number
  default     = 0.50
  validation {
    condition     = var.minimum_account_concentration > 0 && var.minimum_account_concentration <= 1
    error_message = "minimum_account_concentration must be greater than zero and at most one."
  }
}

variable "approved_news_domains" {
  description = "Requester-authorized commercial news domains added to every issuer query; summaries retain source citations. See ADR-0051's 2026-10-30 addendum."
  type        = list(string)
  default     = ["reuters.com", "cnbc.com", "ft.com"]

  validation {
    condition = alltrue([
      for domain in var.approved_news_domains : contains(["reuters.com", "cnbc.com", "ft.com"], domain)
    ])
    error_message = "approved_news_domains may contain only the reviewed candidate domains: reuters.com, cnbc.com, and ft.com."
  }
}

variable "bedrock_region" {
  description = "Singapore source Region for the global Nova 2 Lite inference profile (ALERT_AI_BEDROCK_REGION). Global inference may process the request in another supported AWS Region."
  type        = string
  default     = "ap-southeast-1"
}

variable "cloudwatch_metrics_enabled" {
  description = "Let the worker export its metrics to CloudWatch as it exits (ALERT_CLOUDWATCH_METRICS_ENABLED), and give its task role cloudwatch:PutMetricData for that namespace only. Each distinct metric and tag combination is a billed custom metric, so it defaults to off (ADR-0049)."
  type        = bool
  default     = false
}

variable "cloudwatch_namespace" {
  description = "CloudWatch namespace the worker's metrics go to."
  type        = string
  default     = "CRM/EventAlerts"
}
