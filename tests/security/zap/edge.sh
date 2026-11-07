#!/usr/bin/env bash
# Active ZAP scan through the edge (CloudFront, the WAF, API Gateway), from
# this machine, as the ZAP test agent (tests/test-users.py). Runs scan.sh in
# ZAP's container once per spec, each with a fresh token: a token lasts 60
# minutes, and the nine public specs take longer than that together. ZAP
# stays under the API's throttle (2 threads, 20 ms between requests) and
# spends at most 2 minutes per rule and 8 per spec, unless ZAP_OPTIONS says
# otherwise.
#
# usage: tests/security/zap/edge.sh <run-id> <agent-store> [spec ...]
#   <agent-store>  the store tests/test-users.py create wrote
#   spec           the specs to scan (default: out/zap/active-specs/*.yaml,
#                  from active-scope.py)
# Reports land in out/zap/<run-id>/<spec>.{html,md,json}, and the scan's start
# and end (UTC) in out/zap/<run-id>/window.txt, for waf-blocks.py. Needs
# Docker, Python, terraform and the AWS CLI with credentials for the CRM
# account. TARGET defaults to the crm stack's app_url; CRM_DIR is the stack.
set -euo pipefail

usage="usage: $0 <run-id> <agent-store> [spec ...]"
run_id=${1:?$usage}
store=${2:?$usage}
shift 2
crm_dir=${CRM_DIR:-infrastructure/terraform/stacks/crm}
here=$(cd "$(dirname "$0")" && pwd)

py=
for candidate in python3 python py; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys' >/dev/null 2>&1; then
    py=$candidate
    break
  fi
done
[[ -n "$py" ]] || { echo "no working Python found (tried python3, python, py)" >&2; exit 2; }

specs=("$@")
[[ ${#specs[@]} -gt 0 ]] || specs=(out/zap/active-specs/*.yaml)
[[ -f "${specs[0]}" ]] || { echo "no specs: run active-scope.py first (README.md)" >&2; exit 2; }
target=${TARGET:-$(terraform -chdir="$crm_dir" output -raw app_url)}
options=${ZAP_OPTIONS:-"-config scanner.threadPerHost=2 -config scanner.delayInMs=20 -config scanner.maxRuleDurationInMins=2 -config scanner.maxScanDurationInMins=8"}

out="out/zap/$run_id"
work="out/zap/$run_id-work"
mkdir -p "$out"
trap 'rm -rf "$work"' EXIT
echo "start $(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$out/window.txt"

for spec in "${specs[@]}"; do
  rm -rf "$work"
  mkdir -p "$work/specs"
  cp "$spec" "$work/specs/"
  # The container's sh cannot run a script with Windows line endings.
  tr -d '\r' < "$here/scan.sh" > "$work/scan.sh"
  "$py" "$here/../../test-users.py" token --store "$store" --out "$work/token" --stack "$crm_dir"
  # ZAP runs as its own user, which must read the inputs and write the reports.
  chmod a+rwx "$work"
  chmod a+r "$work/token" "$work/scan.sh" "$work/specs" "$work"/specs/*
  chmod a+x "$work/specs"
  MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W 2>/dev/null || pwd)/$work:/zap/wrk:rw" \
    -e TARGET="$target" -e ZAP_OPTIONS="$options" zaproxy/zap-stable:2.17.0 sh /zap/wrk/scan.sh
  rm -f "$work/token"
  cp "$work"/out/* "$out/"
done

echo "end $(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$out/window.txt"
echo "reports in $out/"
