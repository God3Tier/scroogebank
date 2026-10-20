# Plans the stack against a mocked AWS provider and a stand-in for the CRM
# stack's state (no AWS account needed), and checks how it attaches to that
# stack (ADR-0047). Run with: terraform init -backend=false && terraform test

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_data "aws_partition" {
    defaults = { partition = "aws" }
  }
  mock_data "aws_region" {
    defaults = { region = "ap-southeast-1" }
  }
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
}

variables {
  environment  = "prod"
  state_bucket = "crm-tfstate-123456789012"
  image_tag    = "abc1234"
}

# A running prod CRM stack that already allows the dashboard's role.
override_data {
  target = data.terraform_remote_state.crm
  values = {
    outputs = {
      dashboard_inputs = {
        name_prefix    = "crm-sg-prod"
        vpc_id         = "vpc-0123456789abcdef0"
        app_subnet_ids = ["subnet-0aaaaaaaaaaaaaaaa", "subnet-0bbbbbbbbbbbbbbbb"]
        cluster_arn    = "arn:aws:ecs:ap-southeast-1:123456789012:cluster/crm-sg-prod"
        cluster_name   = "crm-sg-prod"
        namespace_arn  = "arn:aws:servicediscovery:ap-southeast-1:123456789012:namespace/ns-example"
        alb = {
          listener_arn   = "arn:aws:elasticloadbalancing:ap-southeast-1:123456789012:listener/app/crm-sg-prod/abc/def"
          alb_arn_suffix = "app/crm-sg-prod/abc"
          vpc_id         = "vpc-0123456789abcdef0"
        }
        alb_sg = "sg-0a1b"
        upstreams = {
          user    = { security_group = "sg-0user", port = 8081, url = "http://user-service:8081" }
          client  = { security_group = "sg-0client", port = 8080, url = "https://client-service:8080" }
          account = { security_group = "sg-0account", port = 8083, url = "https://account-service:8083" }
        }
        ca_pem                   = "-----BEGIN CERTIFICATE-----\nexample\n-----END CERTIFICATE-----"
        cognito_issuer           = "https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_example"
        cognito_web_client_id    = "exampleclientid"
        audit_queue_url          = "https://sqs.ap-southeast-1.amazonaws.com/123456789012/crm-sg-prod-audit-insert"
        audit_queue_arn          = "arn:aws:sqs:ap-southeast-1:123456789012:crm-sg-prod-audit-insert"
        audit_producer_role_arns = ["arn:aws:iam::123456789012:role/crm-sg-prod-user-service-task", "arn:aws:iam::123456789012:role/crm-sg-prod-dashboard-service-task"]
        min_tasks                = 1
        max_tasks                = 6
        use_spot                 = false
        log_retention_days       = 30
        cpu_architecture         = "X86_64"
        deletion_protection      = true
        github_deploy_role_name  = null
      }
    }
  }
}

run "prod" {
  command = plan

  assert {
    condition     = output.task_role_arn == "arn:aws:iam::123456789012:role/crm-sg-prod-dashboard-service-task"
    error_message = "The predicted task role is the name the ecs-service module gives it."
  }
  assert {
    condition     = aws_ecr_repository.dashboard.name == "crm-sg-prod/dashboard-service" && aws_ecr_repository.dashboard.image_tag_mutability == "IMMUTABLE"
    error_message = "The repository keeps the CRM stack's name and immutable tags."
  }
  assert {
    condition     = aws_ecr_repository.dashboard.force_delete == false
    error_message = "With deletion protection on, destroy must not delete pushed images."
  }
  assert {
    condition     = aws_security_group.dashboard.vpc_id == "vpc-0123456789abcdef0" && aws_vpc_security_group_ingress_rule.from_alb.referenced_security_group_id == "sg-0a1b" && aws_vpc_security_group_ingress_rule.from_alb.from_port == 8085
    error_message = "Only the CRM stack's internal ALB reaches the dashboard, on 8085."
  }
  assert {
    condition = (
      length(aws_vpc_security_group_ingress_rule.upstream) == 3 &&
      aws_vpc_security_group_ingress_rule.upstream["user"].security_group_id == "sg-0user" && aws_vpc_security_group_ingress_rule.upstream["user"].from_port == 8081 &&
      aws_vpc_security_group_ingress_rule.upstream["client"].security_group_id == "sg-0client" && aws_vpc_security_group_ingress_rule.upstream["client"].from_port == 8080 &&
      aws_vpc_security_group_ingress_rule.upstream["account"].security_group_id == "sg-0account" && aws_vpc_security_group_ingress_rule.upstream["account"].from_port == 8083
    )
    error_message = "The dashboard may call user-, client- and account-service, each on its own port, and nothing else."
  }
  assert {
    condition     = module.dashboard_service.service_name == "dashboard-service"
    error_message = "The service keeps its name, so the runbook and the CloudWatch dashboard find it."
  }
  assert {
    condition     = length(aws_iam_role_policy.github_deploy) == 0
    error_message = "No deploy-role policy while the CRM stack has no deploy role."
  }
}

