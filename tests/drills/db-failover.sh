#!/usr/bin/env bash
# Availability drill 3, database failover (NFR 2): forces a Multi-AZ failover
# of one service's database while the probe requests a route that reads it,
# and checks that a client committed before the failover reads back intact.
# The standby has every committed write (synchronous replication), so the
# outage should be only the seconds connections take to move.
#
# The endpoint's address shows the switch at once: each zone has its own
# database subnet. DescribeDBInstances and the console report the new zone
# minutes late, so the drill follows the address and the RDS events.
#
# usage: tests/drills/db-failover.sh <run-id> <store> [database]
#   <store>    the store tests/test-users.py create wrote
#   database   clients (default), accounts, transactions, users, audit or
#              alerts. The probe lists clients and the marker is a client, so
#              for another database set PROBE_PATH to a route that reads it;
#              there is no marker then.
# Writes out/drills/<run-id>/: probe.csv (the evidence), report.txt and
# events.txt. Needs Docker, Python, terraform and the AWS CLI with credentials
# for the CRM account, and a stack with high availability (Multi-AZ). lib.sh
# lists the other settings.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

usage="usage: $0 <run-id> <store> [database]"
run_id=${1:?$usage}
store=${2:?$usage}
database=${3:-clients}
out="out/drills/$run_id"
mkdir -p "$out"
: > "$out/events.txt"

load_stack
if [[ "$database" != clients && -z "${PROBE_PATH:-}" ]]; then
  echo "the probe lists clients, which are not in the $database database; set PROBE_PATH to a route that reads it" >&2
  exit 2
fi
db="$cluster-$database-db"
read -r multi_az endpoint < <(aws rds describe-db-instances --db-instance-identifier "$db" \
  --query 'DBInstances[0].[MultiAZ, Endpoint.Address]' --output text)
[[ "$multi_az" == True ]] || { echo "$db is not Multi-AZ, so it has no standby to fail over to" >&2; exit 2; }
confirm "force a Multi-AZ failover of $db"

address() { "$py" -c 'import socket, sys; print(socket.gethostbyname(sys.argv[1]))' "$endpoint" 2>/dev/null || echo unresolved; }
token="$out/token"
trap 'probe_stop; rm -f "$token"' EXIT
drill_token "$store" "$token"
start_iso=$(date -u +%Y-%m-%dT%H:%M:%SZ)

marker=
if [[ "$database" == clients ]]; then
  "$py" "$lib_dir/api.py" marker-create --base-url "$app_url" --token-file "$token" --out "$out/marker.json"
  marker="$out/marker.json"
  event "marker client committed"
fi

before=$(address)
probe_start "$token"
sleep 30
aws rds reboot-db-instance --db-instance-identifier "$db" --force-failover >/dev/null
event "failover of $db requested; endpoint at $before"

# Follow the endpoint's address and the instance's status, for at most 10 minutes.
moved=
for _ in $(seq 1 120); do
  sleep 5
  now=$(address)
  if [[ -z "$moved" && "$now" != "$before" && "$now" != unresolved ]]; then
    moved=1
    event "endpoint moved to $now"
  fi
  status=$(aws rds describe-db-instances --db-instance-identifier "$db" --query 'DBInstances[0].DBInstanceStatus' --output text)
  if [[ -n "$moved" && "$status" == available ]]; then
    event "$db available again"
    break
  fi
done
[[ -n "$moved" ]] || event "the endpoint did not move within 10 minutes"

sleep 60
probe_stop
aws rds describe-events --source-type db-instance --source-identifier "$db" --start-time "$start_iso" \
  --query 'Events[].[Date, Message]' --output text > "$out/rds-events.txt"
probe_report
echo "RDS events:"
cat "$out/rds-events.txt"
if [[ -n "$marker" ]]; then
  # A fresh token: the probe's may be close to its hour.
  drill_token "$store" "$token"
  "$py" "$lib_dir/api.py" marker-check --base-url "$app_url" --token-file "$token" --marker "$marker" | tee -a "$out/report.txt"
fi
echo "evidence in $out/ (probe.csv, report.txt, rds-events.txt)"
