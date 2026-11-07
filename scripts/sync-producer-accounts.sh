#!/usr/bin/env bash
# Gives the bank's producer, on the simulated SFTP server, the CRM accounts
# its transactions may use and each one's currency (ADR-0045): the accounts
# that are neither deleted nor closed, the 100 most recently opened. With
# none, the producer publishes nothing. Run it after opening, closing or deleting accounts in the app, and
# after the SFTP instance is replaced, which starts with an empty list.
#
# It reads the accounts database with scripts/db-run-sql.sh, so the crm stack
# must be running, then rewrites the producer's account file on the instance
# with SSM Run Command. The producer reads the file at its next run.
#
# The accounts in demo_accounts are left out, so the Client Health demo's
# seeded accounts keep only their deterministic history (producer -history)
# and get no random transactions (ADR-0081).
#
# usage: scripts/sync-producer-accounts.sh [crm-stack-dir] [sftp-stack-dir]
# Needs terraform, the AWS CLI and Python, as scripts/db-run-sql.sh does.
set -euo pipefail

crm_dir=${1:-infrastructure/terraform/stacks/crm}
sftp_dir=${2:-infrastructure/terraform/stacks/sftp-server}
limit=100 # keeps the Run Command request small

# The Client Health demo's synthetic accounts, seeded 2026-11-04.
demo_accounts=(
  107bca5c-9ca2-4128-ba0d-77a5a14bfc89 # recurring-credit interruption
  32f72eae-3cc6-4dd5-aeef-c0596f443d0a # transaction-activity decline
  80272835-28f2-4316-80d1-76b7b3ecc096 # sustained net outflow
  85549ca7-d488-448d-b5dc-dcf25496f0e8 # healthy control
  # One client with all four scenarios, one per account, seeded 2026-11-07.
  3a048ae4-8481-4545-adfa-1d5ed98054b0 # recurring-credit interruption
  7ce1f08f-5371-4060-930a-c4ba63e9d20d # transaction-activity decline
  c9560b7b-2776-46d3-ae38-7adfc0840d55 # sustained net outflow
  e9f93879-9763-4350-add1-6371b67c5de5 # healthy control
)

instance=$(terraform -chdir="$sftp_dir" output -raw instance_id)
file=$(terraform -chdir="$sftp_dir" output -raw producer_account_ids_file)

# Each account is tagged and given as <id>:<currency>, and a marker ends the
# output, so output that could not be read is never taken for a CRM without
# accounts.
sql=$(mktemp)
trap 'rm -f "$sql"' EXIT
printf '%s\n' \
  "SELECT 'sync-account ' || id || ':' || currency FROM accounts WHERE deleted_at IS NULL AND status <> 'closed' ORDER BY opening_date DESC, id LIMIT $limit;" \
  "SELECT 'sync-end';" > "$sql"
output=$("$(dirname "$0")/db-run-sql.sh" accounts "$sql" "$crm_dir")
if ! grep -q 'sync-end' <<<"$output"; then
  echo "could not read the accounts query's output, so the producer's list is unchanged; run the script again" >&2
  exit 1
fi

# Only entries that match a UUID and a three-letter currency code reach the
# instance's command line.
entry='[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[A-Z]{3}'
all=$(grep -oE "sync-account $entry" <<<"$output" | cut -d' ' -f2 || true)
ids=$(grep -vF -f <(printf '%s:\n' "${demo_accounts[@]}") <<<"$all" | tr '\n' ' ' || true)
ids=${ids% }
count=$(wc -w <<<"$ids" | tr -d ' ')
skipped=$(( $(wc -w <<<"$all") - count ))
(( skipped == 0 )) || echo "Left out $skipped Client Health demo account(s)."

# Written beside the file and renamed, so the producer never reads half a
# list. With no IDs, xargs -r writes an empty file.
params=$(printf '{"commands":["set -e","install -d -m 0755 %s","echo %s | xargs -rn1 > %s.tmp","chmod 0644 %s.tmp","mv %s.tmp %s"]}' \
  "$(dirname "$file")" "$ids" "$file" "$file" "$file" "$file")

# Git Bash would turn the paths in the commands into Windows paths.
command_id=$(MSYS_NO_PATHCONV=1 aws ssm send-command --instance-ids "$instance" \
  --document-name AWS-RunShellScript --comment "Sync the producer's CRM accounts (ADR-0045)" \
  --parameters "$params" --query Command.CommandId --output text)
aws ssm wait command-executed --command-id "$command_id" --instance-id "$instance" 2>/dev/null || true
status=$(aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance" \
  --query Status --output text)
if [[ "$status" != "Success" ]]; then
  echo "the instance did not update the producer's list ($status):" >&2
  aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance" \
    --query StandardErrorContent --output text >&2
  exit 1
fi

if [[ "$count" == "0" ]]; then
  echo "The CRM has no open accounts: the producer publishes nothing until a sync finds some."
elif [[ "$count" == "1" ]]; then
  echo "Synced 1 account: from its next run, the producer uses only this one."
else
  echo "Synced $count accounts: from its next run, the producer uses only these."
fi
