output "address" {
  description = "Endpoint hostname. It stays the same after a Multi-AZ failover."
  value       = aws_db_instance.this.address
}

output "port" {
  description = "Endpoint port."
  value       = aws_db_instance.this.port
}

output "db_name" {
  description = "Database name."
  value       = aws_db_instance.this.db_name
}

output "identifier" {
  description = "RDS instance identifier, used to stop and start it."
  value       = aws_db_instance.this.identifier
}

output "arn" {
  description = "Instance ARN."
  value       = aws_db_instance.this.arn
}

output "backup_retention_days" {
  description = "Automated backup retention, which is the point-in-time recovery window."
  value       = aws_db_instance.this.backup_retention_period
}

output "multi_az" {
  description = "Whether a Multi-AZ standby runs."
  value       = aws_db_instance.this.multi_az
}

output "instance_class" {
  description = "Instance class."
  value       = aws_db_instance.this.instance_class
}

output "max_allocated_storage" {
  description = "Storage auto scaling limit; 0 means off."
  value       = aws_db_instance.this.max_allocated_storage
}

output "master_secret_arn" {
  description = "Secrets Manager secret holding the master username and password (JSON keys username and password)."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}
