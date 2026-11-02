#!/usr/bin/env bash
# Records how the ECS services scale during a load test (tests/load/README.md):
# every service's desired and running task counts every INTERVAL seconds, then
# the scaling activities and, per minute over the same time, each service's
# CPU and each target group's requests per target.
#
# usage: scripts/capture-scaling.sh <out-dir> <minutes> [crm-stack-dir]
# Needs terraform and the AWS CLI, signed in to the stack's account.
set -euo pipefail

out=${1:-}
minutes=${2:-}
crm_dir=${3:-infrastructure/terraform/stacks/crm}
interval=${INTERVAL:-30}

if [[ -z "$out" || -z "$minutes" ]]; then
  echo "usage: $0 <out-dir> <minutes> [crm-stack-dir]" >&2
  exit 2
fi

services=(user-service client-service account-service transaction-service event-alert-service
  client-health-monitor-service auditor dashboard-service engagement-service)
cluster=$(terraform -chdir="$crm_dir" output -raw ecs_cluster | tr -d '\r')
mkdir -p "$out"

start=$(date -u +%Y-%m-%dT%H:%M:%SZ)
stop_at=$(( $(date +%s) + minutes * 60 ))
echo "time_utc,service,desired,running" > "$out/task-counts.csv"
while (( $(date +%s) < stop_at )); do
  now=$(date -u +%H:%M:%S)
  aws ecs describe-services --cluster "$cluster" --services "${services[@]}" \
    --query 'services[].[serviceName,desiredCount,runningCount]' --output text | tr -d '\r' |
    while read -r name desired running; do echo "$now,$name,$desired,$running"; done >> "$out/task-counts.csv"
  sleep "$interval"
done
end=$(date -u +%Y-%m-%dT%H:%M:%SZ)
echo "sampled $start to $end"

aws application-autoscaling describe-scaling-activities --service-namespace ecs \
  --query 'ScalingActivities[].[StartTime,ResourceId,StatusCode,Description,Cause]' --output text |
  tr -d '\r' | sort > "$out/scaling-activities.tsv"

echo "time_utc,service,cpu_avg_percent" > "$out/cpu.csv"
for s in "${services[@]}"; do
  aws cloudwatch get-metric-statistics --namespace AWS/ECS --metric-name CPUUtilization \
    --dimensions Name=ClusterName,Value="$cluster" Name=ServiceName,Value="$s" \
    --start-time "$start" --end-time "$end" --period 60 --statistics Average \
    --query 'Datapoints[].[Timestamp,Average]' --output text | tr -d '\r' |
    while read -r t v; do echo "$t,$s,$v"; done
done | sort >> "$out/cpu.csv"

# Requests per target, per target group of the internal ALB (named like the
# cluster), which is what the request-count scaling policy tracks.
alb=$(aws elbv2 describe-load-balancers --names "$cluster" --query 'LoadBalancers[0].LoadBalancerArn' --output text | tr -d '\r')
echo "time_utc,target_group,requests_per_target" > "$out/requests-per-target.csv"
for tg in $(aws elbv2 describe-target-groups --load-balancer-arn "$alb" --query 'TargetGroups[].TargetGroupArn' --output text | tr -d '\r'); do
  dim=${tg##*:}
  name=$(cut -d/ -f2 <<< "$dim")
  aws cloudwatch get-metric-statistics --namespace AWS/ApplicationELB --metric-name RequestCountPerTarget \
    --dimensions Name=TargetGroup,Value="$dim" \
    --start-time "$start" --end-time "$end" --period 60 --statistics Sum \
    --query 'Datapoints[].[Timestamp,Sum]' --output text | tr -d '\r' |
    while read -r t v; do echo "$t,$name,$v"; done
done | sort >> "$out/requests-per-target.csv"

echo "wrote $out/task-counts.csv, scaling-activities.tsv, cpu.csv, requests-per-target.csv"
