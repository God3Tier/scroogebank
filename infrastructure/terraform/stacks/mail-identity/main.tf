# The SES sending identity for agent engagement (ADR-0053 decision 2): the
# domain identity with Easy DKIM, its MAIL FROM domain and DMARC record in
# the existing hosted zone, the configuration set, and the team inboxes used
# as demo recipients. This stack is long-lived: apply it once per environment
# and never destroy it between sessions, because a new identity needs DKIM
# verification again and every inbox must click its verification email
# again. stacks/agent-engagement reads the identity and the configuration set
# by name.

locals {
  name    = "crm-${var.legal_entity}-${var.environment}"
  is_prod = var.environment == "prod"

  # The CRM stack's app host (stacks/crm/locals.tf), so prod sends from
  # mail.sg.itsag3t6.com and staging from mail.sg-staging.itsag3t6.com.
  app_host         = "${var.legal_entity}${local.is_prod ? "" : "-${var.environment}"}.${var.domain_name}"
  mail_domain      = "mail.${local.app_host}"
  mail_from_domain = "bounce.${local.mail_domain}"
  from_address     = "no-reply@${local.mail_domain}"

  configuration_set_name = "${local.name}-engagement"
}

# The hosted zone is made by hand when the domain is registered (runbook,
# "Register a domain"), as the CRM stack expects.
data "aws_route53_zone" "this" {
  name         = var.domain_name
  private_zone = false
}

# --------------------------------------------------------- configuration set
# Every message names this set. It publishes sends, deliveries, bounces,
# complaints and rejects to CloudWatch; stacks/agent-engagement alarms on them.

resource "aws_sesv2_configuration_set" "this" {
  configuration_set_name = local.configuration_set_name

  delivery_options {
    tls_policy = "REQUIRE"
  }

  reputation_options {
    reputation_metrics_enabled = true
  }

  sending_options {
    sending_enabled = true
  }
}

resource "aws_sesv2_configuration_set_event_destination" "cloudwatch" {
  configuration_set_name = aws_sesv2_configuration_set.this.configuration_set_name
  event_destination_name = "cloudwatch"

  event_destination {
    enabled              = true
    matching_event_types = ["SEND", "DELIVERY", "BOUNCE", "COMPLAINT", "REJECT"]

    cloud_watch_destination {
      dimension_configuration {
        dimension_name          = "ses:configuration-set"
        dimension_value_source  = "MESSAGE_TAG"
        default_dimension_value = local.configuration_set_name
      }
    }
  }
}

# ------------------------------------------------------------ domain identity

resource "aws_sesv2_email_identity" "domain" {
  email_identity         = local.mail_domain
  configuration_set_name = aws_sesv2_configuration_set.this.configuration_set_name

  dkim_signing_attributes {
    next_signing_key_length = "RSA_2048_BIT"
  }
}

# Easy DKIM: three CNAMEs SES checks before the domain can send.
resource "aws_route53_record" "dkim" {
  count   = 3
  zone_id = data.aws_route53_zone.this.zone_id
  name    = "${aws_sesv2_email_identity.domain.dkim_signing_attributes[0].tokens[count.index]}._domainkey.${local.mail_domain}"
  type    = "CNAME"
  ttl     = 600
  records = ["${aws_sesv2_email_identity.domain.dkim_signing_attributes[0].tokens[count.index]}.dkim.amazonses.com"]
}

# Bounces go to bounce.<sending domain>, so SPF aligns with the sender.
resource "aws_sesv2_email_identity_mail_from_attributes" "domain" {
  email_identity         = aws_sesv2_email_identity.domain.email_identity
  mail_from_domain       = local.mail_from_domain
  behavior_on_mx_failure = "USE_DEFAULT_VALUE"
}

resource "aws_route53_record" "mail_from_mx" {
  zone_id = data.aws_route53_zone.this.zone_id
  name    = local.mail_from_domain
  type    = "MX"
  ttl     = 600
  records = ["10 feedback-smtp.${var.aws_region}.amazonses.com"]
}

resource "aws_route53_record" "mail_from_spf" {
  zone_id = data.aws_route53_zone.this.zone_id
  name    = local.mail_from_domain
  type    = "TXT"
  ttl     = 600
  records = ["v=spf1 include:amazonses.com ~all"]
}

# Monitoring only (p=none): receivers report, nothing is rejected.
resource "aws_route53_record" "dmarc" {
  zone_id = data.aws_route53_zone.this.zone_id
  name    = "_dmarc.${local.mail_domain}"
  type    = "TXT"
  ttl     = 600
  records = ["v=DMARC1; p=none;"]
}

# ------------------------------------------------- sandbox demo recipients
# In the SES sandbox only verified addresses receive mail, so demo clients
# and agents use these team inboxes (ADR-0053 decision 8).

resource "aws_sesv2_email_identity" "recipient" {
  for_each       = toset(var.verified_recipients)
  email_identity = each.value
}
