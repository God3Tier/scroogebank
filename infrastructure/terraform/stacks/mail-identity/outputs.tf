output "mail_domain" {
  description = "The sending domain. stacks/agent-engagement looks the identity up by this name."
  value       = local.mail_domain
}

output "from_address" {
  description = "The no-reply address every message is sent from (ADR-0053 decision 3)."
  value       = local.from_address
}

output "identity_arn" {
  description = "The domain identity, which the engagement task role may send as."
  value       = aws_sesv2_email_identity.domain.arn
}

output "configuration_set_name" {
  description = "The configuration set every message names."
  value       = aws_sesv2_configuration_set.this.configuration_set_name
}

output "dkim_status" {
  description = "SUCCESS once SES has found the DKIM records; sending fails until then."
  value       = aws_sesv2_email_identity.domain.dkim_signing_attributes[0].status
}

output "verified_recipients" {
  description = "The sandbox recipients. Each must click its verification email once."
  value       = sort(keys(aws_sesv2_email_identity.recipient))
}
