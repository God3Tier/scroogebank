#!/usr/bin/env bash
# ZAP scans directly at the services, past the edge, from the in-VPC scanner
# task (ADR-0071; run-in-vpc.sh). Two runs:
#   1. anonymous and passive, every spec in one task: shows each service
#      refusing requests without a valid JWT;
#   2. active, as the ZAP test agent (tests/test-users.py), one task per
#      spec, five at a time, with a fresh token for each five, so none
#      expires mid-scan.
# Needs the crm stack applied with enable_test_tooling = true, and what
# run-in-vpc.sh needs. ZAP_OPTIONS overrides the active scan's limits (4
# threads, 2 minutes per rule, 8 per spec).
#
# usage: tests/security/zap/direct.sh <run-id> <agent-store> [spec ...]
#   <agent-store>  the store tests/test-users.py create wrote
#   spec           the specs to scan (default: out/zap/direct-specs/*.yaml,
#                  from active-scope.py --internal)
# Reports land in out/zap/<run-id>-anonymous/ and out/zap/<run-id>-active-<spec>/,
# and the scan's start and end (UTC) in out/zap/<run-id>-window.txt.
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
[[ ${#specs[@]} -gt 0 ]] || specs=(out/zap/direct-specs/*.yaml)
[[ -f "${specs[0]}" ]] || { echo "no specs: run active-scope.py --internal first (README.md)" >&2; exit 2; }

work="out/zap/$run_id-work"
rm -rf "$work"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/all"
cp "${specs[@]}" "$work/all/"
echo "start $(date -u +%Y-%m-%dT%H:%M:%SZ)" > "out/zap/$run_id-window.txt"

echo "anonymous, passive: ${#specs[@]} specs"
SCAN=passive bash "$here/run-in-vpc.sh" "$run_id-anonymous" "$work/all" "" "$crm_dir" > "out/zap/$run_id-anonymous.log" 2>&1

export ZAP_OPTIONS=${ZAP_OPTIONS:-"-config scanner.threadPerHost=4 -config scanner.maxRuleDurationInMins=2 -config scanner.maxScanDurationInMins=8"}
failed=0
batch=()
finish_batch() {
  for pid in ${batch[@]+"${batch[@]}"}; do wait "$pid" || failed=$((failed + 1)); done
  batch=()
  rm -f "$work/token"
}
for spec in "${specs[@]}"; do
  name=$(basename "$spec" .yaml)
  mkdir -p "$work/$name"
  cp "$spec" "$work/$name/"
  # One token per batch: all five tasks start together, well inside its 60 minutes.
  [[ ${#batch[@]} -gt 0 ]] || "$py" "$here/../../test-users.py" token --store "$store" --out "$work/token" --stack "$crm_dir"
  (
    if SCAN=active bash "$here/run-in-vpc.sh" "$run_id-active-$name" "$work/$name" "$work/token" "$crm_dir" \
      > "out/zap/$run_id-active-$name.log" 2>&1; then status=0; else status=$?; fi
    echo "active $name finished with exit code $status"
    exit "$status"
  ) &
  batch+=($!)
  (( ${#batch[@]} < 5 )) || finish_batch
done
finish_batch

echo "end $(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "out/zap/$run_id-window.txt"
echo "reports in out/zap/$run_id-anonymous/ and out/zap/$run_id-active-*/; $failed task(s) failed"
(( failed == 0 ))
