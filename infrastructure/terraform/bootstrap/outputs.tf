output "state_bucket" {
  description = "Put this in each stack's env/*.s3.tfbackend."
  value       = aws_s3_bucket.state.bucket
}

output "github_oidc_provider_arn" {
  description = "The account's GitHub Actions OIDC provider."
  value       = var.enable_github_oidc ? aws_iam_openid_connect_provider.github[0].arn : null
}

output "cloudtrail_bucket" {
  description = "Where CloudTrail writes."
  value       = aws_s3_bucket.trail.bucket
}
