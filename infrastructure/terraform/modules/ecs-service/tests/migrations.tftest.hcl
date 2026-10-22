# Plans the module against a mocked AWS provider (no AWS account needed) and
# checks the db-migrate container (ADR-0028 decision 3).
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
  name               = "account-service"
  name_prefix        = "crm-sg-prod"
  cluster_arn        = "arn:aws:ecs:ap-southeast-1:123456789012:cluster/crm-sg-prod"
  cluster_name       = "crm-sg-prod"
  subnet_ids         = ["subnet-0aaaaaaaaaaaaaaaa", "subnet-0bbbbbbbbbbbbbbbb"]
  security_group_ids = ["sg-0account"]
  image              = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/crm-sg-prod/account-service:abc1234"
  port               = 8083
  secrets = {
    DB_USER     = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:rds-accounts-AbCdEf:username::"
    DB_PASSWORD = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:rds-accounts-AbCdEf:password::"
  }
  secret_arns = ["arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:rds-accounts-AbCdEf"]
  tls = {
    mode   = "go"
    ca_pem = "-----BEGIN CERTIFICATE-----\nexample\n-----END CERTIFICATE-----"
  }
}

run "with_migrations" {
  command = plan

  variables {
    migrations = {
      image    = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/crm-sg-prod/db-migrate:abc1234"
      location = "accounts"
      url      = "jdbc:postgresql://accounts.example.internal:5432/accounts?sslmode=verify-full&sslrootcert=/tls/rds-ca.pem"
      secrets = {
        FLYWAY_USER     = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:rds-accounts-AbCdEf:username::"
        FLYWAY_PASSWORD = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:rds-accounts-AbCdEf:password::"
      }
    }
  }

  assert {
    condition     = join(",", jsondecode(aws_ecs_task_definition.this.container_definitions)[*].name) == "tls-init,db-migrate,account-service"
    error_message = "tls-init runs first, then db-migrate, then the app."
  }
  assert {
    condition = (
      jsondecode(aws_ecs_task_definition.this.container_definitions)[1].essential == false &&
      jsondecode(aws_ecs_task_definition.this.container_definitions)[1].dependsOn == [{ containerName = "tls-init", condition = "SUCCESS" }] &&
      jsondecode(aws_ecs_task_definition.this.container_definitions)[1].mountPoints[0].readOnly
    )
    error_message = "db-migrate runs once tls-init has written the RDS CA, which it reads but cannot change."
  }
  assert {
    condition = (
      contains(jsondecode(aws_ecs_task_definition.this.container_definitions)[1].environment, { name = "FLYWAY_LOCATIONS", value = "filesystem:/flyway/sql/accounts" }) &&
      join(",", sort(jsondecode(aws_ecs_task_definition.this.container_definitions)[1].secrets[*].name)) == "FLYWAY_PASSWORD,FLYWAY_USER"
    )
    error_message = "db-migrate applies only its own database's directory, with the credentials it is given."
  }
  assert {
    condition = (
      join(",", jsondecode(aws_ecs_task_definition.this.container_definitions)[2].dependsOn[*].containerName) == "tls-init,db-migrate" &&
      length([for s in jsondecode(aws_ecs_task_definition.this.container_definitions)[2].secrets : s if startswith(s.name, "FLYWAY_")]) == 0
    )
    error_message = "The app starts only after both init containers succeed, and never receives db-migrate's secrets."
  }
}

run "migration_settings" {
  command = plan

  variables {
    migrations = {
      image       = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/crm-sg-prod/db-migrate:abc1234"
      location    = "users"
      url         = "jdbc:postgresql://users.example.internal:5432/users?sslmode=verify-full&sslrootcert=/tls/rds-ca.pem"
      secrets     = {}
      environment = { FLYWAY_SQL_MIGRATION_SEPARATOR = "__" }
    }
  }

  assert {
    condition = (
      contains(jsondecode(aws_ecs_task_definition.this.container_definitions)[1].environment, { name = "FLYWAY_SQL_MIGRATION_SEPARATOR", value = "__" }) &&
      contains(jsondecode(aws_ecs_task_definition.this.container_definitions)[1].environment, { name = "FLYWAY_LOCATIONS", value = "filesystem:/flyway/sql/users" }) &&
      length([for e in jsondecode(aws_ecs_task_definition.this.container_definitions)[2].environment : e if startswith(e.name, "FLYWAY_")]) == 0
    )
    error_message = "db-migrate gets its other Flyway settings, such as a Java service's separator, and the app does not."
  }
}

run "migrations_need_tls" {
  command = plan

  variables {
    tls = null
    migrations = {
      image    = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/crm-sg-prod/db-migrate:abc1234"
      location = "accounts"
      url      = "jdbc:postgresql://accounts.example.internal:5432/accounts?sslmode=verify-full&sslrootcert=/tls/rds-ca.pem"
      secrets  = {}
    }
  }

  expect_failures = [var.migrations]
}

run "without_migrations" {
  command = plan

  assert {
    condition     = join(",", jsondecode(aws_ecs_task_definition.this.container_definitions)[*].name) == "tls-init,account-service"
    error_message = "Without migrations, the task has no db-migrate container."
  }
}
