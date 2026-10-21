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
    condition     = aws_sesv2_email_identity_mail_from_attributes.domain[0].mail_from_domain == "bounce.mail.sg.itsag3t6.com"
    error_message = "The MAIL FROM domain is bounce.<sending domain>."
  }
  assert {
    condition     = aws_route53_record.dmarc[0].name == "_dmarc.mail.sg.itsag3t6.com" && aws_route53_record.dmarc[0].records == toset(["v=DMARC1; p=none;"])
    error_message = "DMARC is published at p=none for the sending domain."
  }
  assert {
    condition     = aws_route53_record.mail_from_mx[0].records == toset(["10 feedback-smtp.ap-southeast-1.amazonses.com"])
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
  assert {
    condition     = length(aws_sesv2_email_identity.sender) == 0 && length(aws_route53_record.dkim) == 3
    error_message = "With a domain: the domain identity and its DKIM records, no sender address identity."
  }
}

# An account without the domain's hosted zone (ADR-0055).
run "sender_address_without_a_domain" {
  command = plan

  variables {
    domain_name         = null
    sender_address      = "synthetic.sender@example.test"
    verified_recipients = ["synthetic.sender@example.test", "synthetic.client@example.test"]
  }

  assert {
    condition     = output.from_address == "synthetic.sender@example.test" && output.mail_domain == null
    error_message = "Without a domain, mail comes from sender_address."
  }
  assert {
    condition     = aws_sesv2_email_identity.sender[0].email_identity == "synthetic.sender@example.test" && aws_sesv2_email_identity.sender[0].configuration_set_name == "crm-sg-prod-engagement"
    error_message = "The sender address is an identity that uses the configuration set."
  }
  assert {
    condition = (
      length(data.aws_route53_zone.this) == 0 &&
      length(aws_sesv2_email_identity.domain) == 0 &&
      length(aws_route53_record.dkim) == 0 &&
      length(aws_route53_record.mail_from_mx) == 0 &&
      length(aws_route53_record.dmarc) == 0
    )
    error_message = "Without a domain: no hosted zone lookup, domain identity or DNS records."
  }
  assert {
    condition     = keys(aws_sesv2_email_identity.recipient) == ["synthetic.client@example.test"]
    error_message = "The sender address is not made a second time as a recipient."
  }
}

run "needs_a_domain_or_a_sender_address" {
  command = plan

  variables {
    domain_name = null
  }

  expect_failures = [var.sender_address]
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