run "stopped_teardown_ready_with_ci" {
  command = plan

  override_data {
    target = data.terraform_remote_state.crm
    values = {
      outputs = {
        dashboard_inputs = {
          name_prefix    = "crm-sg-prod"
          vpc_id         = "vpc-0123456789abcdef0"
          app_subnet_ids = ["subnet-0aaaaaaaaaaaaaaaa", "subnet-0bbbbbbbbbbbbbbbb"]
          cluster_arn    = "arn:aws:ecs:ap-southeast-1:123456789012:cluster/crm-sg-prod"
          cluster_name   = "crm-sg-prod"
          namespace_arn  = "arn:aws:servicediscovery:ap-southeast-1:123456789012:namespace/ns-example"
          alb = {
            listener_arn   = "arn:aws:elasticloadbalancing:ap-southeast-1:123456789012:listener/app/crm-sg-prod/abc/def"
            alb_arn_suffix = "app/crm-sg-prod/abc"
            vpc_id         = "vpc-0123456789abcdef0"
          }
          alb_sg = "sg-0a1b"
          upstreams = {
            user    = { security_group = "sg-0user", port = 8081, url = "http://user-service:8081" }
            client  = { security_group = "sg-0client", port = 8080, url = "https://client-service:8080" }
            account = { security_group = "sg-0account", port = 8083, url = "https://account-service:8083" }
          }
          ca_pem                   = "-----BEGIN CERTIFICATE-----\nexample\n-----END CERTIFICATE-----"
          cognito_issuer           = "https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_example"
          cognito_web_client_id    = "exampleclientid"
          audit_queue_url          = "https://sqs.ap-southeast-1.amazonaws.com/123456789012/crm-sg-prod-audit-insert"
          audit_queue_arn          = "arn:aws:sqs:ap-southeast-1:123456789012:crm-sg-prod-audit-insert"
          audit_producer_role_arns = ["arn:aws:iam::123456789012:role/crm-sg-prod-dashboard-service-task"]
          min_tasks                = 0
          max_tasks                = 0
          use_spot                 = false
          log_retention_days       = 30
          cpu_architecture         = "X86_64"
          deletion_protection      = false
          github_deploy_role_name  = "crm-sg-prod-github-deploy"
        }
      }
    }
  }

  assert {
    condition     = aws_ecr_repository.dashboard.force_delete == true
    error_message = "With deletion protection off, destroy empties the repository, as the CRM stack's do."
  }
  assert {
    condition     = length(aws_iam_role_policy.github_deploy) == 1 && aws_iam_role_policy.github_deploy[0].role == "crm-sg-prod-github-deploy"
    error_message = "The CRM stack's deploy role gets this image and these roles."
  }
}

run "audit_queue_does_not_allow_the_role" {
  command = plan

  override_data {
    target = data.terraform_remote_state.crm
    values = {
      outputs = {
        dashboard_inputs = {
          name_prefix    = "crm-sg-prod"
          vpc_id         = "vpc-0123456789abcdef0"
          app_subnet_ids = ["subnet-0aaaaaaaaaaaaaaaa", "subnet-0bbbbbbbbbbbbbbbb"]
          cluster_arn    = "arn:aws:ecs:ap-southeast-1:123456789012:cluster/crm-sg-prod"
          cluster_name   = "crm-sg-prod"
          namespace_arn  = "arn:aws:servicediscovery:ap-southeast-1:123456789012:namespace/ns-example"
          alb = {
            listener_arn   = "arn:aws:elasticloadbalancing:ap-southeast-1:123456789012:listener/app/crm-sg-prod/abc/def"
            alb_arn_suffix = "app/crm-sg-prod/abc"
            vpc_id         = "vpc-0123456789abcdef0"
          }
          alb_sg = "sg-0a1b"
          upstreams = {
            user    = { security_group = "sg-0user", port = 8081, url = "http://user-service:8081" }
            client  = { security_group = "sg-0client", port = 8080, url = "https://client-service:8080" }
            account = { security_group = "sg-0account", port = 8083, url = "https://account-service:8083" }
          }
          ca_pem                   = "-----BEGIN CERTIFICATE-----\nexample\n-----END CERTIFICATE-----"
          cognito_issuer           = "https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_example"
          cognito_web_client_id    = "exampleclientid"
          audit_queue_url          = "https://sqs.ap-southeast-1.amazonaws.com/123456789012/crm-sg-prod-audit-insert"
          audit_queue_arn          = "arn:aws:sqs:ap-southeast-1:123456789012:crm-sg-prod-audit-insert"
          audit_producer_role_arns = ["arn:aws:iam::123456789012:role/crm-sg-prod-user-service-task"]
          min_tasks                = 1
          max_tasks                = 6
          use_spot                 = false
          log_retention_days       = 30
          cpu_architecture         = "X86_64"
          deletion_protection      = true
          github_deploy_role_name  = null
        }
      }
    }
  }

  expect_failures = [aws_security_group.dashboard]
}
