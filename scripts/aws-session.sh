#!/usr/bin/env bash
# Stop or start what Terraform cannot pause between sessions (ADR-0029 stop
# model): the CRM's RDS instances, engagement-db when the agent-engagement
# stack exists (ADR-0051), and the simulated SFTP server's instance.
# ECS tasks, the schedules and the NAT gateways follow the CRM stack's
# `running` variable instead. The business-dashboard stack reads it from the
# CRM stack's state, so apply that stack after the CRM stack (ADR-0047), and
# the agent-engagement stack the same way:
#
#   stop:   terraform apply -var-file=env/prod.tfvars -var running=false   (crm)
#           terraform apply -var-file=env/prod.tfvars                      (business-dashboard)
#           terraform apply -var-file=env/prod.tfvars                      (agent-engagement)
#           scripts/aws-session.sh stop
#   start:  scripts/aws-session.sh start
#           terraform apply -var-file=env/prod.tfvars -var running=true    (crm)
#           terraform apply -var-file=env/prod.tfvars                      (business-dashboard)
#           terraform apply -var-file=env/prod.tfvars                      (agent-engagement)
#
# usage: scripts/aws-session.sh stop|start [crm-stack-dir] [sftp-stack-dir] [engagement-stack-dir]
# Needs terraform and the AWS CLI, signed in to the stack's account.
set -euo pipefail

action=${1:-}
crm_dir=${2:-infrastructure/terraform/stacks/crm}
sftp_dir=${3:-infrastructure/terraform/stacks/sftp-server}
engagement_dir=${4:-infrastructure/terraform/stacks/agent-engagement}

if [[ "$action" != "stop" && "$action" != "start" ]]; then
  echo "usage: $0 stop|start [crm-stack-dir] [sftp-stack-dir] [engagement-stack-dir]" >&2
  exit 2
fi

dbs=$(terraform -chdir="$crm_dir" output -json db_instance_identifiers | tr -d '[]"\r\n ' | tr ',' ' ')

# engagement-db is in its own stack; skipped when that stack was never applied.
if [[ -d "$engagement_dir" ]] && engagement_db=$(terraform -chdir="$engagement_dir" output -raw db_instance_identifier 2>/dev/null) \
    && [[ -n "$engagement_db" ]]; then
  dbs="$dbs ${engagement_db//$'\r'/}"
fi

for db in $dbs; do
  status=$(aws rds describe-db-instances --db-instance-identifier "$db" \
    --query 'DBInstances[0].DBInstanceStatus' --output text)
  if [[ "$action" == "stop" && "$status" == "available" ]]; then
    aws rds stop-db-instance --db-instance-identifier "$db" >/dev/null
    echo "stopping $db"
  elif [[ "$action" == "start" && "$status" == "stopped" ]]; then
    aws rds start-db-instance --db-instance-identifier "$db" >/dev/null
    echo "starting $db"
  else
    echo "$db is $status; left as it is"
  fi
done

if [[ -d "$sftp_dir" ]] && instance=$(terraform -chdir="$sftp_dir" output -raw instance_id 2>/dev/null); then
  aws ec2 "${action}-instances" --instance-ids "$instance" >/dev/null
  echo "${action}: SFTP server $instance"
fi

if [[ "$action" == "start" ]]; then
  echo "waiting for the databases to become available..."
  for db in $dbs; do
    aws rds wait db-instance-available --db-instance-identifier "$db"
  done
  echo "databases are up; now apply with -var running=true"
else
  echo "RDS starts a stopped instance again after 7 days; run this again if the break is longer."
fi
