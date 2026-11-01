# The allowed-traffic chain (security-model "Perimeter", ADR-0019 decision 4):
# VPC Link -> ALB -> services; each service -> its own database on 5432;
# named callers -> the auditor and other services; the ALB also admits the
# ingest Lambda for /internal calls. Rules are separate resources so groups can
# refer to each other without cycles, and so the business-dashboard stack can
# add its own rules to the user, client, account and transaction groups
# (ADR-0047).

locals {
  ports = {
    user         = 8081
    client       = 8080
    account      = 8083
    auditor_http = 8080
    auditor_grpc = 50051
    transaction  = 8080
    alerts       = 8080
    health       = 8087
  }

  security_groups = {
    vpc_link      = "API Gateway VPC Link interfaces"
    alb           = "Internal ALB"
    user          = "user-service tasks"
    client        = "client-service tasks"
    account       = "account-service tasks"
    auditor       = "auditor tasks"
    chain_verify  = "chain-verify tasks"
    transaction   = "transaction-service tasks"
    alerts        = "event-alert-service tasks"
    client_health = "Client Health Monitor tasks"
    ingest        = "transaction-ingest Lambda"
    db_console    = "One-off database console tasks (runbook)"
    db_users      = "users-db"
    db_clients    = "clients-db"
    db_accounts   = "accounts-db"
    db_trans      = "transactions-db"
    db_audit      = "audit-db"
    db_alerts     = "alerts-db"
    db_health     = "health-db"
  }

  # to <- from on port
  sg_ingress = [
    { to = "alb", from = "vpc_link", port = local.alb_port },
    { to = "alb", from = "ingest", port = local.alb_port },
    { to = "alb", from = "alerts", port = local.alb_port }, # account directory, API and worker (ADR-0066)
    { to = "alb", from = "client_health", port = local.alb_port },

    { to = "user", from = "alb", port = local.ports.user },
    { to = "user", from = "client", port = local.ports.user }, # reassigning clients (ADR-0060)
    { to = "client", from = "alb", port = local.ports.client },
    { to = "client", from = "account", port = local.ports.client },
    { to = "account", from = "alb", port = local.ports.account },
    { to = "account", from = "client", port = local.ports.account },
    { to = "account", from = "transaction", port = local.ports.account }, # ownership checks (ADR-0064)
    { to = "auditor", from = "user", port = local.ports.auditor_http },
    { to = "auditor", from = "user", port = local.ports.auditor_grpc },
    # transaction-service checks each caller's token (ADR-0064); the
    # business-dashboard stack adds dashboard-service's rule. Client Health
    # reaches its separately scoped private history route through the ALB,
    # like the ingest Lambda (ADR-0019 decision 4).
    { to = "transaction", from = "alb", port = local.ports.transaction },
    { to = "alerts", from = "alb", port = local.ports.alerts },
    { to = "client_health", from = "alb", port = local.ports.health },

    { to = "db_users", from = "user", port = 5432 },
    { to = "db_clients", from = "client", port = 5432 },
    { to = "db_accounts", from = "account", port = 5432 },
    { to = "db_trans", from = "transaction", port = 5432 },
    { to = "db_trans", from = "ingest", port = 5432 },
    { to = "db_audit", from = "auditor", port = 5432 },
    { to = "db_audit", from = "chain_verify", port = 5432 },
    { to = "db_alerts", from = "alerts", port = 5432 },
    { to = "db_health", from = "client_health", port = 5432 },
    { to = "db_users", from = "db_console", port = 5432 },
    { to = "db_clients", from = "db_console", port = 5432 },
    { to = "db_accounts", from = "db_console", port = 5432 },
    { to = "db_trans", from = "db_console", port = 5432 },
    { to = "db_audit", from = "db_console", port = 5432 },
    { to = "db_alerts", from = "db_console", port = 5432 },
    { to = "db_health", from = "db_console", port = 5432 },
  ]

  # Groups whose members reach AWS APIs, Cognito's keys and the SFTP server
  # through the NAT gateway, and other tasks inside the VPC.
  sg_egress_anywhere = ["user", "client", "account", "auditor", "chain_verify", "transaction", "alerts", "client_health", "ingest", "db_console"]

  sg = { for k, v in aws_security_group.this : k => v.id }
}

resource "aws_security_group" "this" {
  for_each    = local.security_groups
  name        = "${local.name}-${replace(each.key, "_", "-")}"
  description = each.value
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${local.name}-${replace(each.key, "_", "-")}" }
}

resource "aws_vpc_security_group_ingress_rule" "this" {
  for_each                     = { for r in local.sg_ingress : "${r.to}-from-${r.from}-${r.port}" => r }
  security_group_id            = local.sg[each.value.to]
  referenced_security_group_id = local.sg[each.value.from]
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
  description                  = "${each.value.from} to ${each.value.to}"
}

resource "aws_vpc_security_group_egress_rule" "anywhere" {
  for_each          = toset(local.sg_egress_anywhere)
  security_group_id = local.sg[each.key]
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
  description       = "AWS APIs and Cognito through the NAT gateway, and other tasks"
}

resource "aws_vpc_security_group_egress_rule" "vpc_link_to_alb" {
  security_group_id            = local.sg["vpc_link"]
  referenced_security_group_id = local.sg["alb"]
  ip_protocol                  = "tcp"
  from_port                    = local.alb_port
  to_port                      = local.alb_port
  description                  = "VPC Link to the internal ALB"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_vpc" {
  security_group_id = local.sg["alb"]
  ip_protocol       = "tcp"
  from_port         = 1024
  to_port           = 65535
  cidr_ipv4         = aws_vpc.this.cidr_block
  description       = "ALB to service tasks"
}
