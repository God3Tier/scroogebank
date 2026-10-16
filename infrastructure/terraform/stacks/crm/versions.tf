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
    tags = local.tags
  }
}

# CloudFront's WAF web ACL must live in us-east-1.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"
  default_tags {
    tags = local.tags
  }
}

# Disaster-recovery region for AWS Backup copies (ADR-0023 decision 3).
provider "aws" {
  alias  = "dr"
  region = var.dr_region
  default_tags {
    tags = local.tags
  }
}
