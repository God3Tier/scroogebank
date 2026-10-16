# CRM VPC (system-overview "Network layout"): public subnets hold only the NAT
# gateways, private app subnets hold ECS tasks, Lambda and VPC Link interfaces,
# and isolated subnets hold RDS with no route to the internet.

resource "aws_vpc" "this" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true
  tags                 = { Name = local.name }
}

# No rules: nothing may use the default security group.
resource "aws_default_security_group" "this" {
  vpc_id = aws_vpc.this.id
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = local.name }
}

resource "aws_subnet" "public" {
  count             = 2
  vpc_id            = aws_vpc.this.id
  availability_zone = local.azs[count.index]
  cidr_block        = cidrsubnet(aws_vpc.this.cidr_block, 8, count.index) # 10.0.0.0/24, 10.0.1.0/24
  tags              = { Name = "${local.name}-public-${local.azs[count.index]}", Tier = "public" }
}

resource "aws_subnet" "app" {
  count             = 2
  vpc_id            = aws_vpc.this.id
  availability_zone = local.azs[count.index]
  cidr_block        = cidrsubnet(aws_vpc.this.cidr_block, 8, 10 + count.index) # 10.0.10.0/24, 10.0.11.0/24
  tags              = { Name = "${local.name}-app-${local.azs[count.index]}", Tier = "app" }
}

resource "aws_subnet" "db" {
  count             = 2
  vpc_id            = aws_vpc.this.id
  availability_zone = local.azs[count.index]
  cidr_block        = cidrsubnet(aws_vpc.this.cidr_block, 8, 20 + count.index) # 10.0.20.0/24, 10.0.21.0/24
  tags              = { Name = "${local.name}-db-${local.azs[count.index]}", Tier = "db" }
}

# ---------------------------------------------------------------- routing

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${local.name}-public" }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# One NAT gateway normally; one per AZ in the high-availability week; none
# while the stack is stopped. The Elastic IPs outlive the gateways: the first
# is the address the SFTP server allows in, so it is kept for the stack's
# whole life and a recreated gateway reuses it (ADR-0029, ADR-0042).
resource "aws_eip" "nat" {
  count  = local.nat_eip_count
  domain = "vpc"
  tags   = { Name = "${local.name}-nat-${local.azs[count.index]}" }
}

resource "aws_nat_gateway" "this" {
  count         = local.nat_gateway_count
  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id
  tags          = { Name = "${local.name}-${local.azs[count.index]}" }

  depends_on = [aws_internet_gateway.this]
}

resource "aws_route_table" "app" {
  count  = 2
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${local.name}-app-${local.azs[count.index]}" }
}

resource "aws_route" "app_nat" {
  count                  = local.nat_gateway_count > 0 ? 2 : 0
  route_table_id         = aws_route_table.app[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[min(count.index, local.nat_gateway_count - 1)].id
}

resource "aws_route_table_association" "app" {
  count          = 2
  subnet_id      = aws_subnet.app[count.index].id
  route_table_id = aws_route_table.app[count.index].id
}

# Isolated: local routes only.
resource "aws_route_table" "db" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${local.name}-db" }
}

resource "aws_route_table_association" "db" {
  count          = 2
  subnet_id      = aws_subnet.db[count.index].id
  route_table_id = aws_route_table.db.id
}

# Free gateway endpoints keep S3 (including ECR image layers) and DynamoDB
# traffic off the NAT gateway. The ID-documents bucket refuses requests that
# do not come through the S3 endpoint (ADR-0041 decision 9).
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${local.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.app[*].id
  tags              = { Name = "${local.name}-s3" }
}

resource "aws_vpc_endpoint" "dynamodb" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${local.region}.dynamodb"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.app[*].id
  tags              = { Name = "${local.name}-dynamodb" }
}

resource "aws_db_subnet_group" "this" {
  name       = local.name
  subnet_ids = aws_subnet.db[*].id
}
