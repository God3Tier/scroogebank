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
# Accounts listed in the exclude file are left out, so seeded demo accounts
# keep only their deterministic history and get no random transactions. The
# file is scripts/sync-producer-accounts.exclude, or PRODUCER_ACCOUNTS_EXCLUDE,
# one account ID per line; it is gitignored, so the IDs stay out of the
# repository. A missing file excludes nothing.
#
# usage: scripts/sync-producer-accounts.sh [crm-stack-dir] [sftp-stack-dir]
# Needs terraform, the AWS CLI and Python, as scripts/db-run-sql.sh does.
set -euo pipefail

crm_dir=${1:-infrastructure/terraform/stacks/crm}
sftp_dir=${2:-infrastructure/terraform/stacks/sftp-server}
exclude=${PRODUCER_ACCOUNTS_EXCLUDE:-$(dirname "$0")/sync-producer-accounts.exclude}
[[ -f "$exclude" ]] || exclude=/dev/null
limit=100 # keeps the Run Command request small

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
excluded_ids=$(grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' "$exclude" | sed 's/$/:/' || true)
ids=$(grep -vF -f <(printf '%s\n' "$excluded_ids" | sed '/^$/d') <<<"$all" | tr '\n' ' ' || true)
ids=${ids% }
count=$(wc -w <<<"$ids" | tr -d ' ')
skipped=$(( $(wc -w <<<"$all") - count ))
(( skipped == 0 )) || echo "Left out $skipped account(s) listed in $exclude."

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
