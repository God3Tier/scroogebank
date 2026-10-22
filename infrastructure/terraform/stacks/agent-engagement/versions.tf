terraform {
  # 1.10 or newer for S3 native state locking (use_lockfile).
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  # Partial configuration: terraform init -backend-config=env/<env>.s3.tfbackend
  # (see env/*.s3.tfbackend.example and docs/runbooks/deploy-aws.md).
  backend "s3" {}
}

provider "aws" {
  region = var.aws_region
  default_tags {
    # The CRM stack's tags (ADR-0026), plus the stack's name.
    tags = {
      Project     = "cs301-crm"
      LegalEntity = var.legal_entity
      Environment = var.environment
      ManagedBy   = "terraform"
      Stack       = "agent-engagement"
    }
  }
}
