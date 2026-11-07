#!/usr/bin/env bash
# Runs scan.sh in the test-only ZAP scanner task inside the VPC (ADR-0071), so
# ZAP reaches the services through the internal ALB directly, past
# CloudFront, the WAF and API Gateway's JWT check. Needs the crm stack applied
# with enable_test_tooling = true and running, terraform, the AWS CLI and
# Python, and credentials for the CRM account.
#
# usage: tests/security/zap/run-in-vpc.sh <run-id> <specs-dir> [token-file] [crm-stack-dir]
#   SCAN=active|passive (default active) and ZAP_OPTIONS pass through to scan.sh.
# Reports land in out/zap/<run-id>/. The run's inputs, the token included, are
# deleted from the bucket when the task stops.
set -euo pipefail

usage="usage: $0 <run-id> <specs-dir> [token-file] [crm-stack-dir]"
run_id=${1:?$usage}
specs=${2:?$usage}
token_file=${3:-}
crm_dir=${4:-infrastructure/terraform/stacks/crm}
here=$(cd "$(dirname "$0")" && pwd)

py=
for candidate in python3 python py; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys' >/dev/null 2>&1; then
    py=$candidate
    break
  fi
done
[[ -n "$py" ]] || { echo "no working Python found (tried python3, python, py)" >&2; exit 2; }

tooling=$(terraform -chdir="$crm_dir" output -json test_tooling)
get() { printf '%s' "$tooling" | "$py" -c "import json,sys; v=json.load(sys.stdin); print(v['$1'] if v else '')"; }
bucket=$(get bucket)
[[ -n "$bucket" ]] || { echo "no scanner: apply the crm stack with enable_test_tooling = true" >&2; exit 2; }
cluster=$(get cluster)
log_group=$(get log_group)
network=$(printf '%s' "$tooling" | "$py" -c '
import json, sys
v = json.load(sys.stdin)
print("awsvpcConfiguration={subnets=[%s],securityGroups=[%s],assignPublicIp=DISABLED}" % (",".join(v["subnets"]), v["security_group"]))')

inputs="s3://$bucket/in/$run_id"
aws s3 cp "$specs" "$inputs/specs/" --recursive --only-show-errors
# The task's sh cannot run a script with Windows line endings, which a
# checkout with core.autocrlf gives it.
tr -d '\r' < "$here/scan.sh" | aws s3 cp - "$inputs/scan.sh" --only-show-errors
[[ -n "$token_file" ]] && aws s3 cp "$token_file" "$inputs/token" --only-show-errors
trap 'aws s3 rm "$inputs/" --recursive --only-show-errors' EXIT

overrides=$(RUN_ID="$run_id" SCAN="${SCAN:-active}" ZAP_OPTIONS="${ZAP_OPTIONS:-}" "$py" -c '
import json, os
run = {"name": "RUN_ID", "value": os.environ["RUN_ID"]}
zap = [run, {"name": "SCAN", "value": os.environ["SCAN"]}, {"name": "ZAP_OPTIONS", "value": os.environ["ZAP_OPTIONS"]}]
print(json.dumps({"containerOverrides": [
    {"name": "fetch", "environment": [run]},
    {"name": "zap", "environment": zap},
    {"name": "upload", "environment": [run]},
]}))')

task_arn=$(aws ecs run-task --cluster "$cluster" --launch-type FARGATE \
  --task-definition "$(get task_family)" --network-configuration "$network" \
  --overrides "$overrides" --query 'tasks[0].taskArn' --output text)
echo "scanning $(get target) as run $run_id (${task_arn##*/})"

# aws ecs wait gives up after 10 minutes; a scan takes longer.
while [[ "$(aws ecs describe-tasks --cluster "$cluster" --tasks "$task_arn" \
  --query 'tasks[0].lastStatus' --output text)" != STOPPED ]]; do
  sleep 30
done
aws ecs describe-tasks --cluster "$cluster" --tasks "$task_arn" \
  --query 'tasks[0].containers[].[name,exitCode,reason]' --output text

mkdir -p "out/zap/$run_id"
aws s3 cp "s3://$bucket/reports/$run_id/" "out/zap/$run_id/" --recursive --only-show-errors
# Git Bash would turn the log group's leading / into a Windows path.
MSYS_NO_PATHCONV=1 aws logs get-log-events --log-group-name "$log_group" \
  --log-stream-name "zap/zap/${task_arn##*/}" --start-from-head \
  --query 'events[].message' --output text > "out/zap/$run_id/zap.log" 2>/dev/null || true
grep -E 'scanning|finished|FAIL|WARN-NEW' "out/zap/$run_id/zap.log" | tail -40 || true
echo "reports in out/zap/$run_id/"
