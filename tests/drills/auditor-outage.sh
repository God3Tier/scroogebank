#!/usr/bin/env bash
# Availability drill 2, Auditor outage (NFR 2 and 5): stops the Auditor and
# suspends its autoscaling, makes changes as the test agent, then starts it
# again. Users should not notice: each service writes its audit events to its
# own outbox and a relay sends them to the queue (ADR-0020), so the changes
# succeed, the events wait in the queue, and the Auditor chains each of them
# once when it is back, with nothing in the dead-letter queue.
#
# usage: tests/drills/auditor-outage.sh <run-id> <store> [changes...]
#   <store>    the store tests/test-users.py create wrote
#   changes    passed to api.py changes (default: 5 clients, 5 updates, 10 accounts)
# Writes out/drills/<run-id>/: report.txt and events.txt. Whatever happens, it
# resumes the Auditor's autoscaling and restores its task count on the way
# out. Needs Python, terraform and the AWS CLI with credentials for the CRM
# account. lib.sh lists the other settings.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

usage="usage: $0 <run-id> <store> [changes...]"
run_id=${1:?$usage}
store=${2:?$usage}
shift 2
out="out/drills/$run_id"
mkdir -p "$out"
: > "$out/events.txt"

load_stack
queue=$(terraform -chdir="$crm_dir" output -raw audit_queue_url)
dlq=$(terraform -chdir="$crm_dir" output -raw audit_dlq_url)
resource="service/$cluster/auditor"
original=$(aws ecs describe-services --cluster "$cluster" --services auditor --query 'services[0].desiredCount' --output text)
(( original >= 1 )) || original=1
confirm "stop the Auditor for a few minutes and suspend its autoscaling"

depth() { # depth <queue-url>: messages waiting or being processed
  aws sqs get-queue-attributes --queue-url "$1" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
    --query '[Attributes.ApproximateNumberOfMessages, Attributes.ApproximateNumberOfMessagesNotVisible]' \
    --output text | awk '{ print $1 + $2 }'
}
scaling() { # scaling true|false: suspend or resume the Auditor's autoscaling
  aws application-autoscaling register-scalable-target --service-namespace ecs \
    --scalable-dimension ecs:service:DesiredCount --resource-id "$resource" \
    --suspended-state "DynamicScalingInSuspended=$1,DynamicScalingOutSuspended=$1,ScheduledScalingSuspended=$1" >/dev/null
}
stopped=
restore() {
  [[ -n "$stopped" ]] || return 0
  aws ecs update-service --cluster "$cluster" --service auditor --desired-count "$original" >/dev/null
  scaling false
  stopped=
  event "Auditor set back to $original task(s), autoscaling resumed"
}

token="$out/token"
trap 'restore; rm -f "$token"' EXIT
drill_token "$store" "$token"
start_iso=$(date -u +%Y-%m-%dT%H:%M:%SZ)

stopped=1
scaling true
aws ecs update-service --cluster "$cluster" --service auditor --desired-count 0 >/dev/null
event "Auditor autoscaling suspended, desired count 0"
aws ecs wait services-stable --cluster "$cluster" --services auditor
event "Auditor stopped; $(depth "$queue") messages in the audit queue"

if "$py" "$lib_dir/api.py" changes --base-url "$app_url" --token-file "$token" "$@" | tee "$out/changes.txt"; then
  event "changes made: $(tail -1 "$out/changes.txt")"
else
  event "SOME CHANGES FAILED: $(tail -1 "$out/changes.txt")"
fi
sleep 30 # the outbox relays send every few seconds
event "$(depth "$queue") messages waiting in the audit queue"

restore
for _ in $(seq 1 60); do
  sleep 10
  waiting=$(depth "$queue")
  if (( waiting == 0 )); then
    event "audit queue drained"
    break
  fi
done
(( waiting == 0 )) || event "the audit queue still held $waiting messages after 10 minutes"
event "$(depth "$dlq") messages in the dead-letter queue"
end_iso=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# The rows the Auditor wrote, each event once, and no broken link in the chain.
cat > "$out/audit.sql" <<SQL
SELECT source_service, action, count(*) AS events, count(DISTINCT event_id) AS distinct_ids,
       min(sequence_no) AS first_seq, max(sequence_no) AS last_seq
FROM audit_log WHERE timestamp >= '$start_iso' AND timestamp < '$end_iso'
GROUP BY 1, 2 ORDER BY 1, 2;
SELECT count(*) AS broken_links FROM (
  SELECT parent_hash, lag(hash) OVER (ORDER BY sequence_no) AS previous FROM audit_log
) AS chain WHERE previous IS NOT NULL AND parent_hash <> previous;
SQL
bash scripts/db-run-sql.sh audit "$out/audit.sql" "$crm_dir" | tee "$out/audit-rows.txt"

{
  echo "Auditor outage drill $run_id, $start_iso to $end_iso (UTC)"
  "$py" -c 'import sys, time
for line in open(sys.argv[1]):
    t, label = line.rstrip("\n").split(" ", 1)
    print(" ", time.strftime("%H:%M:%S", time.gmtime(int(t))), label)' "$out/events.txt"
} > "$out/report.txt"
cat "$out/report.txt"
echo "For a full check of the chain, run tests/drills/chain-verify.sh $run_id-verify"
