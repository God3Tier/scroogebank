# The external core banking SFTP server (ADR-0024 decision 1): OpenSSH on a
# t4g.micro EC2 instance in its own stack and VPC, treated as a third-party
# system. It accepts SSH only from the CRM's NAT gateway addresses, with key
# authentication, a pinned host key and a chrooted SFTP-only account. The
# producer container writes synthetic report files into that account.

terraform {
  required_version = ">= 1.10.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  # terraform init -backend-config=env/<env>.s3.tfbackend
  backend "s3" {}
}

provider "aws" {
  region = var.aws_region
  default_tags {
    tags = {
      Project     = "cs301-crm"
      Environment = var.environment
      ManagedBy   = "terraform"
      Stack       = "sftp-server"
      Role        = "external-core-banking-simulation"
    }
  }
}

locals {
  name = "core-banking-sftp-${var.environment}"
  # The Free plan allows only free-tier instance types (ADR-0042).
  instance_type = var.free_plan ? "t4g.micro" : var.instance_type
  remote_dir    = "/srv/sftp/${var.sftp_user}/reports"
}

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_ssm_parameter" "al2023_arm64" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

# ------------------------------------------------------------------ network

resource "aws_vpc" "this" {
  cidr_block           = "10.50.0.0/24"
  enable_dns_hostnames = true
  tags                 = { Name = local.name }
}

resource "aws_default_security_group" "this" {
  vpc_id = aws_vpc.this.id
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = local.name }
}

resource "aws_subnet" "public" {
  vpc_id            = aws_vpc.this.id
  cidr_block        = aws_vpc.this.cidr_block
  availability_zone = data.aws_availability_zones.available.names[0]
  tags              = { Name = local.name }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }
  tags = { Name = local.name }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

resource "aws_security_group" "sftp" {
  name        = local.name
  description = "SFTP from the CRM's NAT gateway addresses only"
  vpc_id      = aws_vpc.this.id
}

resource "aws_vpc_security_group_ingress_rule" "sftp" {
  for_each          = toset(var.allowed_cidrs)
  security_group_id = aws_security_group.sftp.id
  ip_protocol       = "tcp"
  from_port         = 22
  to_port           = 22
  cidr_ipv4         = each.value
  description       = "CRM NAT gateway"
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.sftp.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
  description       = "Package updates, SSM and ECR"
}

# ----------------------------------------------------------------- host key
# Generated here so the CRM can pin its fingerprint. The private key is in
# SSM Parameter Store (SecureString) and in this stack's state.

resource "tls_private_key" "host" {
  algorithm = "ED25519"
}

resource "aws_ssm_parameter" "host_key" {
  name        = "/${local.name}/ssh-host-key"
  description = "SSH host private key of the simulated core banking server"
  type        = "SecureString"
  value       = tls_private_key.host.private_key_openssh
}

# --------------------------------------------------------------- producer

resource "aws_ecr_repository" "producer" {
  name                 = "${local.name}/producer"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = true

  image_scanning_configuration {
    scan_on_push = true
  }
}

# ----------------------------------------------------------------- instance

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "instance" {
  name               = local.name
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

# Session Manager instead of an open admin SSH port.
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy_attachment" "ecr" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
}

data "aws_iam_policy_document" "host_key" {
  statement {
    actions   = ["ssm:GetParameter"]
    resources = [aws_ssm_parameter.host_key.arn]
  }
}

resource "aws_iam_role_policy" "host_key" {
  name   = "read-host-key"
  role   = aws_iam_role.instance.id
  policy = data.aws_iam_policy_document.host_key.json
}

resource "aws_iam_instance_profile" "this" {
  name = local.name
  role = aws_iam_role.instance.name
}

resource "aws_instance" "sftp" {
  ami                    = data.aws_ssm_parameter.al2023_arm64.value
  instance_type          = local.instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.sftp.id]
  iam_instance_profile   = aws_iam_instance_profile.this.name
  monitoring             = false
  # First boot needs the internet (SSM, packages) before the Elastic IP is
  # attached; the Elastic IP then replaces this address.
  associate_public_ip_address = true

  metadata_options {
    http_tokens   = "required"
    http_endpoint = "enabled"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 8
    encrypted   = true
  }

  user_data_replace_on_change = true
  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    region         = var.aws_region
    host_key_param = aws_ssm_parameter.host_key.name
    sftp_user      = var.sftp_user
    authorized_key = trimspace(var.crm_ingest_public_key)
    remote_dir     = local.remote_dir
    producer_image = var.producer_image == null ? "" : var.producer_image
    registry       = split("/", aws_ecr_repository.producer.repository_url)[0]
    interval       = var.publish_interval_seconds
    account_ids    = join(",", var.crm_account_ids)
  })

  tags = { Name = local.name }

  lifecycle {
    ignore_changes = [ami] # take a new AMI deliberately, not on every plan
  }
}

# A fixed address for the CRM to connect to.
resource "aws_eip" "sftp" {
  domain   = "vpc"
  instance = aws_instance.sftp.id
  tags     = { Name = local.name }
}
