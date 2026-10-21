# Plans the stack against a mocked AWS provider (no AWS account needed) and
# checks the names ADR-0053 fixes. Run with:
#   terraform init -backend=false && terraform test

mock_provider "aws" {
  mock_data "aws_route53_zone" {
    defaults = { zone_id = "Z0EXAMPLE", name = "itsag3t6.com" }
  }
  mock_resource "aws_sesv2_email_identity" {
    defaults = {
      arn = "arn:aws:ses:ap-southeast-1:123456789012:identity/mail.sg.itsag3t6.com"
      dkim_signing_attributes = [{
        tokens                        = ["token1", "token2", "token3"]
        status                        = "PENDING"
        next_signing_key_length       = "RSA_2048_BIT"
        current_signing_key_length    = "RSA_2048_BIT"
        domain_signing_private_key    = null
        domain_signing_selector       = null
        last_key_generation_timestamp = "2026-10-21T00:00:00Z"
        signing_attributes_origin     = "AWS_SES"
      }]
    }
  }
}

variables {
  environment         = "prod"
  domain_name         = "itsag3t6.com"
  verified_recipients = ["synthetic.agent@example.test", "synthetic.client@example.test"]
}

run "prod" {
  command = plan

  assert {
    condition     = output.mail_domain == "mail.sg.itsag3t6.com" && output.from_address == "no-reply@mail.sg.itsag3t6.com"
    error_message = "Prod sends from no-reply@mail.sg.itsag3t6.com (ADR-0053 decision 1)."
  }
  assert {
    condition     = aws_sesv2_email_identity_mail_from_attributes.domain.mail_from_domain == "bounce.mail.sg.itsag3t6.com"
    error_message = "The MAIL FROM domain is bounce.<sending domain>."
  }
  assert {
    condition     = aws_route53_record.dmarc.name == "_dmarc.mail.sg.itsag3t6.com" && aws_route53_record.dmarc.records == toset(["v=DMARC1; p=none;"])
    error_message = "DMARC is published at p=none for the sending domain."
  }
  assert {
    condition     = aws_route53_record.mail_from_mx.records == toset(["10 feedback-smtp.ap-southeast-1.amazonses.com"])
    error_message = "Bounces return to SES in the sending region."
  }
  assert {
    condition     = aws_sesv2_configuration_set.this.configuration_set_name == "crm-sg-prod-engagement" && output.configuration_set_name == "crm-sg-prod-engagement"
    error_message = "The configuration set has the name stacks/agent-engagement looks up."
  }
  assert {
    condition     = length(aws_sesv2_email_identity.recipient) == 2
    error_message = "Each sandbox recipient gets its own identity."
  }
}

run "staging" {
  command = plan

  variables {
    environment         = "staging"
    verified_recipients = []
  }

  assert {
    condition     = output.mail_domain == "mail.sg-staging.itsag3t6.com"
    error_message = "Staging sends from mail.<legal_entity>-<environment>.<domain>."
  }
  assert {
    condition     = length(aws_sesv2_email_identity.recipient) == 0
    error_message = "No recipients, no recipient identities."
  }
}

run "rejects_a_recipient_that_is_not_an_address" {
  command = plan

  variables {
    verified_recipients = ["not-an-address"]
  }

  expect_failures = [var.verified_recipients]
}
