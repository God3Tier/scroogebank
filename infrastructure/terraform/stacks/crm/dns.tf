# Custom domain (ADR-0019 decision 5, ADR-0042 decision 8). The domain is
# registered by hand in Route 53, which creates its public hosted zone; both
# stay outside Terraform, so destroying the stack never removes them (runbook
# step 1). Everything here exists only when domain_name is set.

data "aws_route53_zone" "this" {
  count        = local.custom_domain ? 1 : 0
  name         = var.domain_name
  private_zone = false
}

# ------------------------------------------------------------- certificates

# The internal ALB's. API Gateway checks this name on the VPC Link
# connection, and the ingest Lambda calls the ALB by it.
resource "aws_acm_certificate" "alb" {
  count             = local.custom_domain ? 1 : 0
  domain_name       = local.alb_host
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

# CloudFront's. CloudFront takes certificates only from us-east-1.
resource "aws_acm_certificate" "app" {
  count             = local.custom_domain ? 1 : 0
  provider          = aws.us_east_1
  domain_name       = local.app_host
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

locals {
  # Static keys, so for_each is known at plan time. Each certificate names one
  # host, so it has one validation record.
  certificate_validation = local.custom_domain ? {
    alb = one(aws_acm_certificate.alb[0].domain_validation_options)
    app = one(aws_acm_certificate.app[0].domain_validation_options)
  } : {}
}

resource "aws_route53_record" "certificate_validation" {
  for_each        = local.certificate_validation
  zone_id         = data.aws_route53_zone.this[0].zone_id
  name            = each.value.resource_record_name
  type            = each.value.resource_record_type
  records         = [each.value.resource_record_value]
  ttl             = 300
  allow_overwrite = true # a record left by an earlier stack for the same name
}

resource "aws_acm_certificate_validation" "alb" {
  count                   = local.custom_domain ? 1 : 0
  certificate_arn         = aws_acm_certificate.alb[0].arn
  validation_record_fqdns = [aws_route53_record.certificate_validation["alb"].fqdn]
}

resource "aws_acm_certificate_validation" "app" {
  count                   = local.custom_domain ? 1 : 0
  provider                = aws.us_east_1
  certificate_arn         = aws_acm_certificate.app[0].arn
  validation_record_fqdns = [aws_route53_record.certificate_validation["app"].fqdn]
}

# -------------------------------------------------------------------- hosts

resource "aws_route53_record" "app" {
  count   = local.custom_domain ? 1 : 0
  zone_id = data.aws_route53_zone.this[0].zone_id
  name    = local.app_host
  type    = "A" # the distribution has IPv6 off, so no AAAA record

  alias {
    name                   = aws_cloudfront_distribution.this.domain_name
    zone_id                = aws_cloudfront_distribution.this.hosted_zone_id
    evaluate_target_health = false
  }
}

# A public name for a private ALB: it resolves to the ALB's addresses in the
# app subnets, which nothing outside the VPC can reach. A private hosted zone
# would cost another USD 0.50 a month and protect nothing more.
resource "aws_route53_record" "alb" {
  count   = local.custom_domain ? 1 : 0
  zone_id = data.aws_route53_zone.this[0].zone_id
  name    = local.alb_host
  type    = "A"

  alias {
    name                   = aws_lb.internal.dns_name
    zone_id                = aws_lb.internal.zone_id
    evaluate_target_health = false
  }
}
