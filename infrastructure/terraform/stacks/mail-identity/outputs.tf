output "mail_domain" {
  description = "The sending domain. stacks/agent-engagement looks the identity up by this name. Null when mail comes from sender_address."
  value       = local.mail_domain
}

output "from_address" {
  description = "The address every message is sent from: no-reply@<sending domain> (ADR-0053 decision 3), or sender_address without a domain (ADR-0055)."
  value       = local.from_address
}

output "identity_arn" {
  description = "The sending identity, which the engagement task role may send as."
  value       = local.identity_arn
}

output "configuration_set_name" {
  description = "The configuration set every message names."
  value       = aws_sesv2_configuration_set.this.configuration_set_name
}

output "dkim_status" {
  description = "SUCCESS once SES has found the DKIM records; sending fails until then. Null without a domain, where the sender address's verification link is what counts."
  value       = local.domain_mode ? aws_sesv2_email_identity.domain[0].dkim_signing_attributes[0].status : null
}

output "verified_recipients" {
  description = "The sandbox recipients. Each must click its verification email once."
  value       = sort(keys(aws_sesv2_email_identity.recipient))
}
