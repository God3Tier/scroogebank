# Checks how the instance starts the producer, against a mocked AWS provider
# (no AWS account needed). Run with: terraform init -backend=false && terraform test

mock_provider "aws" {
  mock_data "aws_availability_zones" {
    defaults = { names = ["ap-southeast-1a"] }
  }
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
  mock_resource "aws_ecr_repository" {
    defaults = { repository_url = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/core-banking-sftp-prod/producer" }
  }
}

variables {
  environment           = "prod"
  allowed_cidrs         = ["203.0.113.10/32"]
  crm_ingest_public_key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleOnly test"
  producer_image        = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/core-banking-sftp-prod/producer:test"
}

run "producer_reads_the_synced_accounts" {
  assert {
    condition     = output.producer_account_ids_file == "/etc/producer/account-ids"
    error_message = "The sync script finds the account file through this output."
  }
  assert {
    condition     = strcontains(aws_instance.sftp.user_data, "-e CRM_ACCOUNT_IDS_FILE=\"/etc/producer/account-ids\"")
    error_message = "The producer reads its accounts from the synced file (ADR-0045)."
  }
  assert {
    condition     = strcontains(aws_instance.sftp.user_data, "-v \"$(dirname \"/etc/producer/account-ids\"):$(dirname \"/etc/producer/account-ids\"):ro\"")
    error_message = "The file's directory is mounted read-only, so a sync that renames the file is seen."
  }
  assert {
    condition     = !strcontains(aws_instance.sftp.user_data, "CRM_ACCOUNT_IDS=")
    error_message = "No fixed account list is passed to the producer."
  }
}

run "producer_publishes_every_5_minutes_by_default" {
  assert {
    condition     = strcontains(aws_instance.sftp.user_data, "-e INTERVAL_SECONDS=\"300\"")
    error_message = "The producer writes a report file every 300 seconds unless publish_interval_seconds is set."
  }
}

run "producer_interval_comes_from_the_variable" {
  variables {
    publish_interval_seconds = 60
  }
  assert {
    condition     = strcontains(aws_instance.sftp.user_data, "-e INTERVAL_SECONDS=\"60\"")
    error_message = "publish_interval_seconds is passed to the producer as INTERVAL_SECONDS."
  }
}

run "producer_interval_must_be_positive" {
  command = plan
  variables {
    publish_interval_seconds = 0
  }
  expect_failures = [var.publish_interval_seconds]
}

run "producer_interval_must_be_whole_seconds" {
  command = plan
  variables {
    publish_interval_seconds = 1.5
  }
  expect_failures = [var.publish_interval_seconds]
}
