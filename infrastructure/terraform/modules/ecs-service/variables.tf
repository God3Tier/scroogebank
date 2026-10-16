variable "name" {
  description = "Service name; also the container name, for example account-service."
  type        = string
}

variable "name_prefix" {
  description = "Stack prefix, for example crm-sg-prod."
  type        = string
}

variable "create_service" {
  description = "Create an ECS service. False gives only the task definition, for a task EventBridge Scheduler starts."
  type        = bool
  default     = true
}

variable "cluster_arn" {
  description = "ECS cluster ARN."
  type        = string
}

variable "cluster_name" {
  description = "ECS cluster name, for auto scaling."
  type        = string
}

variable "subnet_ids" {
  description = "Private app subnets in both AZs."
  type        = list(string)
}

variable "security_group_ids" {
  description = "Security groups for the tasks."
  type        = list(string)
}

variable "image" {
  description = "Container image URI with tag."
  type        = string
}

variable "entry_point" {
  description = "Override the image's entry point, for example [\"/chain-verify\"]."
  type        = list(string)
  default     = null
}

variable "cpu" {
  description = "Task CPU units (256 = 0.25 vCPU)."
  type        = number
  default     = 256
}

variable "memory" {
  description = "Task memory in MiB."
  type        = number
  default     = 512
}

variable "cpu_architecture" {
  description = "X86_64, or ARM64 (Graviton, about 20% cheaper) when the images are built for arm64."
  type        = string
  default     = "X86_64"
}

variable "port" {
  description = "The container port the service listens on; null for a task that serves nothing."
  type        = number
  default     = null
}

variable "port_name" {
  description = "Name of the main port mapping, used by Service Connect."
  type        = string
  default     = "http"
}

variable "app_protocol" {
  description = "Service Connect protocol for the main port: http, http2 or grpc for plain-text traffic, or null for TCP (the service serves TLS itself)."
  type        = string
  default     = null
}

variable "extra_ports" {
  description = "Further port mappings, such as the auditor's gRPC port."
  type = list(object({
    name         = string
    port         = number
    app_protocol = optional(string)
  }))
  default = []
}

variable "environment" {
  description = "Plain environment variables. Never put secrets here."
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "Environment variables read from Secrets Manager: name => valueFrom (secret ARN, optionally with :json-key::)."
  type        = map(string)
  default     = {}
}

variable "secret_arns" {
  description = "Secret ARNs (without JSON keys) the execution role may read."
  type        = list(string)
  default     = []
}

variable "task_role_policies" {
  description = "IAM policies for what the application calls: name => policy JSON. The names must be known at plan time."
  type        = map(string)
  default     = {}
}

variable "tls" {
  description = "Prepare TLS files in /tls before the app starts. mode is go or java. When serves_tls is true, cert_secret_arn holds the service's own certificate (JSON keys cert and key)."
  type = object({
    mode            = string
    ca_pem          = string
    serves_tls      = optional(bool, false)
    cert_secret_arn = optional(string)
  })
  default = null

  validation {
    condition     = var.tls == null || contains(["go", "java"], try(var.tls.mode, ""))
    error_message = "tls.mode must be go or java."
  }
}

variable "service_connect" {
  description = "Join Service Connect. aliases are the names other services call this one by; an empty list makes it a client only."
  type = object({
    namespace_arn = string
    aliases = list(object({
      port_name = string
      dns_name  = string
      port      = number
    }))
  })
  default = null
}

variable "load_balancer" {
  description = "Put the service behind the internal ALB with these path rules."
  type = object({
    listener_arn      = string
    alb_arn_suffix    = string
    vpc_id            = string
    protocol          = string
    health_check_path = string
    rules = list(object({
      priority = number
      paths    = list(string)
    }))
  })
  default = null
}

variable "min_tasks" {
  description = "Minimum tasks; 0 while the stack is stopped."
  type        = number
  default     = 2
}

variable "max_tasks" {
  description = "Maximum tasks."
  type        = number
  default     = 6
}

variable "use_spot" {
  description = "Run on Fargate Spot."
  type        = bool
  default     = false
}

variable "scaling_queue_name" {
  description = "Also scale on this SQS queue's backlog."
  type        = string
  default     = null
}

variable "log_retention_days" {
  description = "CloudWatch Logs retention."
  type        = number
  default     = 30
}

variable "tags" {
  description = "Extra tags."
  type        = map(string)
  default     = {}
}
