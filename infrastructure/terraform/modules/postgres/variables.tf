variable "identifier" {
  description = "RDS instance identifier, for example crm-sg-prod-users-db."
  type        = string
}

variable "db_name" {
  description = "Name of the database RDS creates in the instance."
  type        = string
}

variable "engine_version" {
  description = "PostgreSQL major version."
  type        = string
  default     = "17"
}

variable "instance_class" {
  description = "Instance class."
  type        = string
  default     = "db.t4g.micro"
}

variable "allocated_storage" {
  description = "Storage in GiB."
  type        = number
  default     = 20
}

variable "max_allocated_storage" {
  description = "Upper limit for storage auto scaling in GiB; 0 turns auto scaling off."
  type        = number
  default     = 100
}

variable "multi_az" {
  description = "Run a synchronous standby in the second AZ."
  type        = bool
  default     = false
}

variable "backup_retention_days" {
  description = "Days of automated backups, which is also the point-in-time recovery window. 0 turns backups off."
  type        = number
  default     = 7
}

variable "deletion_protection" {
  description = "Refuse deletion until this is turned off."
  type        = bool
  default     = true
}

variable "skip_final_snapshot" {
  description = "Delete without taking a final snapshot."
  type        = bool
  default     = false
}

variable "kms_key_arn" {
  description = "KMS key for storage encryption."
  type        = string
}

variable "subnet_group_name" {
  description = "DB subnet group in the isolated subnets."
  type        = string
}

variable "security_group_id" {
  description = "The database's own security group."
  type        = string
}

variable "parameter_group_name" {
  description = "Parameter group that sets rds.force_ssl."
  type        = string
}

variable "backup_tag" {
  description = "Tag the instance Backup=daily so the AWS Backup plan selects it."
  type        = bool
  default     = false
}

variable "tags" {
  description = "Extra tags."
  type        = map(string)
  default     = {}
}
