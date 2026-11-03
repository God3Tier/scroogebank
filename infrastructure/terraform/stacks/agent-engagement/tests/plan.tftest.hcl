# Plans the stack against a mocked AWS provider and a stand-in for the CRM
# stack's state (no AWS account needed), and checks how it attaches to that
# stack and what it may do (ADR-0051, ADR-0053, ADR-0054). Run with:
#   terraform init -backend=false && terraform test

mock_provider "tls" {}
mock_provider "random" {}

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
  mock_data "aws_sesv2_email_identity" {
    defaults = { arn = "arn:aws:ses:ap-southeast-1:123456789012:identity/mail.sg.itsag3t6.com" }
  }
  mock_data "aws_sesv2_configuration_set" {
    defaults = { arn = "arn:aws:ses:ap-southeast-1:123456789012:configuration-set/crm-sg-prod-engagement" }
  }
}

variables {
  environment  = "prod"
  state_bucket = "crm-tfstate-123456789012"
  image_tag    = "abc1234"
}

# A running prod CRM stack with a custom domain that already allows the
# engagement role.
override_data {
  target = data.terraform_remote_state.crm
  values = {
    outputs = {
      engagement_inputs = {
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
        alb_sg                   = "sg-0a1b"
        client                   = { security_group = "sg-0client", port = 8080, url = "https://client-service:8080" }
        alb_https                = { url = "https://alb.sg.itsag3t6.com", port = 443 }
        ca_pem                   = "-----BEGIN CERTIFICATE-----\nexample\n-----END CERTIFICATE-----"
        cognito_issuer           = "https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_example"
        cognito_web_client_id    = "exampleclientid"
        cognito_user_pool_id     = "ap-southeast-1_example"
        cognito_user_pool_arn    = "arn:aws:cognito-idp:ap-southeast-1:123456789012:userpool/ap-southeast-1_example"
        audit_queue_url          = "https://sqs.ap-southeast-1.amazonaws.com/123456789012/crm-sg-prod-audit-insert"
        audit_queue_arn          = "arn:aws:sqs:ap-southeast-1:123456789012:crm-sg-prod-audit-insert"
        audit_producer_role_arns = ["arn:aws:iam::123456789012:role/crm-sg-prod-dashboard-service-task", "arn:aws:iam::123456789012:role/crm-sg-prod-engagement-service-task"]
        db = {
          kms_key_arn           = "arn:aws:kms:ap-southeast-1:123456789012:key/example"
          subnet_group_name     = "crm-sg-prod-db"
          parameter_group_name  = "crm-sg-prod-postgres17"
          instance_class        = "db.t4g.micro"
          allocated_storage     = 20
          max_allocated_storage = 100
          backup_retention_days = 7
          deletion_protection   = true
          skip_final_snapshot   = false
          backup_tag            = true
        }
        db_migrate_image        = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/crm-sg-prod/db-migrate:abc1234"
        app_host                = "sg.itsag3t6.com"
        app_url                 = "https://sg.itsag3t6.com"
        alarm_topic_arn         = "arn:aws:sns:ap-southeast-1:123456789012:crm-sg-prod-alarms"
        min_tasks               = 2
        max_tasks               = 6
        use_spot                = false
        log_retention_days      = 30
        cpu_architecture        = "X86_64"
        deletion_protection     = true
        github_deploy_role_name = null
      }
    }
  }
}

