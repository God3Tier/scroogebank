# Test-only tooling (ADR-0071), created only with enable_test_tooling:
#
# - A bucket for test inputs and reports: the ZAP scanner's specs and reports,
#   and the load test's transaction seed file. It empties itself after 7 days
#   and is destroyed with the stack.
# - A one-off Fargate task that runs OWASP ZAP inside the VPC against the
#   internal ALB, so the services can be scanned directly, past CloudFront, the
#   WAF and API Gateway's JWT check. Its security group (security_groups.tf)
#   is the only one added to the ALB's allowed sources. It runs only when
#   tests/security/zap/run-in-vpc.sh starts it with aws ecs run-task.
#
# The task has three containers sharing /zap/wrk: fetch copies a run's inputs
# from the bucket, zap scans, and upload copies the reports back, even when the
# scan fails.

locals {
  test_tooling = var.enable_test_tooling ? 1 : 0
  # Released at least two weeks before they were pinned (2026-11-02).
  zap_image     = "zaproxy/zap-stable:2.17.0"
  aws_cli_image = "public.ecr.aws/aws-cli/aws-cli:2.38.2"
}

resource "aws_s3_bucket" "test_runs" {
  count         = local.test_tooling
  bucket        = "${local.name}-test-runs-${local.account_id}"
  force_destroy = true # only test inputs and reports
}

resource "aws_s3_bucket_public_access_block" "test_runs" {
  count                   = local.test_tooling
  bucket                  = aws_s3_bucket.test_runs[0].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "test_runs" {
  count  = local.test_tooling
  bucket = aws_s3_bucket.test_runs[0].id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "test_runs" {
  count  = local.test_tooling
  bucket = aws_s3_bucket.test_runs[0].id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "test_runs" {
  count  = local.test_tooling
  bucket = aws_s3_bucket.test_runs[0].id
  rule {
    id     = "expire-test-files"
    status = "Enabled"
    filter {}
    expiration {
      days = 7
    }
  }
}

resource "aws_cloudwatch_log_group" "zap_scanner" {
  count             = local.test_tooling
  name              = "/ecs/${local.name}-zap-scanner"
  retention_in_days = local.log_retention_days
}

resource "aws_iam_role" "zap_scanner_exec" {
  count              = local.test_tooling
  name               = "${local.name}-zap-scanner-exec"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "zap_scanner_exec" {
  count      = local.test_tooling
  role       = aws_iam_role.zap_scanner_exec[0].name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# The task reads its inputs from in/ and writes reports to reports/, nothing else.
resource "aws_iam_role" "zap_scanner_task" {
  count              = local.test_tooling
  name               = "${local.name}-zap-scanner-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

data "aws_iam_policy_document" "zap_scanner_task" {
  count = local.test_tooling
  statement {
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.test_runs[0].arn]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["in/*"]
    }
  }
  statement {
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.test_runs[0].arn}/in/*"]
  }
  statement {
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.test_runs[0].arn}/reports/*"]
  }
}

resource "aws_iam_role_policy" "zap_scanner_task" {
  count  = local.test_tooling
  name   = "test-runs-bucket"
  role   = aws_iam_role.zap_scanner_task[0].id
  policy = data.aws_iam_policy_document.zap_scanner_task[0].json
}

resource "aws_ecs_task_definition" "zap_scanner" {
  count                    = local.test_tooling
  family                   = "${local.name}-zap-scanner"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 1024
  memory                   = 4096
  execution_role_arn       = aws_iam_role.zap_scanner_exec[0].arn
  task_role_arn            = aws_iam_role.zap_scanner_task[0].arn

  volume {
    name = "wrk"
  }

  # run-in-vpc.sh sets RUN_ID on every container, and TARGET and SCAN_ARGS on
  # zap; the agent's token, if any, is one of the run's input files.
  container_definitions = jsonencode([
    {
      name        = "fetch"
      image       = local.aws_cli_image
      essential   = false
      entryPoint  = ["sh", "-c"]
      command     = ["aws s3 cp \"s3://$BUCKET/in/$RUN_ID/\" /zap/wrk/ --recursive && chmod -R a+rwX /zap/wrk"]
      environment = [{ name = "BUCKET", value = aws_s3_bucket.test_runs[0].id }]
      mountPoints = [{ sourceVolume = "wrk", containerPath = "/zap/wrk" }]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.zap_scanner[0].name
          awslogs-region        = local.region
          awslogs-stream-prefix = "zap"
        }
      }
    },
    {
      name        = "zap"
      image       = local.zap_image
      essential   = false
      command     = ["sh", "/zap/wrk/scan.sh"]
      dependsOn   = [{ containerName = "fetch", condition = "SUCCESS" }]
      environment = [{ name = "TARGET", value = local.alb_base_url }]
      mountPoints = [{ sourceVolume = "wrk", containerPath = "/zap/wrk" }]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.zap_scanner[0].name
          awslogs-region        = local.region
          awslogs-stream-prefix = "zap"
        }
      }
    },
    {
      name        = "upload"
      image       = local.aws_cli_image
      essential   = true
      entryPoint  = ["sh", "-c"]
      command     = ["aws s3 cp /zap/wrk/out/ \"s3://$BUCKET/reports/$RUN_ID/\" --recursive"]
      dependsOn   = [{ containerName = "zap", condition = "COMPLETE" }]
      environment = [{ name = "BUCKET", value = aws_s3_bucket.test_runs[0].id }]
      mountPoints = [{ sourceVolume = "wrk", containerPath = "/zap/wrk" }]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.zap_scanner[0].name
          awslogs-region        = local.region
          awslogs-stream-prefix = "zap"
        }
      }
    },
  ])
}
