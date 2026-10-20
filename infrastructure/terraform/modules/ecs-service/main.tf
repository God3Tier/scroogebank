# One containerised service on ECS Fargate (ADR-0018), or, with
# create_service = false, a task definition that EventBridge Scheduler starts
# (chain-verify). A service gets its own roles and log group, joins Service
# Connect, and may sit behind the internal ALB. Before its app starts, a task
# may run tls-init, then db-migrate; the app starts only if both succeed.

terraform {
  required_version = ">= 1.10.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

data "aws_region" "current" {}

locals {
  family = "${var.name_prefix}-${var.name}"

  # The tls-init container writes the files the service reads at /tls, so no
  # secret is baked into an image (ADR-0042 decision 5):
  #   ca.pem        the project CA, trusted for service-to-service TLS
  #   rds-ca.pem    the RDS CA bundle for this region, for verify-full
  #   ca-bundle.pem public roots + both CAs (Go reads it through SSL_CERT_FILE)
  #   cert.pem, key.pem   the service's own certificate, when it serves TLS;
  #                 a Java service serves it through Spring Boot's server.ssl
  #   truststore    the JVM's cacerts plus the project CA (Java services)
  tls_script_go = <<-EOT
    set -eu
    printf '%s\n' "$CRM_CA_PEM" > /tls/ca.pem
    wget -qO /tls/rds-ca.pem "$RDS_CA_URL"
    cat /etc/ssl/certs/ca-certificates.crt /tls/ca.pem /tls/rds-ca.pem > /tls/ca-bundle.pem
    if [ -n "$${TLS_CERT:-}" ]; then
      printf '%s\n' "$TLS_CERT" > /tls/cert.pem
      printf '%s\n' "$TLS_KEY" > /tls/key.pem
    fi
    chmod 0444 /tls/*.pem
    chown -R 65532:65532 /tls
  EOT

  # /tls is opened to all because some images run as a non-root user
  # (event-alert-service's alerts).
  tls_script_java = <<-EOT
    set -eu
    printf '%s\n' "$CRM_CA_PEM" > /tls/ca.pem
    wget -qO /tls/rds-ca.pem "$RDS_CA_URL"
    cp "$JAVA_HOME/lib/security/cacerts" /tls/truststore
    chmod 0644 /tls/truststore
    keytool -importcert -noprompt -keystore /tls/truststore -storepass changeit -alias crm-project-ca -file /tls/ca.pem
    if [ -n "$${TLS_CERT:-}" ]; then
      printf '%s\n' "$TLS_CERT" > /tls/cert.pem
      printf '%s\n' "$TLS_KEY" > /tls/key.pem
    fi
    chmod 0444 /tls/*
    chmod 0755 /tls
  EOT

  tls_enabled = var.tls != null
  serves_tls  = local.tls_enabled && try(var.tls.serves_tls, false)

  tls_env = local.tls_enabled ? (
    var.tls.mode == "java"
    ? merge(
      { JAVA_TOOL_OPTIONS = "-Djavax.net.ssl.trustStore=/tls/truststore -Djavax.net.ssl.trustStorePassword=changeit" },
      # Spring Boot's server.ssl.certificate and certificate-private-key.
      local.serves_tls ? { SERVER_SSL_CERTIFICATE = "/tls/cert.pem", SERVER_SSL_CERTIFICATEPRIVATEKEY = "/tls/key.pem" } : {},
    )
    : { SSL_CERT_FILE = "/tls/ca-bundle.pem" }
  ) : {}

  init_container = local.tls_enabled ? [{
    name      = "tls-init"
    essential = false
    # Go images are distroless (no shell), so they get Alpine; Java images
    # have a shell and keytool, so they prepare their own truststore.
    image      = var.tls.mode == "java" ? var.image : "public.ecr.aws/docker/library/alpine:3.20"
    user       = "0"
    entryPoint = ["sh", "-c"]
    # A Windows checkout (core.autocrlf) gives this file CRLF line endings,
    # and sh fails on the carriage returns ("set: illegal option -").
    command = [replace(var.tls.mode == "java" ? local.tls_script_java : local.tls_script_go, "\r", "")]
    environment = [
      { name = "CRM_CA_PEM", value = var.tls.ca_pem },
      { name = "RDS_CA_URL", value = "https://truststore.pki.rds.amazonaws.com/${data.aws_region.current.region}/${data.aws_region.current.region}-bundle.pem" },
    ]
    secrets = var.tls.serves_tls ? [
      { name = "TLS_CERT", valueFrom = "${var.tls.cert_secret_arn}:cert::" },
      { name = "TLS_KEY", valueFrom = "${var.tls.cert_secret_arn}:key::" },
    ] : []
    mountPoints = [{ sourceVolume = "tls", containerPath = "/tls", readOnly = false }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.this.name
        awslogs-region        = data.aws_region.current.region
        awslogs-stream-prefix = "tls-init"
      }
    }
  }] : []

  # Applies the database's migrations as its master user (ADR-0028 decision
  # 3). Only this container gets the master credentials; Flyway's lock keeps
  # several tasks starting at once from migrating twice.
  migrate_container = var.migrations == null ? [] : [{
    name      = "db-migrate"
    essential = false
    image     = var.migrations.image
    environment = [
      { name = "FLYWAY_URL", value = var.migrations.url },
      { name = "FLYWAY_LOCATIONS", value = "filesystem:/flyway/sql/${var.migrations.location}" },
    ]
    secrets     = [for k, v in var.migrations.secrets : { name = k, valueFrom = v }]
    mountPoints = [{ sourceVolume = "tls", containerPath = "/tls", readOnly = true }]
    dependsOn   = [{ containerName = "tls-init", condition = "SUCCESS" }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.this.name
        awslogs-region        = data.aws_region.current.region
        awslogs-stream-prefix = "db-migrate"
      }
    }
  }]

  ports = concat(
    var.port == null ? [] : [{ name = var.port_name, port = var.port, app_protocol = var.app_protocol }],
    var.extra_ports,
  )

  app_container = merge(
    {
      name      = var.name
      image     = var.image
      essential = true
      portMappings = [for p in local.ports : merge(
        { name = p.name, containerPort = p.port, protocol = "tcp" },
        p.app_protocol == null ? {} : { appProtocol = p.app_protocol },
      )]
      environment = [for k, v in merge(var.environment, local.tls_env) : { name = k, value = v }]
      secrets     = [for k, v in var.secrets : { name = k, valueFrom = v }]
      mountPoints = local.tls_enabled ? [{ sourceVolume = "tls", containerPath = "/tls", readOnly = true }] : []
      dependsOn = concat(
        local.tls_enabled ? [{ containerName = "tls-init", condition = "SUCCESS" }] : [],
        var.migrations == null ? [] : [{ containerName = "db-migrate", condition = "SUCCESS" }],
      )
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.this.name
          awslogs-region        = data.aws_region.current.region
          awslogs-stream-prefix = var.name
        }
      }
    },
    var.entry_point == null ? {} : { entryPoint = var.entry_point },
  )

  # Counts and keys below use only values known at plan time: the secret
  # ARNs themselves may not exist until apply.
  secret_arns = concat(var.secret_arns, local.serves_tls ? [var.tls.cert_secret_arn] : [])
}

# ------------------------------------------------------------------ logs

resource "aws_cloudwatch_log_group" "this" {
  name              = "/ecs/${local.family}"
  retention_in_days = var.log_retention_days
  tags              = var.tags
}

# ----------------------------------------------------------------- roles

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# Pulls the image, writes logs and reads this service's secrets only.
resource "aws_iam_role" "execution" {
  name               = "${local.family}-exec"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secrets" {
  count = length(local.secret_arns) > 0 ? 1 : 0
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = local.secret_arns
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  count  = length(local.secret_arns) > 0 ? 1 : 0
  name   = "read-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets[0].json
}

# What the application itself may call (least privilege, ADR-0025, NFR 8).
resource "aws_iam_role" "task" {
  name               = "${local.family}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy" "task" {
  for_each = var.task_role_policies
  name     = each.key
  role     = aws_iam_role.task.id
  policy   = each.value
}

# ------------------------------------------------------- task definition

resource "aws_ecs_task_definition" "this" {
  family                   = local.family
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  dynamic "volume" {
    for_each = local.tls_enabled ? [1] : []
    content {
      name = "tls"
    }
  }

  container_definitions = jsonencode(concat(local.init_container, local.migrate_container, [local.app_container]))
  tags                  = var.tags
}

# ------------------------------------------------------------ load balancer

resource "aws_lb_target_group" "this" {
  count       = var.load_balancer == null ? 0 : 1
  name_prefix = substr(replace(var.name, "-", ""), 0, 6)
  vpc_id      = var.load_balancer.vpc_id
  target_type = "ip"
  port        = var.port
  protocol    = var.load_balancer.protocol

  deregistration_delay = 30

  health_check {
    path                = var.load_balancer.health_check_path
    protocol            = var.load_balancer.protocol
    matcher             = "200"
    interval            = 30
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = var.tags

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_lb_listener_rule" "this" {
  for_each     = var.load_balancer == null ? {} : { for r in var.load_balancer.rules : tostring(r.priority) => r }
  listener_arn = var.load_balancer.listener_arn
  priority     = each.value.priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this[0].arn
  }

  condition {
    path_pattern {
      values = each.value.paths
    }
  }

  tags = var.tags
}

# ------------------------------------------------------------------ service

resource "aws_ecs_service" "this" {
  count           = var.create_service ? 1 : 0
  name            = var.name
  cluster         = var.cluster_arn
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.min_tasks

  enable_ecs_managed_tags           = true
  propagate_tags                    = "SERVICE"
  health_check_grace_period_seconds = var.load_balancer == null ? null : 180

  capacity_provider_strategy {
    capacity_provider = var.use_spot ? "FARGATE_SPOT" : "FARGATE"
    weight            = 1
  }

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = var.security_group_ids
    assign_public_ip = false
  }

  # A deployment whose tasks never become healthy rolls back (NFR 5).
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  dynamic "load_balancer" {
    for_each = var.load_balancer == null ? [] : [1]
    content {
      target_group_arn = aws_lb_target_group.this[0].arn
      container_name   = var.name
      container_port   = var.port
    }
  }

  dynamic "service_connect_configuration" {
    for_each = var.service_connect == null ? [] : [var.service_connect]
    content {
      enabled   = true
      namespace = service_connect_configuration.value.namespace_arn

      dynamic "service" {
        for_each = service_connect_configuration.value.aliases
        content {
          port_name      = service.value.port_name
          discovery_name = "${var.name}-${service.value.port_name}"
          client_alias {
            dns_name = service.value.dns_name
            port     = service.value.port
          }
        }
      }
    }
  }

  tags = var.tags

  # Application Auto Scaling owns the task count; the stop model changes its
  # minimum and maximum instead (ADR-0029).
  lifecycle {
    ignore_changes = [desired_count]
  }

  depends_on = [aws_lb_listener_rule.this]
}

# -------------------------------------------------------------- auto scaling

resource "aws_appautoscaling_target" "this" {
  count              = var.create_service ? 1 : 0
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${var.cluster_name}/${aws_ecs_service.this[0].name}"
  min_capacity       = var.min_tasks
  max_capacity       = var.max_tasks
}

resource "aws_appautoscaling_policy" "cpu" {
  count              = var.create_service ? 1 : 0
  name               = "${local.family}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this[0].service_namespace
  scalable_dimension = aws_appautoscaling_target.this[0].scalable_dimension
  resource_id        = aws_appautoscaling_target.this[0].resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 120
    scale_out_cooldown = 60
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

resource "aws_appautoscaling_policy" "requests" {
  count              = var.create_service && var.load_balancer != null ? 1 : 0
  name               = "${local.family}-requests"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this[0].service_namespace
  scalable_dimension = aws_appautoscaling_target.this[0].scalable_dimension
  resource_id        = aws_appautoscaling_target.this[0].resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = 300
    scale_in_cooldown  = 120
    scale_out_cooldown = 60
    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "${var.load_balancer.alb_arn_suffix}/${aws_lb_target_group.this[0].arn_suffix}"
    }
  }
}

# The auditor scales on the audit queue's backlog (NFR 1).
resource "aws_appautoscaling_policy" "queue" {
  count              = var.create_service && var.scaling_queue_name != null ? 1 : 0
  name               = "${local.family}-queue"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this[0].service_namespace
  scalable_dimension = aws_appautoscaling_target.this[0].scalable_dimension
  resource_id        = aws_appautoscaling_target.this[0].resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = 100
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
    customized_metric_specification {
      metric_name = "ApproximateNumberOfMessagesVisible"
      namespace   = "AWS/SQS"
      statistic   = "Average"
      dimensions {
        name  = "QueueName"
        value = var.scaling_queue_name
      }
    }
  }
}
