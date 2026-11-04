#!/bin/sh
# Runs ZAP's API scan once per spec, inside the zaproxy/zap-stable container.
# The same script runs on a laptop against the public edge and in the in-VPC
# scanner task against the internal ALB (ADR-0071; run-in-vpc.sh).
#
# /zap/wrk holds the run's inputs: specs/*.yaml and, for a signed-in run, a
# file named token with an access token. Reports go to /zap/wrk/out.
# Environment:
#   TARGET        the base URL, e.g. https://sg.itsag3t6.com or the ALB's
#   SCAN          active (the default) or passive (ZAP's safe mode, -S)
#   ZAP_OPTIONS   extra -config settings for ZAP, e.g. thread and time limits
cd /zap/wrk || exit 1
mkdir -p out
: "${TARGET:?TARGET must be set}"

if [ -s token ]; then
  ZAP_AUTH_HEADER_VALUE="Bearer $(cat token)"
  ZAP_AUTH_HEADER_SITE="${TARGET#https://}"
  export ZAP_AUTH_HEADER_VALUE ZAP_AUTH_HEADER_SITE
  echo "signed in"
else
  echo "no token: every request is anonymous"
fi

mode=""
[ "${SCAN:-active}" = passive ] && mode="-S"

port=8090
for spec in specs/*.yaml; do
  name=$(basename "$spec" .yaml)
  # A ZAP that is still shutting down makes the next one fail to start, so
  # wait for it, and give each spec its own port.
  for _ in $(seq 1 30); do pgrep -f zap-.*\\.jar >/dev/null 2>&1 || break; sleep 2; done
  port=$((port + 1))
  echo "$(date -u +%H:%M:%S) scanning $name"
  # shellcheck disable=SC2086 # $mode is empty or one flag
  zap-api-scan.py $mode -I -P "$port" -f openapi -t "/zap/wrk/$spec" -O "$TARGET" \
    -r "out/$name.html" -w "out/$name.md" -J "out/$name.json" -z "${ZAP_OPTIONS:-}"
  echo "$(date -u +%H:%M:%S) $name finished with exit code $?"
done