run "prod" {
  command = plan

  assert {
    condition     = output.task_role_arn == "arn:aws:iam::123456789012:role/crm-sg-prod-engagement-service-task"
    error_message = "The predicted task role is the name the ecs-service module gives it."
  }
  assert {
    condition     = aws_ecr_repository.engagement.name == "crm-sg-prod/engagement-service" && aws_ecr_repository.engagement.image_tag_mutability == "IMMUTABLE" && aws_ecr_repository.engagement.force_delete == false
    error_message = "The repository keeps the CRM stack's name, immutable tags and, with deletion protection on, its images."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.from_alb.referenced_security_group_id == "sg-0a1b" && aws_vpc_security_group_ingress_rule.from_alb.from_port == 8086
    error_message = "Only the CRM stack's internal ALB reaches the service, on 8086."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.to_client_service.security_group_id == "sg-0client" && aws_vpc_security_group_ingress_rule.to_client_service.from_port == 8080
    error_message = "The service may call client-service on its port."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.to_alb[0].security_group_id == "sg-0a1b" && aws_vpc_security_group_ingress_rule.to_alb[0].from_port == 443
    error_message = "With a custom domain, engagement-service may reach the internal ALB for Client Health and alerts (ADR-0068)."
  }
  assert {
    condition     = module.engagement_service.app_environment.CLIENT_HEALTH_BASE_URL == "https://alb.sg.itsag3t6.com" && module.engagement_service.app_environment.ALERT_SERVICE_BASE_URL == "https://alb.sg.itsag3t6.com"
    error_message = "Client Health and alerts are read through the internal ALB over HTTPS (ADR-0068)."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.db_from_engagement.from_port == 5432
    error_message = "Only the service reaches engagement-db, on 5432."
  }
  assert {
    condition     = module.db.instance_class == "db.t4g.micro" && module.db.multi_az == false
    error_message = "engagement-db is a single-AZ db.t4g.micro (ADR-0051 decision 1)."
  }
  assert {
    condition     = module.engagement_service.service_name == "engagement-service"
    error_message = "The service keeps its name, so the runbook finds it."
  }
  assert {
    condition = (
      module.engagement_service.app_environment.ENGAGEMENT_MAIL_PROVIDER == "ses" &&
      module.engagement_service.app_environment.SES_FROM_ADDRESS == "no-reply@mail.sg.itsag3t6.com" &&
      module.engagement_service.app_environment.SES_CONFIGURATION_SET == "crm-sg-prod-engagement" &&
      module.engagement_service.app_environment.ENGAGEMENT_MAIL_DOMAIN == "mail.sg.itsag3t6.com"
    )
    error_message = "The service sends through SES as the mail-identity stack's no-reply address and configuration set."
  }
  assert {
    condition     = module.engagement_service.app_environment.ENGAGEMENT_AI_ENABLED == "false" && module.engagement_service.app_environment.BEDROCK_MODEL_ID == "apac.amazon.nova-lite-v1:0"
    error_message = "AI drafting is off by default, with the Nova Lite Asia-Pacific profile (ADR-0067)."
  }
  assert {
    condition     = module.engagement_service.app_environment.ENGAGEMENT_HOLIDAY_SYNC_ENABLED == "true" && aws_cloudwatch_log_metric_filter.holiday_data.pattern == "?\"holiday_coverage_short\" ?\"holiday_sync_mismatch\""
    error_message = "The holiday sync runs in AWS, and its warnings raise an alarm (ADR-0057)."
  }
  assert {
    condition     = module.engagement_service.app_environment.SPRING_FLYWAY_ENABLED == "false" && module.engagement_service.app_environment.CLIENT_SERVICE_BASE_URL == "https://client-service:8080"
    error_message = "db-migrate applies the migrations, and client-service is reached over Service Connect."
  }
  assert {
    condition     = !contains(keys(module.engagement_service.app_secrets), "FLYWAY_PASSWORD")
    error_message = "The master password goes to db-migrate only, never to the app."
  }
  assert {
    condition     = aws_secretsmanager_secret.app_login.name == "crm-sg-prod/db/engagement/engagement_service"
    error_message = "The login secret follows the CRM's naming."
  }
  assert {
    condition     = length(aws_iam_role_policy.github_deploy) == 0
    error_message = "No deploy-role policy while the CRM stack has no deploy role."
  }
  assert {
    condition = toset(flatten([for s in data.aws_iam_policy_document.engagement.statement : tolist(s.resources) if s.sid == "InvokeDraftModel"])) == toset([
      "arn:aws:bedrock:ap-southeast-1:123456789012:inference-profile/apac.amazon.nova-lite-v1:0",
      "arn:aws:bedrock:ap-southeast-1::foundation-model/amazon.nova-lite-v1:0",
      "arn:aws:bedrock:ap-southeast-2::foundation-model/amazon.nova-lite-v1:0",
      "arn:aws:bedrock:ap-northeast-1::foundation-model/amazon.nova-lite-v1:0",
      "arn:aws:bedrock:ap-northeast-2::foundation-model/amazon.nova-lite-v1:0",
      "arn:aws:bedrock:ap-northeast-3::foundation-model/amazon.nova-lite-v1:0",
      "arn:aws:bedrock:ap-south-1::foundation-model/amazon.nova-lite-v1:0",
    ])
    error_message = "Bedrock is allowed on the Asia-Pacific profile and its model in the six regions ADR-0067 lists, nothing else."
  }
  assert {
    condition = alltrue([
      for s in data.aws_iam_policy_document.engagement.statement :
      s.sid != "SendEngagementMail" || (
        toset(s.resources) == toset(["arn:aws:ses:ap-southeast-1:123456789012:identity/*", "arn:aws:ses:ap-southeast-1:123456789012:configuration-set/crm-sg-prod-engagement"]) &&
        anytrue([for c in s.condition : c.variable == "ses:FromAddress" && toset(c.values) == toset(["no-reply@mail.sg.itsag3t6.com"])])
      )
    ])
    error_message = "SES sends to any identity in this account and region (sandbox recipients are identities), through the configuration set, only from the no-reply address (ADR-0053 decision 9)."
  }
  assert {
    condition     = !contains(flatten([for s in data.aws_iam_policy_document.engagement.statement : tolist(s.resources)]), "*")
    error_message = "No permission is granted on every resource."
  }
}

