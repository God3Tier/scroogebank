# GitHub Actions deploys through OIDC with no stored keys (ADR-0028
# decision 2). The OIDC provider is account-wide and lives in the bootstrap
# stack. This role can push images, roll out ECS services, upload the
# frontend and update the Lambda code; it cannot change infrastructure, which
# a person applies with Terraform. Off until the team adds the deploy
# workflow (enable_github_deploy_role); local deployments do not need it.

data "aws_iam_openid_connect_provider" "github" {
  count = var.enable_github_deploy_role ? 1 : 0
  url   = "https://token.actions.githubusercontent.com"
}

locals {
  deploy_branches = coalesce(var.github_deploy_branches, local.is_prod ? ["main"] : ["staging"])
}

data "aws_iam_policy_document" "github_assume" {
  count = var.enable_github_deploy_role ? 1 : 0
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github[0].arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = [for b in local.deploy_branches : "repo:${var.github_repository}:ref:refs/heads/${b}"]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  count              = var.enable_github_deploy_role ? 1 : 0
  name               = "${local.name}-github-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_assume[0].json
}

# The business-dashboard stack adds its own repository and roles to this role
# (ADR-0047).
locals {
  service_modules = {
    user        = module.user_service
    client      = module.client_service
    account     = module.account_service
    auditor     = module.auditor
    chain       = module.chain_verify
    transaction = module.transaction_service
    alerts      = module.event_alert_service
  }
}

data "aws_iam_policy_document" "github_deploy" {
  count = var.enable_github_deploy_role ? 1 : 0
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "PushImages"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImages",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = [for r in aws_ecr_repository.this : r.arn]
  }

  # Registering a task definition revision cannot be limited by resource.
  statement {
    sid       = "TaskDefinitions"
    actions   = ["ecs:RegisterTaskDefinition", "ecs:DescribeTaskDefinition"]
    resources = ["*"]
  }

  statement {
    sid       = "RollOutServices"
    actions   = ["ecs:UpdateService", "ecs:DescribeServices"]
    resources = ["arn:${data.aws_partition.current.partition}:ecs:${local.region}:${local.account_id}:service/${aws_ecs_cluster.this.name}/*"]
  }

  statement {
    sid       = "RunMigrations"
    actions   = ["ecs:RunTask", "ecs:DescribeTasks"]
    resources = ["*"]
    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [aws_ecs_cluster.this.arn]
    }
  }

  statement {
    sid       = "PassServiceRoles"
    actions   = ["iam:PassRole"]
    resources = flatten([for m in local.service_modules : [m.task_role_arn, m.execution_role_arn]])
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  statement {
    sid       = "UploadFrontend"
    actions   = ["s3:ListBucket", "s3:PutObject", "s3:DeleteObject"]
    resources = [aws_s3_bucket.frontend.arn, "${aws_s3_bucket.frontend.arn}/*"]
  }

  statement {
    sid       = "InvalidateFrontend"
    actions   = ["cloudfront:CreateInvalidation"]
    resources = [aws_cloudfront_distribution.this.arn]
  }

  dynamic "statement" {
    for_each = length(local.deployable_functions) > 0 ? [1] : []
    content {
      sid       = "UpdateLambdaCode"
      actions   = ["lambda:UpdateFunctionCode", "lambda:GetFunction"]
      resources = local.deployable_functions
    }
  }
}

locals {
  deployable_functions = concat(
    var.enable_cognito_triggers ? [aws_lambda_function.cognito_triggers[0].arn] : [],
    var.enable_ingest_lambda ? [aws_lambda_function.ingest[0].arn] : [],
  )
}

resource "aws_iam_role_policy" "github_deploy" {
  count  = var.enable_github_deploy_role ? 1 : 0
  name   = "deploy"
  role   = aws_iam_role.github_deploy[0].id
  policy = data.aws_iam_policy_document.github_deploy[0].json
}
