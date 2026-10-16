variable "aws_region" {
  description = "Region of the server. The same region as the CRM keeps the demo simple."
  type        = string
  default     = "ap-southeast-1"
}

variable "environment" {
  description = "staging or prod, matching the CRM stack it serves."
  type        = string

  validation {
    condition     = contains(["staging", "prod"], var.environment)
    error_message = "environment must be staging or prod."
  }
}

variable "instance_type" {
  description = "An arm64 (Graviton) instance type; the AMI is arm64."
  type        = string
  default     = "t4g.micro"
}

variable "allowed_cidrs" {
  description = "The CRM stack's nat_public_ips output, as /32 CIDRs."
  type        = list(string)

  validation {
    condition     = alltrue([for c in var.allowed_cidrs : can(cidrnetmask(c)) && c != "0.0.0.0/0"])
    error_message = "List the CRM's NAT gateway addresses as CIDRs; never 0.0.0.0/0."
  }
}

variable "crm_ingest_public_key" {
  description = "The CRM stack's sftp_client_public_key output."
  type        = string
}

variable "host_key_file" {
  description = "A host key you were given: an unencrypted OpenSSH private key, its path relative to stacks/sftp-server. null generates an ED25519 key."
  type        = string
  default     = null

  validation {
    condition     = var.host_key_file == null || fileexists(coalesce(var.host_key_file, "-"))
    error_message = "host_key_file names no file. The path is relative to stacks/sftp-server, for example \"../../../../secrets/sftp_host_key\"."
  }
}

variable "sftp_user" {
  description = "The account the CRM signs in as."
  type        = string
  default     = "reportuser"
}

variable "producer_image" {
  description = "Producer image URI in this stack's ECR repository; null runs no producer."
  type        = string
  default     = null
}

variable "publish_interval_seconds" {
  description = "How often the producer writes a report file."
  type        = number
  default     = 300
}

variable "crm_account_ids" {
  description = "Synthetic CRM account IDs the producer's transactions refer to."
  type        = list(string)
  default     = []
}