run "stopped_crm_stops_the_tasks" {
  command = plan

  override_data {
    target = data.terraform_remote_state.crm
    values = {
      outputs = {
        engagement_inputs = {
          name_prefix    = "crm-sg-prod"
          vpc_id         = "vpc-0123456789abcdef0"
          app_subnet_ids = ["subnet-0aaaaaaaaaaaaaaaa"]
          cluster_arn    = "arn:aws:ecs:ap-southeast-1:123456789012:cluster/crm-sg-prod"
          cluster_name   = "crm-sg-prod"
          namespace_arn  = "arn:aws:servicediscovery:ap-southeast-1:123456789012:namespace/ns-example"
          alb = {
            listener_arn   = "arn:aws:elasticloadbalancing:ap-southeast-1:123456789012:listener/app/crm-sg-prod/abc/def"
            alb_arn_suffix = "app/crm-sg-prod/abc"
            vpc_id         = "vpc-0123456789abcdef0"
          }
          alb_sg                   = "sg-0a1b"
          client                   = { security_group = "sg-0client", port = 8080, url = "https://client-service:8080" }
          ca_pem                   = "-----BEGIN CERTIFICATE-----\nexample\n-----END CERTIFICATE-----"
          cognito_issuer           = "https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_example"
          cognito_web_client_id    = "exampleclientid"
          cognito_user_pool_id     = "ap-southeast-1_example"
          cognito_user_pool_arn    = "arn:aws:cognito-idp:ap-southeast-1:123456789012:userpool/ap-southeast-1_example"
          audit_queue_url          = "https://sqs.ap-southeast-1.amazonaws.com/123456789012/crm-sg-prod-audit-insert"
          audit_queue_arn          = "arn:aws:sqs:ap-southeast-1:123456789012:crm-sg-prod-audit-insert"
          audit_producer_role_arns = ["arn:aws:iam::123456789012:role/crm-sg-prod-engagement-service-task"]
          db = {
            kms_key_arn           = "arn:aws:kms:ap-southeast-1:123456789012:key/example"
            subnet_group_name     = "crm-sg-prod-db"
            parameter_group_name  = "crm-sg-prod-postgres17"
            instance_class        = "db.t4g.micro"
            allocated_storage     = 20
            max_allocated_storage = 100
            backup_retention_days = 7
            deletion_protection   = false
            skip_final_snapshot   = true
            backup_tag            = false
          }
          db_migrate_image        = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/crm-sg-prod/db-migrate:abc1234"
          app_host                = "sg.itsag3t6.com"
          app_url                 = "https://sg.itsag3t6.com"
          alarm_topic_arn         = "arn:aws:sns:ap-southeast-1:123456789012:crm-sg-prod-alarms"
          min_tasks               = 0
          max_tasks               = 0
          use_spot                = false
          log_retention_days      = 30
          cpu_architecture        = "X86_64"
          deletion_protection     = false
          github_deploy_role_name = "crm-sg-prod-github-deploy"
        }
      }
    }
  }

  variables {
    ai_enabled   = true
    mail_enabled = false
  }

  assert {
    condition     = aws_ecr_repository.engagement.force_delete == true && aws_secretsmanager_secret.tls.recovery_window_in_days == 0
    error_message = "With deletion protection off, destroy empties the repository and deletes the secrets at once."
  }
  assert {
    condition     = module.engagement_service.app_environment.ENGAGEMENT_AI_ENABLED == "true" && module.engagement_service.app_environment.ENGAGEMENT_MAIL_PROVIDER == "none"
    error_message = "The switches reach the service."
  }
  assert {
    condition     = length(aws_iam_role_policy.github_deploy) == 1 && aws_iam_role_policy.github_deploy[0].role == "crm-sg-prod-github-deploy"
    error_message = "The CRM stack's deploy role gets this image and these roles."
  }
  assert {
    condition     = length(aws_vpc_security_group_ingress_rule.to_alb) == 0 && module.engagement_service.app_environment.CLIENT_HEALTH_BASE_URL == "" && module.engagement_service.app_environment.ALERT_SERVICE_BASE_URL == ""
    error_message = "A CRM state without alb_https opens nothing, and the follow-ups from Client Health and alerts fail closed (ADR-0068)."
  }
}

