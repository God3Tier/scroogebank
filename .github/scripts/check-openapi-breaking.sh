#!/usr/bin/env bash
# Fails when a pull request makes a breaking change to an OpenAPI contract in
# docs/api, compared with the branch it would merge into (ADR-0063), the way
# buf breaking guards the gRPC contracts (ADR-0021). A contract that is new on
# this branch has nothing to compare with; a removed one fails. Changes
# accepted on purpose are listed, with reasons, in
# docs/api/accepted-breaking-changes.txt.
#
# The contracts are OpenAPI 3.0. A base copy still in 3.1 is converted to 3.0
# first: oasdiff reads 3.1's nullable types as not nullable, so comparing the
# two versions directly reports changes that are not there.
#
# usage: check-openapi-breaking.sh <base ref>
set -euo pipefail

base="$1"
oasdiff_image="tufin/oasdiff:v1.33.0"
down_convert="@apiture/openapi-down-convert@0.14.2"
accepted="docs/api/accepted-breaking-changes.txt"

# oasdiff runs in a container that sees only the workspace, so the base
# versions are written inside it.
base_dir=".oasdiff-base"
rm -rf "$base_dir"
mkdir -p "$base_dir"
trap 'rm -rf "$base_dir"' EXIT

# The file's OpenAPI version, such as 3.0.3.
version() { grep -m1 -E '^openapi:' "$1" | sed -E 's/^openapi:[[:space:]]*//' | tr -d "\"'[:cntrl:]"; }

status=0
for spec in $(git ls-tree --name-only "$base" docs/api/ | grep '\.yaml$'); do
  if [ ! -f "$spec" ]; then
    echo "::error file=$spec::$spec is on $base but removed here; callers lose the whole contract."
    status=1
    continue
  fi
  echo "== $spec"
  if [[ "$(version "$spec")" != 3.0.* ]]; then
    echo "::error file=$spec::$spec must be OpenAPI 3.0 (ADR-0063)."
    status=1
    continue
  fi
  old="$base_dir/$(basename "$spec")"
  git show "$base:$spec" > "$old"
  if [[ "$(version "$old")" == 3.1.* ]]; then
    docker run --rm -v "$PWD:/workspace" -w /workspace node:24-alpine \
      npx --yes "$down_convert" --input "$old" --output "$old.3.0" > /dev/null
    old="$old.3.0"
  fi
  if ! docker run --rm -v "$PWD:/workspace" -w /workspace "$oasdiff_image" \
      breaking "$old" "$spec" --fail-on ERR --err-ignore "$accepted"; then
    echo "::error file=$spec::$spec has a breaking change; see the oasdiff output above."
    status=1
  fi
done
exit "$status"
