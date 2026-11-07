# Shared by the drill scripts, which source it: the crm stack's settings, a
# confirmation before anything is broken, the test agent's token, the probe,
# and the drill's timeline. Run from the repository root.
#
# Settings:
#   CRM_DIR           the crm stack (default infrastructure/terraform/stacks/crm)
#   EXPECTED_CLUSTER  the only cluster a drill may break (default crm-sg-prod)
#   DRILL_USER        the test agent the probe and changes act as
#                     (default zap.agent@example.com, from tests/test-users.py)
#   DRILL_YES=1       skip the confirmation, for a run nobody watches
#   PROBE_PATH        the route the probe requests once a second (poller.js)

crm_dir=${CRM_DIR:-infrastructure/terraform/stacks/crm}
lib_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

py=
for candidate in python3 python py; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys' >/dev/null 2>&1; then
    py=$candidate
    break
  fi
done
[[ -n "$py" ]] || { echo "no working Python found (tried python3, python, py)" >&2; exit 2; }

# A path as Docker Desktop on Windows wants it; unchanged elsewhere.
host_path() { (cd "$1" && { pwd -W 2>/dev/null || pwd; }); }

# Reads the stack's cluster and address, and refuses any cluster but the expected one.
load_stack() {
  cluster=$(terraform -chdir="$crm_dir" output -raw ecs_cluster)
  app_url=$(terraform -chdir="$crm_dir" output -raw app_url)
  account=$(aws sts get-caller-identity --query Account --output text)
  if [[ "$cluster" != "${EXPECTED_CLUSTER:-crm-sg-prod}" ]]; then
    echo "the crm stack's cluster is $cluster, not ${EXPECTED_CLUSTER:-crm-sg-prod}; set EXPECTED_CLUSTER to drill it" >&2
    exit 2
  fi
}

# confirm <what the drill will do>
confirm() {
  echo "This drill will $1, in account $account ($cluster)."
  [[ "${DRILL_YES:-}" == 1 ]] && return
  local answer
  read -r -p "Type the cluster's name to go on: " answer
  [[ "$answer" == "$cluster" ]] || { echo "stopped; nothing was changed" >&2; exit 1; }
}

# drill_token <store> <file>: a fresh access token for the test agent.
drill_token() {
  "$py" "$lib_dir/../test-users.py" token --store "$1" --out "$2" \
    --user "${DRILL_USER:-zap.agent@example.com}" --stack "$crm_dir" >/dev/null
}

# event <label>: a line in the drill's timeline, at the current second.
event() {
  echo "$(date -u +%s) $*" >> "$out/events.txt"
  echo "$(date -u +%H:%M:%S) $*"
}

# probe_start <token-file>: poller.js in the background, one request a second,
# each written to $out/probe.csv.gz. It stops itself after 30 minutes.
probe_start() {
  probe_name="drill-probe-$run_id"
  docker rm -f "$probe_name" >/dev/null 2>&1 || true
  MSYS_NO_PATHCONV=1 docker run -d --name "$probe_name" -u 0 \
    -e BASE_URL="$app_url" -e AGENT_TOKENS="$(cat "$1")" -e DURATION=30m -e PROBE_PATH \
    -v "$(host_path tests/load):/scripts:ro" -v "$(host_path "$out"):/out" \
    grafana/k6:2.3.0 run --quiet --out csv=/out/probe.csv.gz /scripts/poller.js >/dev/null
  event "probe started (${PROBE_PATH:-/api/clients?page=0&size=1})"
}

# probe_stop: stops the probe (k6 writes the rest of its CSV on the way out).
probe_stop() {
  [[ -n "${probe_name:-}" ]] || return 0
  docker stop -t 30 "$probe_name" >/dev/null 2>&1 || true
  docker rm "$probe_name" >/dev/null 2>&1 || true
  probe_name=
  event "probe stopped"
}

# probe_report: the outage the probe saw, against the drill's timeline.
probe_report() {
  "$py" "$lib_dir/probe-report.py" "$out/probe.csv.gz" "$out/probe.csv" --events "$out/events.txt" | tee "$out/report.txt"
}
