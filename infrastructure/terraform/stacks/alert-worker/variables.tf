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

variable "report_summary_enabled" {
  description = "Let the worker write one summary sentence for each new report, for the news email (ALERT_REPORT_SUMMARY_ENABLED, ADR-0085). Approved by Azeez, so on by default; it needs ai_enabled and is skipped, without affecting the report, when the AI is off or the sentence fails its checks."
  type        = bool
  default     = true
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
  description = "Inclusive share of the clients holding anything that must hold a company, with at least minimum_search_client_count of them, before the scheduled runs search it. A large share of one account no longer qualifies a company."
  type        = number
  default     = 0.20
  validation {
    condition     = var.minimum_search_client_share > 0 && var.minimum_search_client_share <= 1
    error_message = "minimum_search_client_share must be greater than zero and at most one."
  }
}

variable "approved_news_domains" {
  description = "Requester-approved news sites. Tavily is restricted to them, each company's official sites and the websites agents add (include_domains). Plain domains only, none of the platforms anyone can post on. See ADR-0051 and its 2026-11-07 addendum."
  type        = list(string)
  default     = ["reuters.com", "cnbc.com", "ft.com", "bloomberg.com", "wsj.com", "barrons.com", "marketwatch.com", "finance.yahoo.com", "investing.com", "benzinga.com", "fool.com", "forbes.com", "businesstimes.com.sg", "straitstimes.com", "channelnewsasia.com", "theedgesingapore.com", "apnews.com", "bbc.com", "theguardian.com", "techcrunch.com", "theverge.com", "thenextweb.com", "techspot.com", "adage.com"]

  validation {
    condition = alltrue([
      for domain in var.approved_news_domains :
      can(regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?[.])+[a-z]{2,63}$", domain))
      && !contains(["reddit.com", "x.com", "twitter.com", "facebook.com", "instagram.com", "tiktok.com", "youtube.com", "youtu.be",
        "medium.com", "substack.com", "quora.com", "linkedin.com", "t.me", "telegram.org", "pinterest.com", "tumblr.com",
      "blogspot.com", "wordpress.com", "stocktwits.com", "discord.com", "whatsapp.com"], domain)
    ])
    error_message = "approved_news_domains may hold only plain domain names such as example.com, and none of the open posting platforms (reddit.com, x.com and the like)."
  }
}

variable "bedrock_region" {
  description = "Singapore source Region for the Asia-Pacific Nova Pro inference profile (ALERT_AI_BEDROCK_REGION)."
  type        = string
  default     = "ap-southeast-1"
}

variable "bedrock_profile_regions" {
  description = "Where the apac. inference profile may run the request. Re-check with aws bedrock get-inference-profile before applying."
  type        = list(string)
  default     = ["ap-southeast-1", "ap-southeast-2", "ap-northeast-1", "ap-northeast-2", "ap-northeast-3", "ap-south-1"]
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
