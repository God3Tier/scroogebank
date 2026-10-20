# Plans the module against a mocked AWS provider (no AWS account needed) and
# checks how a Java service serves TLS to the internal ALB (INF-08).
# Run with: terraform init -backend=false && terraform test

mock_provider "aws" {
  mock_data "aws_region" {
    defaults = { region = "ap-southeast-1" }
  }
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
}

variables {
  name               = "user-service"
  name_prefix        = "crm-sg-prod"
  cluster_arn        = "arn:aws:ecs:ap-southeast-1:123456789012:cluster/crm-sg-prod"
  cluster_name       = "crm-sg-prod"
  subnet_ids         = ["subnet-0aaaaaaaaaaaaaaaa", "subnet-0bbbbbbbbbbbbbbbb"]
  security_group_ids = ["sg-0user"]
  image              = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/crm-sg-prod/user-service:abc1234"
  port               = 8081
}

run "java_serves_tls" {
  command = plan

  variables {
    port_name = "https"
    tls = {
      mode            = "java"
      ca_pem          = "-----BEGIN CERTIFICATE-----\nexample\n-----END CERTIFICATE-----"
      serves_tls      = true
      cert_secret_arn = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:crm-sg-prod/tls/user-AbCdEf"
    }
    load_balancer = {
      listener_arn      = "arn:aws:elasticloadbalancing:ap-southeast-1:123456789012:listener/app/crm-sg-prod/abc/def"
      alb_arn_suffix    = "app/crm-sg-prod/abc"
      vpc_id            = "vpc-0123456789abcdef0"
      protocol          = "HTTPS"
      health_check_path = "/actuator/health"
      rules             = [{ priority = 20, paths = ["/api/users", "/api/users/*"] }]
    }
  }

  assert {
    condition = (
      contains(jsondecode(aws_ecs_task_definition.this.container_definitions)[1].environment, { name = "SERVER_SSL_CERTIFICATE", value = "/tls/cert.pem" }) &&
      contains(jsondecode(aws_ecs_task_definition.this.container_definitions)[1].environment, { name = "SERVER_SSL_CERTIFICATEPRIVATEKEY", value = "/tls/key.pem" })
    )
    error_message = "Spring Boot serves HTTPS from the certificate and key that tls-init writes."
  }
  assert {
    condition = (
      jsondecode(aws_ecs_task_definition.this.container_definitions)[0].secrets[*].name == ["TLS_CERT", "TLS_KEY"] &&
      strcontains(jsondecode(aws_ecs_task_definition.this.container_definitions)[0].command[0], "> /tls/cert.pem") &&
      strcontains(jsondecode(aws_ecs_task_definition.this.container_definitions)[0].command[0], "> /tls/key.pem")
    )
    error_message = "The Java tls-init container reads the service's certificate secret and writes cert.pem and key.pem."
  }
  assert {
    condition = (
      jsondecode(aws_ecs_task_definition.this.container_definitions)[1].portMappings[0].name == "https" &&
      !can(jsondecode(aws_ecs_task_definition.this.container_definitions)[1].portMappings[0].appProtocol)
    )
    error_message = "A port that serves TLS has no appProtocol, so Service Connect proxies it as TCP (ADR-0042 decision 7)."
  }
  assert {
    condition     = aws_lb_target_group.this[0].protocol == "HTTPS" && aws_lb_target_group.this[0].health_check[0].protocol == "HTTPS"
    error_message = "The ALB sends requests and health checks over HTTPS."
  }
}

run "java_client_only" {
  command = plan

  variables {
    name         = "dashboard-service"
    port         = 8085
    app_protocol = "http"
    tls = {
      mode   = "java"
      ca_pem = "-----BEGIN CERTIFICATE-----\nexample\n-----END CERTIFICATE-----"
    }
  }

  assert {
    condition     = length([for e in jsondecode(aws_ecs_task_definition.this.container_definitions)[1].environment : e if startswith(e.name, "SERVER_SSL_")]) == 0
    error_message = "A Java service that does not serve TLS keeps plain HTTP."
  }
  assert {
    condition     = length(jsondecode(aws_ecs_task_definition.this.container_definitions)[0].secrets) == 0
    error_message = "Without serves_tls, tls-init reads no certificate secret."
  }
  assert {
    condition     = contains(jsondecode(aws_ecs_task_definition.this.container_definitions)[1].environment[*].name, "JAVA_TOOL_OPTIONS")
    error_message = "It still trusts the project CA for its own calls."
  }
}