run "audit_queue_does_not_allow_the_role" {
  command = plan

  override_data {
    target = data.terraform_remote_state.crm
    values = {
      outputs = {
        engagement_inputs = {
          name_prefix    = "crm-sg-prod"
          vpc_id         = "vpc-0123456789abcdef0"
          app_subnet_ids = ["subnet-0aaaaaaaaaaaaaaaa"]
          cluster_arn    = "arn:aws:ecs:ap-southeast-1:123456789012:cluster/crm-sg-prod"
          cluster_name   = "crm-sg-prod"
          namespace_arn  = "arn:aws:servicediscovery:ap-southeast-1:123456789012:namespace/ns-example"
          alb = {
            listener_arn   = "arn:aws:elasticloadbalancing:ap-southeast-1:123456789012:listener/app/crm-sg-prod/abc/def"
            alb_arn_suffix = "app/crm-sg-prod/abc"
            vpc_id         = "vpc-0123456789abcdef0"
          }
          alb_sg                   = "sg-0a1b"
          client                   = { security_group = "sg-0client", port = 8080, url = "https://client-service:8080" }
          ca_pem                   = "-----BEGIN CERTIFICATE-----\nexample\n-----END CERTIFICATE-----"
          cognito_issuer           = "https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_example"
          cognito_web_client_id    = "exampleclientid"
          cognito_user_pool_id     = "ap-southeast-1_example"
          cognito_user_pool_arn    = "arn:aws:cognito-idp:ap-southeast-1:123456789012:userpool/ap-southeast-1_example"
          audit_queue_url          = "https://sqs.ap-southeast-1.amazonaws.com/123456789012/crm-sg-prod-audit-insert"
          audit_queue_arn          = "arn:aws:sqs:ap-southeast-1:123456789012:crm-sg-prod-audit-insert"
          audit_producer_role_arns = ["arn:aws:iam::123456789012:role/crm-sg-prod-dashboard-service-task"]
          db = {
            kms_key_arn           = "arn:aws:kms:ap-southeast-1:123456789012:key/example"
            subnet_group_name     = "crm-sg-prod-db"
            parameter_group_name  = "crm-sg-prod-postgres17"
            instance_class        = "db.t4g.micro"
            allocated_storage     = 20
            max_allocated_storage = 100
            backup_retention_days = 7
            deletion_protection   = true
            skip_final_snapshot   = false
            backup_tag            = true
          }
          db_migrate_image        = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/crm-sg-prod/db-migrate:abc1234"
          app_host                = "sg.itsag3t6.com"
          app_url                 = "https://sg.itsag3t6.com"
          alarm_topic_arn         = "arn:aws:sns:ap-southeast-1:123456789012:crm-sg-prod-alarms"
          min_tasks               = 2
          max_tasks               = 6
          use_spot                = false
          log_retention_days      = 30
          cpu_architecture        = "X86_64"
          deletion_protection     = true
          github_deploy_role_name = null
        }
      }
    }
  }

  expect_failures = [aws_security_group.engagement]
}

