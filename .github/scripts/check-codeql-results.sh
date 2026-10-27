#!/usr/bin/env bash
# Fails when a CodeQL SARIF report in the given directory holds any alert.
#
# The repository has no GitHub Code Security, so CodeQL cannot upload its
# results to code scanning. Each alert is printed as an error annotation
# instead, which GitHub shows on the pull request's changed files, and the
# SARIF report is kept as a build artifact.
set -euo pipefail

dir="$1"
total=0

for report in "$dir"/*.sarif; do
  count=$(jq '[.runs[].results[]?] | length' "$report")
  total=$((total + count))
  jq -r '
    .runs[].results[]? |
    (.locations[0].physicalLocation // {}) as $loc |
    "::error file=\($loc.artifactLocation.uri // ""),line=\($loc.region.startLine // 1),title=CodeQL \(.ruleId)::\(.message.text | gsub("\n"; " "))"
  ' "$report"
done

if [ "$total" -gt 0 ]; then
  echo "CodeQL found $total alert(s)."
  exit 1
fi
echo "CodeQL found no alerts."
