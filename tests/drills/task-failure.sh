#!/usr/bin/env bash
# Availability drill 1, task failure (NFR 2): stops one running task of a
# service while the probe requests one of its routes once a second, and
# measures what users saw while ECS replaced the task. With two tasks per
# service (ADR-0075) the other task should answer everything; with one, the
# outage is the time to start a replacement.
#
# usage: tests/drills/task-failure.sh <run-id> <store> [service]
#   <store>    the store tests/test-users.py create wrote
#   service    the ECS service (default client-service). The probe lists
#              clients; for another service, set PROBE_PATH to one of its routes.
# Writes out/drills/<run-id>/: probe.csv (the evidence), report.txt and
# events.txt. Needs Docker, Python, terraform and the AWS CLI with credentials
# for the CRM account. lib.sh lists the other settings.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

usage="usage: $0 <run-id> <store> [service]"
run_id=${1:?$usage}
store=${2:?$usage}
service=${3:-client-service}
out="out/drills/$run_id"
mkdir -p "$out"
: > "$out/events.txt"

load_stack
if [[ "$service" != client-service && -z "${PROBE_PATH:-}" ]]; then
  echo "the probe lists clients, which $service does not serve; set PROBE_PATH to one of its routes" >&2
  exit 2
fi
desired=$(aws ecs describe-services --cluster "$cluster" --services "$service" --query 'services[0].desiredCount' --output text)
target_group=$(aws ecs describe-services --cluster "$cluster" --services "$service" \
  --query 'services[0].loadBalancers[0].targetGroupArn' --output text)
confirm "stop one of $service's $desired running tasks"

token="$out/token"
trap 'probe_stop; rm -f "$token"' EXIT
drill_token "$store" "$token"
probe_start "$token"
sleep 30

task=$(aws ecs list-tasks --cluster "$cluster" --service-name "$service" --desired-status RUNNING \
  --query 'taskArns[0]' --output text)
aws ecs stop-task --cluster "$cluster" --task "$task" --reason "availability drill $run_id" >/dev/null
event "stopped task ${task##*/} of $service ($desired running)"

# Wait, at most 10 minutes, for a replacement to run and pass the load balancer's health check.
replaced=
healthy=
for _ in $(seq 1 120); do
  sleep 5
  running=$(aws ecs list-tasks --cluster "$cluster" --service-name "$service" --desired-status RUNNING \
    --query 'taskArns' --output text)
  if [[ -z "$replaced" && "$running" != *"${task##*/}"* ]] && (( $(wc -w <<< "$running") >= desired )); then
    replaced=1
    event "replacement task started"
  fi
  if [[ -n "$replaced" && "$target_group" != None ]]; then
    count=$(aws elbv2 describe-target-health --target-group-arn "$target_group" \
      --query 'length(TargetHealthDescriptions[?TargetHealth.State==`healthy`])' --output text)
    if (( count >= desired )); then
      healthy=1
      event "$count healthy targets behind the load balancer"
      break
    fi
  fi
  [[ -n "$replaced" && "$target_group" == None ]] && break
done
[[ -n "$replaced" ]] || event "no replacement within 10 minutes"
[[ -n "$healthy" || "$target_group" == None ]] || event "the replacement was not healthy within 10 minutes"

sleep 60
probe_stop
probe_report
echo "evidence in $out/ (probe.csv, report.txt)"
