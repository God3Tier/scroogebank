# Checks host_key_file against a mocked AWS provider (no AWS account needed);
# the tls provider is real, so the keys are really parsed. tests/setup writes
# throwaway keys first. Run with: terraform init -backend=false && terraform test

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
}

run "setup" {
  module {
    source = "./tests/setup"
  }
}

run "generated_key" {
  assert {
    condition     = length(tls_private_key.host) == 1
    error_message = "Without host_key_file, Terraform generates the host key."
  }
  assert {
    condition     = nonsensitive(aws_ssm_parameter.host_key.value == tls_private_key.host[0].private_key_openssh)
    error_message = "The SSM parameter holds the generated key."
  }
  assert {
    condition     = output.sftp_host_key_fingerprint == tls_private_key.host[0].public_key_fingerprint_sha256
    error_message = "The fingerprint output is the generated key's."
  }
  assert {
    condition     = strcontains(aws_instance.sftp.user_data, "HostKey /etc/ssh/ssh_host_ed25519_key\n")
    error_message = "A generated key keeps the ED25519 path, so an existing instance is not replaced."
  }
}

run "given_ed25519_key" {
  command = plan

  variables {
    host_key_file = run.setup.ed25519_file
  }

  assert {
    condition     = length(tls_private_key.host) == 0
    error_message = "With host_key_file, no key is generated."
  }
  assert {
    condition     = nonsensitive(aws_ssm_parameter.host_key.value == run.setup.ed25519_key)
    error_message = "The SSM parameter holds the given key unchanged."
  }
  assert {
    condition     = output.sftp_host_key_fingerprint == run.setup.ed25519_fingerprint
    error_message = "The fingerprint output is the given key's."
  }
  assert {
    condition     = strcontains(aws_instance.sftp.user_data, "HostKey /etc/ssh/ssh_host_ed25519_key\n")
    error_message = "An ED25519 key goes to ssh_host_ed25519_key."
  }
}

run "given_rsa_key_with_crlf" {
  command = plan

  variables {
    host_key_file = run.setup.rsa_crlf_file
  }

  assert {
    condition     = nonsensitive(aws_ssm_parameter.host_key.value == run.setup.rsa_key)
    error_message = "CRLF line endings become LF."
  }
  assert {
    condition     = output.sftp_host_key_fingerprint == run.setup.rsa_fingerprint
    error_message = "The fingerprint output is the given key's."
  }
  assert {
    condition     = strcontains(aws_instance.sftp.user_data, "HostKey /etc/ssh/ssh_host_rsa_key\n") && !strcontains(aws_instance.sftp.user_data, "ssh_host_ed25519")
    error_message = "An RSA key goes to ssh_host_rsa_key, in every line of the startup script."
  }
}

run "missing_key_file" {
  command = plan

  variables {
    host_key_file = ".terraform/test-host-keys/does-not-exist"
  }

  expect_failures = [var.host_key_file]
}