run "rejects_a_global_profile" {
  command = plan

  variables {
    bedrock_model_id = "global.amazon.nova-2-lite-v1:0"
  }

  expect_failures = [var.bedrock_model_id]
}

# A CRM stack without a custom domain: mail comes from sender_address (ADR-0055).
run "sender_address_without_a_domain" {
  command = plan

  override_data {
    target = data.terraform_remote_state.crm
    values = {
      outputs = {
        engagement_inputs = {
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
          alb_sg                   = "sg-0a1b"
          client                   = { security_group = "sg-0client", port = 8080, url = "https://client-service:8080" }
          ca_pem                   = "-----BEGIN CERTIFICATE-----\nexample\n-----END CERTIFICATE-----"
          cognito_issuer           = "https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_example"
          cognito_web_client_id    = "exampleclientid"
          cognito_user_pool_id     = "ap-southeast-1_example"
          cognito_user_pool_arn    = "arn:aws:cognito-idp:ap-southeast-1:123456789012:userpool/ap-southeast-1_example"
          audit_queue_url          = "https://sqs.ap-southeast-1.amazonaws.com/123456789012/crm-sg-prod-audit-insert"
          audit_queue_arn          = "arn:aws:sqs:ap-southeast-1:123456789012:crm-sg-prod-audit-insert"
          audit_producer_role_arns = ["arn:aws:iam::123456789012:role/crm-sg-prod-dashboard-service-task", "arn:aws:iam::123456789012:role/crm-sg-prod-engagement-service-task"]
          db = {
            kms_key_arn           = "arn:aws:kms:ap-southeast-1:123456789012:key/example"
            subnet_group_name     = "crm-sg-prod-db"
            parameter_group_name  = "crm-sg-prod-postgres17"
            instance_class        = "db.t4g.micro"
            allocated_storage     = 20
            max_allocated_storage = 100
            backup_retention_days = 7
            deletion_protection   = true
            skip_final_snapshot   = false
            backup_tag            = true
          }
          db_migrate_image        = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/crm-sg-prod/db-migrate:abc1234"
          app_host                = null
          app_url                 = "https://d111111abcdef8.cloudfront.net"
          alarm_topic_arn         = "arn:aws:sns:ap-southeast-1:123456789012:crm-sg-prod-alarms"
          min_tasks               = 2
          max_tasks               = 6
          use_spot                = false
          log_retention_days      = 30
          cpu_architecture        = "X86_64"
          deletion_protection     = true
          github_deploy_role_name = null
        }
      }
    }
  }

  override_data {
    target = data.aws_sesv2_email_identity.mail
    values = { arn = "arn:aws:ses:ap-southeast-1:123456789012:identity/synthetic.sender@example.test" }
  }

  variables {
    sender_address = "synthetic.sender@example.test"
  }

  assert {
    condition     = data.aws_sesv2_email_identity.mail.email_identity == "synthetic.sender@example.test"
    error_message = "The identity looked up is the sender address."
  }
  assert {
    condition = (
      module.engagement_service.app_environment.SES_FROM_ADDRESS == "synthetic.sender@example.test" &&
      module.engagement_service.app_environment.ENGAGEMENT_MAIL_DOMAIN == "example.test" &&
      module.engagement_service.app_environment.SES_CONFIGURATION_SET == "crm-sg-prod-engagement"
    )
    error_message = "The service sends from the sender address, and its domain is the calendar UID suffix."
  }
  assert {
    condition = alltrue([
      for s in data.aws_iam_policy_document.engagement.statement :
      s.sid != "SendEngagementMail" || (
        toset(s.resources) == toset(["arn:aws:ses:ap-southeast-1:123456789012:identity/*", "arn:aws:ses:ap-southeast-1:123456789012:configuration-set/crm-sg-prod-engagement"]) &&
        anytrue([for c in s.condition : c.variable == "ses:FromAddress" && toset(c.values) == toset(["synthetic.sender@example.test"])])
      )
    ])
    error_message = "SES sends through the configuration set only from the sender address."
  }
}

