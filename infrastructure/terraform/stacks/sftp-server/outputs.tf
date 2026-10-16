output "sftp_server_address" {
  description = "The CRM stack's sftp_server_address."
  value       = "${aws_eip.sftp.public_ip}:22"
}

output "sftp_host_key_fingerprint" {
  description = "The CRM stack's sftp_host_key_fingerprint."
  value       = tls_private_key.host.public_key_fingerprint_sha256
}

output "producer_repository_url" {
  description = "Push the producer image here (services/sftp/producer)."
  value       = aws_ecr_repository.producer.repository_url
}

output "instance_id" {
  description = "Stop the instance between sessions, or open a shell with Session Manager."
  value       = aws_instance.sftp.id
}
