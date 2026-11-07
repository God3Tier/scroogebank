#!/usr/bin/env bash
# Runs chain-verify now rather than at its daily time (ADR-0022): it checks
# every entry of the audit hash chain and anchors the result in S3. Uses the
# task definition and network settings of its EventBridge Scheduler schedule,
# so it runs exactly as the daily check does, and writes only what that
# writes: its anchor, and any problem it finds.
#
# usage: tests/drills/chain-verify.sh <run-id>
# Writes its log to out/drills/<run-id>/chain-verify.log. Needs Python,
# terraform and the AWS CLI with credentials for the CRM account.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

run_id=${1:?"usage: $0 <run-id>"}
out="out/drills/$run_id"
mkdir -p "$out"
: > "$out/events.txt"
load_stack

params=$(aws scheduler get-schedule --name "$cluster-chain-verify" --query 'Target.EcsParameters' --output json)
field() { printf '%s' "$params" | "$py" -c "import json, sys; p = json.load(sys.stdin); print($1)"; }
task_definition=$(field 'p["TaskDefinitionArn"]')
network=$(field '"awsvpcConfiguration={subnets=[%s],securityGroups=[%s],assignPublicIp=DISABLED}" % (
  ",".join(p["NetworkConfiguration"]["awsvpcConfiguration"]["Subnets"]),
  ",".join(p["NetworkConfiguration"]["awsvpcConfiguration"]["SecurityGroups"]))')

task_arn=$(aws ecs run-task --cluster "$cluster" --launch-type FARGATE --task-definition "$task_definition" \
  --network-configuration "$network" --query 'tasks[0].taskArn' --output text)
event "chain-verify started (${task_arn##*/})"
# aws ecs wait gives up after 10 minutes; a long chain takes longer.
while [[ "$(aws ecs describe-tasks --cluster "$cluster" --tasks "$task_arn" \
  --query 'tasks[0].lastStatus' --output text)" != STOPPED ]]; do
  sleep 15
done
code=$(aws ecs describe-tasks --cluster "$cluster" --tasks "$task_arn" --query 'tasks[0].containers[?name==`chain-verify`].exitCode | [0]' --output text)
event "chain-verify stopped with exit code $code"

# Git Bash would turn the log group's leading / into a Windows path.
MSYS_NO_PATHCONV=1 aws logs get-log-events --log-group-name "/ecs/$cluster-chain-verify" \
  --log-stream-name "chain-verify/chain-verify/${task_arn##*/}" --start-from-head \
  --query 'events[].message' --output text | tr '\t' '\n' > "$out/chain-verify.log" || true
tail -20 "$out/chain-verify.log"
if grep -q CHAIN_TAMPER_DETECTED "$out/chain-verify.log"; then
  echo "chain-verify found a problem in the chain (CHAIN_TAMPER_DETECTED)" >&2
  exit 1
fi
[[ "$code" == 0 ]]