run "needs_a_sender_address_without_a_domain" {
  command = plan

  override_data {
    target = data.terraform_remote_state.crm
    values = {
      outputs = {
        engagement_inputs = {
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
          alb_sg                   = "sg-0a1b"
          client                   = { security_group = "sg-0client", port = 8080, url = "https://client-service:8080" }
          ca_pem                   = "-----BEGIN CERTIFICATE-----\nexample\n-----END CERTIFICATE-----"
          cognito_issuer           = "https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_example"
          cognito_web_client_id    = "exampleclientid"
          cognito_user_pool_id     = "ap-southeast-1_example"
          cognito_user_pool_arn    = "arn:aws:cognito-idp:ap-southeast-1:123456789012:userpool/ap-southeast-1_example"
          audit_queue_url          = "https://sqs.ap-southeast-1.amazonaws.com/123456789012/crm-sg-prod-audit-insert"
          audit_queue_arn          = "arn:aws:sqs:ap-southeast-1:123456789012:crm-sg-prod-audit-insert"
          audit_producer_role_arns = ["arn:aws:iam::123456789012:role/crm-sg-prod-dashboard-service-task", "arn:aws:iam::123456789012:role/crm-sg-prod-engagement-service-task"]
          db = {
            kms_key_arn           = "arn:aws:kms:ap-southeast-1:123456789012:key/example"
            subnet_group_name     = "crm-sg-prod-db"
            parameter_group_name  = "crm-sg-prod-postgres17"
            instance_class        = "db.t4g.micro"
            allocated_storage     = 20
            max_allocated_storage = 100
            backup_retention_days = 7
            deletion_protection   = true
            skip_final_snapshot   = false
            backup_tag            = true
          }
          db_migrate_image        = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/crm-sg-prod/db-migrate:abc1234"
          app_host                = null
          app_url                 = "https://d111111abcdef8.cloudfront.net"
          alarm_topic_arn         = "arn:aws:sns:ap-southeast-1:123456789012:crm-sg-prod-alarms"
          min_tasks               = 2
          max_tasks               = 6
          use_spot                = false
          log_retention_days      = 30
          cpu_architecture        = "X86_64"
          deletion_protection     = true
          github_deploy_role_name = null
        }
      }
    }
  }

  expect_failures = [data.aws_sesv2_email_identity.mail]
}
