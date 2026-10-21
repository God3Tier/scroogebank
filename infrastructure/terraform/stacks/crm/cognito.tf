# Identity (ADR-0005, ADR-0025): MFA required, managed login with the
# authorization-code flow and PKCE from a public app client, ADMIN and AGENT
# groups in cognito:groups, a client-credentials client for the transaction
# import, and the login/password-reset audit triggers (ADR-0040).

resource "random_id" "cognito_domain" {
  byte_length = 3
}

resource "aws_cognito_user_pool" "this" {
  name = local.name

  # Managed login (the new hosted UI) needs the Essentials tier.
  user_pool_tier      = "ESSENTIALS"
  deletion_protection = local.deletion_protection ? "ACTIVE" : "INACTIVE"

  # user-service creates users with their e-mail address as the username.
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]
  username_configuration {
    case_sensitive = false
  }

  mfa_configuration = "ON"
  software_token_mfa_configuration {
    enabled = true
  }

  admin_create_user_config {
    allow_admin_create_user_only = true
  }

  password_policy {
    minimum_length                   = 12
    require_lowercase                = true
    require_uppercase                = true
    require_numbers                  = true
    require_symbols                  = true
    temporary_password_validity_days = 3
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  dynamic "lambda_config" {
    for_each = var.enable_cognito_triggers ? [1] : []
    content {
      post_authentication = aws_lambda_function.cognito_triggers[0].arn
      post_confirmation   = aws_lambda_function.cognito_triggers[0].arn
    }
  }
}

resource "aws_cognito_user_group" "this" {
  for_each     = toset(["ADMIN", "AGENT"])
  name         = each.key
  user_pool_id = aws_cognito_user_pool.this.id
  description  = "CRM ${lower(each.key)}s"
}

resource "aws_cognito_user_pool_domain" "this" {
  domain                = coalesce(var.cognito_domain_prefix, "${local.name}-${random_id.cognito_domain.hex}")
  user_pool_id          = aws_cognito_user_pool.this.id
  managed_login_version = 2
}

locals {
  # The custom domain first, when there is one. CloudFront still answers on
  # its own name, and the SPA builds its callback from the address it was
  # loaded from, so both sign in.
  web_origins = concat(
    local.custom_domain ? ["https://${local.app_host}"] : [],
    ["https://${aws_cloudfront_distribution.this.domain_name}"],
    var.local_dev_origins,
  )
}

# The SPA's client (ADR-0025 decision 2): public, code grant only, PKCE added
# by oidc-client-ts, token revocation on.
resource "aws_cognito_user_pool_client" "web" {
  name         = "${local.name}-web"
  user_pool_id = aws_cognito_user_pool.this.id

  generate_secret                      = false
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "email"]
  supported_identity_providers         = ["COGNITO"]
  callback_urls                        = [for o in local.web_origins : "${o}/auth/callback"]
  logout_urls                          = [for o in local.web_origins : "${o}/"]
  # Do not enable ALLOW_USER_SRP_AUTH: browser sign-in goes only through the
  # managed-login authorization-code flow with PKCE (ADR-0025).
  explicit_auth_flows           = ["ALLOW_REFRESH_TOKEN_AUTH"]
  enable_token_revocation       = true
  prevent_user_existence_errors = "ENABLED"

  access_token_validity  = 60
  id_token_validity      = 60
  refresh_token_validity = 12
  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "hours"
  }
}

# Managed login shows a page only for clients that have a style.
resource "aws_cognito_managed_login_branding" "web" {
  user_pool_id                = aws_cognito_user_pool.this.id
  client_id                   = aws_cognito_user_pool_client.web.id
  use_cognito_provided_values = true
}

# Service identity for the transaction import (ADR-0037): resource server
# crm-accounts with scope lookup, checked by account-service.
resource "aws_cognito_resource_server" "accounts" {
  user_pool_id = aws_cognito_user_pool.this.id
  identifier   = "crm-accounts"
  name         = "CRM accounts"

  scope {
    scope_name        = "lookup"
    scope_description = "Look up accounts for the transaction import"
  }
}

resource "aws_cognito_user_pool_client" "transaction_ingest" {
  name         = "${local.name}-transaction-ingest"
  user_pool_id = aws_cognito_user_pool.this.id

  generate_secret                      = true
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["client_credentials"]
  allowed_oauth_scopes                 = ["crm-accounts/lookup"]
  supported_identity_providers         = ["COGNITO"]
  explicit_auth_flows                  = ["ALLOW_REFRESH_TOKEN_AUTH"]

  depends_on = [aws_cognito_resource_server.accounts]
}

resource "aws_secretsmanager_secret" "ingest_client" {
  name                    = "${local.name}/cognito/transaction-ingest"
  description             = "Client ID and secret of the transaction-ingest app client"
  recovery_window_in_days = local.deletion_protection ? 7 : 0
}

resource "aws_secretsmanager_secret_version" "ingest_client" {
  secret_id = aws_secretsmanager_secret.ingest_client.id
  secret_string = jsonencode({
    client_id     = aws_cognito_user_pool_client.transaction_ingest.id
    client_secret = aws_cognito_user_pool_client.transaction_ingest.client_secret
  })
}
