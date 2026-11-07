#!/usr/bin/env bash
# Sets the permanent passwords of the brief's evaluation accounts
# (admin@crm.com and agent1@crm.com), which sign in without MFA (ADR-0076).
# The crm stack creates them in Cognito with no password and no e-mail, since
# they have no mailbox; a permanent password means no change is asked for at
# the first sign-in. The passwords are written only in the report, so this
# script reads them from the environment or asks for them, and never stores
# them: not in a file, Terraform state or the shell history.
#
# Run it after each crm apply that created the accounts. agent1@crm.com also
# needs an admin to create it in the CRM (runbook "Evaluation accounts"), which
# adopts this Cognito user and adds it to AGENT.
#
# usage: scripts/evaluation-accounts.sh [crm-stack-dir]
#   EVAL_ADMIN_PASSWORD, EVAL_AGENT_PASSWORD   optional; asked for if unset
set -euo pipefail

crm_dir=${1:-infrastructure/terraform/stacks/crm}
pool=$(terraform -chdir="$crm_dir" output -raw cognito_user_pool_id)

ask() {
  local email=$2 value=${!1:-}
  if [[ -z "$value" ]]; then
    read -r -s -p "Password for $email: " value
    echo >&2
  fi
  printf '%s' "$value"
}

set_password() {
  local email=$1 password=$2
  # Checked before the call, so a typo is caught here and not by Cognito's
  # less specific error. The pool's policy: 12 characters, all four kinds.
  if [[ ${#password} -lt 12 || ! "$password" =~ [a-z] || ! "$password" =~ [A-Z] || ! "$password" =~ [0-9] || ! "$password" =~ [^a-zA-Z0-9] ]]; then
    echo "$email: the password needs 12 or more characters, with lower and upper case, a number and a symbol" >&2
    exit 2
  fi
  aws cognito-idp admin-set-user-password --user-pool-id "$pool" --username "$email" \
    --password "$password" --permanent
  groups=$(aws cognito-idp admin-list-groups-for-user --user-pool-id "$pool" --username "$email" \
    --query 'Groups[].GroupName' --output text)
  echo "$email: permanent password set; groups: ${groups:-none}"
}

set_password admin@crm.com "$(ask EVAL_ADMIN_PASSWORD admin@crm.com)"
set_password agent1@crm.com "$(ask EVAL_AGENT_PASSWORD agent1@crm.com)"
