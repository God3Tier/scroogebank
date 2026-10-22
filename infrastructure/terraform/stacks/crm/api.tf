# API entry (ADR-0019 decisions 2-4): API Gateway HTTP API validates the
# Cognito JWT and throttles, then forwards through a VPC Link to the internal
# ALB, which routes by path. API Gateway has no route for /internal/*, so those
# paths are reachable only from inside the VPC.

resource "aws_lb" "internal" {
  name                       = local.name
  internal                   = true
  load_balancer_type         = "application"
  security_groups            = [local.sg["alb"]]
  subnets                    = aws_subnet.app[*].id
  drop_invalid_header_fields = true
  idle_timeout               = 60
  enable_deletion_protection = local.deletion_protection
}

# HTTPS with the ACM certificate for alb_host (dns.tf). Without domain_name
# there is no certificate, and the listener is HTTP inside the VPC, reachable
# only from the VPC Link and the ingest Lambda (ADR-0042 decision 8).
resource "aws_lb_listener" "internal" {
  load_balancer_arn = aws_lb.internal.arn
  port              = local.alb_port
  protocol          = upper(local.alb_scheme)
  certificate_arn   = local.custom_domain ? aws_acm_certificate_validation.alb[0].certificate_arn : null
  ssl_policy        = local.custom_domain ? "ELBSecurityPolicy-TLS13-1-2-2021-06" : null

  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "application/json"
      message_body = "{\"error\":\"not found\"}"
      status_code  = "404"
    }
  }
}

# ---------------------------------------------------------------- API Gateway

resource "aws_apigatewayv2_api" "this" {
  name          = local.name
  protocol_type = "HTTP"
  description   = "CRM public API; JWT-checked, throttled, forwarded to the internal ALB"
}

resource "aws_apigatewayv2_vpc_link" "this" {
  name               = local.name
  subnet_ids         = aws_subnet.app[*].id
  security_group_ids = [local.sg["vpc_link"]]
}

resource "aws_apigatewayv2_authorizer" "cognito" {
  api_id           = aws_apigatewayv2_api.this.id
  name             = "cognito"
  authorizer_type  = "JWT"
  identity_sources = ["$request.header.Authorization"]

  # Cognito access tokens carry client_id rather than aud; API Gateway checks
  # either against this list.
  jwt_configuration {
    issuer   = local.cognito_issuer
    audience = [aws_cognito_user_pool_client.web.id]
  }
}

resource "aws_apigatewayv2_integration" "alb" {
  api_id                 = aws_apigatewayv2_api.this.id
  integration_type       = "HTTP_PROXY"
  integration_method     = "ANY"
  connection_type        = "VPC_LINK"
  connection_id          = aws_apigatewayv2_vpc_link.this.id
  integration_uri        = aws_lb_listener.internal.arn
  payload_format_version = "1.0"
  timeout_milliseconds   = 29000

  dynamic "tls_config" {
    for_each = local.custom_domain ? [1] : []
    content {
      server_name_to_verify = local.alb_host
    }
  }
}

resource "aws_apigatewayv2_route" "api" {
  api_id             = aws_apigatewayv2_api.this.id
  route_key          = "ANY /api/{proxy+}"
  target             = "integrations/${aws_apigatewayv2_integration.alb.id}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.cognito.id
}

resource "aws_cloudwatch_log_group" "api_access" {
  name              = "/aws/apigateway/${local.name}"
  retention_in_days = local.log_retention_days
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.this.id
  name        = "$default"
  auto_deploy = true

  # Starting values, not derived from measured load (nfr-notes.md).
  default_route_settings {
    throttling_burst_limit = 100
    throttling_rate_limit  = 50
  }

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.api_access.arn
    format = jsonencode({
      requestId      = "$context.requestId"
      ip             = "$context.identity.sourceIp"
      requestTime    = "$context.requestTime"
      httpMethod     = "$context.httpMethod"
      routeKey       = "$context.routeKey"
      status         = "$context.status"
      responseLength = "$context.responseLength"
      latencyMs      = "$context.responseLatency"
      authorizerErr  = "$context.authorizer.error"
      integrationErr = "$context.integrationErrorMessage"
    })
  }
}
