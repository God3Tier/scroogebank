# Edge (ADR-0019 decision 1): CloudFront serves the SPA from a private bucket
# through Origin Access Control and forwards /api/* to API Gateway, so the
# frontend and the API share one HTTPS origin. WAF sits on CloudFront in prod.

resource "aws_s3_bucket" "frontend" {
  bucket        = "${local.name}-frontend-${local.account_id}"
  force_destroy = true # only built files; CI uploads them again
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket                  = aws_s3_bucket.frontend.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_versioning" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  rule {
    id     = "expire-old-builds"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }
}

resource "aws_cloudfront_origin_access_control" "frontend" {
  name                              = "${local.name}-frontend"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

data "aws_iam_policy_document" "frontend" {
  statement {
    sid    = "TlsOnly"
    effect = "Deny"
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.frontend.arn, "${aws_s3_bucket.frontend.arn}/*"]
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  statement {
    sid = "CloudFrontOnly"
    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.frontend.arn}/*"]
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.this.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "frontend" {
  bucket     = aws_s3_bucket.frontend.id
  policy     = data.aws_iam_policy_document.frontend.json
  depends_on = [aws_s3_bucket_public_access_block.frontend]
}

# Single-page-app routing: any path without a file extension gets index.html.
# Done in a function rather than with custom error responses, which would also
# turn the API's own 403 and 404 answers into index.html.
resource "aws_cloudfront_function" "spa" {
  name    = "${local.name}-spa"
  runtime = "cloudfront-js-2.0"
  comment = "Serve index.html for client-side routes"
  publish = true
  code    = <<-EOT
    function handler(event) {
      var request = event.request;
      if (!request.uri.includes('.')) {
        request.uri = '/index.html';
      }
      return request;
    }
  EOT
}

data "aws_cloudfront_cache_policy" "optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "disabled" {
  name = "Managed-CachingDisabled"
}

# Forwards every viewer header except Host (including Authorization) to
# API Gateway.
data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

# Security headers (ADR-0069). Both policies keep what AWS's
# Managed-SecurityHeadersPolicy set, add a Content-Security-Policy,
# Permissions-Policy and cross-origin policies, and drop S3's Server header.
locals {
  cognito_hosts = "https://cognito-idp.${local.region}.amazonaws.com https://${aws_cognito_user_pool_domain.this.domain}.auth.${local.region}.amazoncognito.com"

  # The app loads only its own scripts, styles and fonts (fonts are bundled,
  # not from Google Fonts). It calls Cognito for sign-in and token refresh, and
  # previews identity documents as blob: images and frames (ADR-0041).
  frontend_csp = join("; ", [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self'",
    "font-src 'self'",
    "img-src 'self' data: blob:",
    "connect-src 'self' ${local.cognito_hosts}",
    "frame-src 'self' blob: ${local.cognito_hosts}",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'self'",
    "upgrade-insecure-requests",
  ])

  # The API returns data, never pages, so it may load and frame nothing.
  api_csp = "default-src 'none'; frame-ancestors 'none'"

  permissions_policy = "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()"
}

resource "aws_cloudfront_response_headers_policy" "frontend" {
  name = "${local.name}-frontend-security"

  security_headers_config {
    content_security_policy {
      content_security_policy = local.frontend_csp
      override                = true
    }
    content_type_options {
      override = true
    }
    frame_options {
      frame_option = "SAMEORIGIN"
      override     = false
    }
    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = false
    }
    strict_transport_security {
      access_control_max_age_sec = 31536000
      override                   = false
    }
    xss_protection {
      protection = true
      mode_block = true
      override   = false
    }
  }

  # No Cross-Origin-Embedder-Policy: the app uses nothing that needs
  # cross-origin isolation, and require-corp can block the PDF preview.
  custom_headers_config {
    items {
      header   = "Permissions-Policy"
      value    = local.permissions_policy
      override = true
    }
    items {
      header   = "Cross-Origin-Opener-Policy"
      value    = "same-origin"
      override = true
    }
    items {
      header   = "Cross-Origin-Resource-Policy"
      value    = "same-origin"
      override = true
    }
  }

  remove_headers_config {
    items {
      header = "Server"
    }
  }
}

resource "aws_cloudfront_response_headers_policy" "api" {
  name = "${local.name}-api-security"

  security_headers_config {
    content_security_policy {
      content_security_policy = local.api_csp
      override                = true
    }
    content_type_options {
      override = true
    }
    frame_options {
      frame_option = "DENY"
      override     = true
    }
    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = false
    }
    strict_transport_security {
      access_control_max_age_sec = 31536000
      override                   = false
    }
  }

  custom_headers_config {
    items {
      header   = "Permissions-Policy"
      value    = local.permissions_policy
      override = true
    }
    items {
      header   = "Cross-Origin-Resource-Policy"
      value    = "same-origin"
      override = true
    }
  }

  remove_headers_config {
    items {
      header = "Server"
    }
  }
}

resource "aws_cloudfront_distribution" "this" {
  enabled             = true
  comment             = local.name
  default_root_object = "index.html"
  price_class         = "PriceClass_200" # includes Asia
  http_version        = "http2and3"
  web_acl_id          = local.enable_waf ? aws_wafv2_web_acl.this[0].arn : null
  aliases             = local.custom_domain ? [local.app_host] : []

  origin {
    origin_id                = "frontend"
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
  }

  origin {
    origin_id   = "api"
    domain_name = replace(aws_apigatewayv2_api.this.api_endpoint, "https://", "")
    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  default_cache_behavior {
    target_origin_id           = "frontend"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD", "OPTIONS"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = data.aws_cloudfront_cache_policy.optimized.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.frontend.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa.arn
    }
  }

  ordered_cache_behavior {
    path_pattern               = "/api/*"
    target_origin_id           = "api"
    viewer_protocol_policy     = "https-only"
    allowed_methods            = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = data.aws_cloudfront_cache_policy.disabled.id
    origin_request_policy_id   = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.api.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  # app_host's certificate from us-east-1 (dns.tf). Without domain_name, the
  # default *.cloudfront.net name and certificate.
  viewer_certificate {
    cloudfront_default_certificate = !local.custom_domain
    acm_certificate_arn            = local.custom_domain ? aws_acm_certificate_validation.app[0].certificate_arn : null
    ssl_support_method             = local.custom_domain ? "sni-only" : null
    minimum_protocol_version       = local.custom_domain ? "TLSv1.2_2021" : null
  }
}

# ---------------------------------------------------------------------- WAF

resource "aws_wafv2_web_acl" "this" {
  count    = local.enable_waf ? 1 : 0
  provider = aws.us_east_1
  name     = local.name
  scope    = "CLOUDFRONT"

  default_action {
    allow {}
  }

  rule {
    name     = "common"
    priority = 10
    override_action {
      none {}
    }
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesCommonRuleSet"
        # Identity documents are up to 5 MB (ADR-0041), above this rule's
        # 8 KB body limit. Count it here; the next rule blocks it on every
        # other path.
        rule_action_override {
          name = "SizeRestrictions_BODY"
          action_to_use {
            count {}
          }
        }
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-common"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "large-body-except-documents"
    priority = 20
    action {
      block {}
    }
    statement {
      and_statement {
        statement {
          label_match_statement {
            scope = "LABEL"
            key   = "awswaf:managed:aws:core-rule-set:SizeRestrictions_Body"
          }
        }
        statement {
          not_statement {
            statement {
              regex_match_statement {
                regex_string = "^/api/clients/[^/]+/documents$"
                field_to_match {
                  uri_path {}
                }
                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }
          }
        }
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-large-body"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "known-bad-inputs"
    priority = 30
    override_action {
      none {}
    }
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-bad-inputs"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "ip-reputation"
    priority = 40
    override_action {
      none {}
    }
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesAmazonIpReputationList"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-ip-reputation"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = local.name
    sampled_requests_enabled   = true
  }
}

# Disabled: the account's service control policy denies logs:CreateLogGroup
# in us-east-1, so WAF request logging is commented out until the SCP allows
# it. The web ACL itself is unaffected.
#
# # CloudFront-scoped WAF resources, including their log group, live in
# # us-east-1. Headers that can carry credentials are redacted before logging.
# resource "aws_cloudwatch_log_group" "waf" {
#   count             = local.enable_waf ? 1 : 0
#   provider          = aws.us_east_1
#   name              = "aws-waf-logs-${local.name}"
#   retention_in_days = local.log_retention_days
# }
#
# resource "aws_wafv2_web_acl_logging_configuration" "this" {
#   count                   = local.enable_waf ? 1 : 0
#   provider                = aws.us_east_1
#   resource_arn            = aws_wafv2_web_acl.this[0].arn
#   log_destination_configs = [aws_cloudwatch_log_group.waf[0].arn]
#
#   redacted_fields {
#     single_header {
#       name = "authorization"
#     }
#   }
#
#   redacted_fields {
#     single_header {
#       name = "cookie"
#     }
#   }
# }
