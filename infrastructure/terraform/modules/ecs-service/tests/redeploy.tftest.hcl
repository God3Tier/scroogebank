# Plans the module against a mocked AWS provider (no AWS account needed) and
# checks that starting or stopping the stack redeploys the service, so its
# tasks get every Service Connect name (ADR-0042 decision 4).
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
  name               = "client-service"
  name_prefix        = "crm-sg-prod"
  cluster_arn        = "arn:aws:ecs:ap-southeast-1:123456789012:cluster/crm-sg-prod"
  cluster_name       = "crm-sg-prod"
  subnet_ids         = ["subnet-0aaaaaaaaaaaaaaaa", "subnet-0bbbbbbbbbbbbbbbb"]
  security_group_ids = ["sg-0client"]
  image              = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/crm-sg-prod/client-service:abc1234"
  port               = 8082
}

run "stopped" {
  command = plan

  variables {
    min_tasks = 0
    max_tasks = 0
  }

  assert {
    condition     = aws_ecs_service.this[0].force_new_deployment && aws_ecs_service.this[0].triggers["min_tasks"] == "0"
    error_message = "A stopped service's redeploy trigger holds its minimum of 0 tasks."
  }
}

run "running" {
  command = plan

  assert {
    condition     = aws_ecs_service.this[0].force_new_deployment && aws_ecs_service.this[0].triggers["min_tasks"] == "1"
    error_message = "Starting the stack changes the trigger, which redeploys the service before its tasks start."
  }
}
