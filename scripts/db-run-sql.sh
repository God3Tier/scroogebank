#!/usr/bin/env bash
# Runs a SQL file against one of the CRM stack's databases from inside the
# VPC, as that database's master user, with a one-off psql task (the
# db-console task definitions in infrastructure/terraform/stacks/crm/data.tf).
# For ad-hoc SQL and scripts/sync-producer-accounts.sh; the schemas come from
# each service's db-migrate container (ADR-0028 decision 3). Needs the stack
# running (the task pulls its image through the NAT gateway), terraform, the
# AWS CLI and Python.
#
# usage: scripts/db-run-sql.sh <database> <sql-file> [crm-stack-dir]
#   <database>    users, clients, accounts, transactions, audit or alerts
set -euo pipefail

usage="usage: $0 <database> <sql-file> [crm-stack-dir]"
db=${1:?$usage}
file=${2:?$usage}
crm_dir=${3:-infrastructure/terraform/stacks/crm}
[[ -f "$file" ]] || { echo "no such file: $file" >&2; exit 2; }

# Pick the first interpreter that really runs; the Windows Store stubs for python/python3 exist but fail.
py=
for candidate in python3 python py; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys' >/dev/null 2>&1; then
    py=$candidate
    break
  fi
done
[[ -n "$py" ]] || { echo "no working Python found (tried python3, python, py)" >&2; exit 2; }

# Everything the run needs, from the stack's outputs and the SQL file.
plan=$(terraform -chdir="$crm_dir" output -json | "$py" -c '
import json, re, sys
db, path = sys.argv[1], sys.argv[2]
out = json.load(sys.stdin)
console = out["db_console"]["value"]
if db not in console["task_families"]:
    sys.exit("unknown database %r; choose one of %s" % (db, ", ".join(sorted(console["task_families"]))))
family = console["task_families"][db]
sql = "\n".join(l for l in open(path, encoding="utf-8").read().splitlines() if not re.match(r"\s*--", l))
overrides = {"containerOverrides": [{"name": "psql", "command": [
    "sh", "-c", "psql -v ON_ERROR_STOP=1 <<\x27SQL\x27\n%s\nSQL\n" % sql]}]}
encoded = json.dumps(overrides)
if len(encoded) > 8000:
    sys.exit("the SQL is too long for one run-task override (%d characters); split the file" % len(encoded))
print(json.dumps({
    "cluster": out["ecs_cluster"]["value"],
    "family": family,
    "log_group": "/ecs/" + family[: family.rindex("-db-console-")] + "-db-console",
    "network": "awsvpcConfiguration={subnets=[%s],securityGroups=[%s],assignPublicIp=DISABLED}"
               % (",".join(out["app_subnet_ids"]["value"]), console["security_group"]),
    "overrides": encoded,
}))
' "$db" "$file")

get() { printf '%s' "$plan" | "$py" -c "import json,sys; print(json.load(sys.stdin)['$1'])"; }
cluster=$(get cluster)
log_group=$(get log_group)

task_arn=$(aws ecs run-task --cluster "$cluster" --launch-type FARGATE \
  --task-definition "$(get family)" \
  --network-configuration "$(get network)" \
  --overrides "$(get overrides)" \
  --query 'tasks[0].taskArn' --output text)
echo "running $file against $db ($task_arn)"

aws ecs wait tasks-stopped --cluster "$cluster" --tasks "$task_arn"
exit_code=$(aws ecs describe-tasks --cluster "$cluster" --tasks "$task_arn" \
  --query 'tasks[0].containers[0].exitCode' --output text)

# Git Bash would turn the log group's leading / into a Windows path.
MSYS_NO_PATHCONV=1 aws logs get-log-events --log-group-name "$log_group" \
  --log-stream-name "$db/psql/${task_arn##*/}" \
  --query 'events[].message' --output text 2>/dev/null || true

if [[ "$exit_code" != "0" ]]; then
  echo "psql exited with $exit_code" >&2
  exit 1
fi
echo "done"
