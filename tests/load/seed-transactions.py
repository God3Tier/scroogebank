"""Add synthetic transactions to every account, for the load test's data volume.

The report's load test needs 50 transactions per account: 1.5 million for
30,000 accounts. Accounts live in account-service's database and transactions
in transaction-service's, with no link between them, so this script:

1. lists every account's ID and client ID with a one-off psql task on the
   accounts database (the db-console task definitions, as
   scripts/db-run-sql.sh uses them), reading the rows back from its log;
2. writes a gzipped SQL file that loads those IDs into a temporary table and
   inserts PER_ACCOUNT rows for each with generate_series;
3. uploads the file to the test bucket (enable_test_tooling, ADR-0071), runs a
   one-off psql task on the transactions database that fetches it through a
   15-minute presigned URL, and deletes the file.

Every row is marked Matched, described as a synthetic load-test transaction,
and has an ID starting with synthetic-fs-, so it can be found and removed.
Run it after seed-scale.js, from the repository root, with the stack running
and credentials for the CRM account in the environment (Git Bash on Windows
needs MSYS_NO_PATHCONV=1 for the log group names).

usage: python tests/load/seed-transactions.py [--per-account 50] [--stack infrastructure/terraform/stacks/crm]
"""
import argparse
import gzip
import json
import os
import subprocess
import sys
import tempfile
import time


def run(*args, capture=True):
    out = subprocess.run(args, capture_output=capture, text=True)
    if out.returncode != 0:
        sys.exit(f"{' '.join(args[:3])} failed: {out.stderr.strip()}")
    return out.stdout


def aws(*args):
    text = run("aws", *args, "--output", "json")
    return json.loads(text) if text.strip() else {}


def read_log(group, stream):
    # --no-paginate, so --next-token is the API's own token: the CLI's automatic
    # paging stops this operation early.
    lines, token = [], None
    while True:
        args = ["logs", "get-log-events", "--no-paginate", "--log-group-name", group,
                "--log-stream-name", stream, "--start-from-head"]
        if token:
            args += ["--next-token", token]
        page = aws(*args)
        lines += [e["message"] for e in page["events"]]
        if page["nextForwardToken"] == token:
            return lines
        token = page["nextForwardToken"]


def run_task(outputs, database, command, environment=None, complete=lambda lines: True):
    """Runs one db-console task and returns its log lines, once complete(lines) holds."""
    console = outputs["db_console"]["value"]
    family = console["task_families"][database]
    override = {"name": "psql", "command": command}
    if environment:
        override["environment"] = [{"name": k, "value": v} for k, v in environment.items()]
    network = {"awsvpcConfiguration": {"subnets": outputs["app_subnet_ids"]["value"],
                                       "securityGroups": [console["security_group"]],
                                       "assignPublicIp": "DISABLED"}}
    cluster = outputs["ecs_cluster"]["value"]
    task = aws("ecs", "run-task", "--cluster", cluster, "--launch-type", "FARGATE",
               "--task-definition", family, "--network-configuration", json.dumps(network),
               "--overrides", json.dumps({"containerOverrides": [override]}))["tasks"][0]["taskArn"]
    print(f"{database}: running {task.rsplit('/', 1)[-1]}", flush=True)
    # Polled, not aws ecs wait: the waiter gives up after 10 minutes, and the
    # insert takes longer.
    while True:
        described = aws("ecs", "describe-tasks", "--cluster", cluster, "--tasks", task)["tasks"][0]
        if described["lastStatus"] == "STOPPED":
            break
        time.sleep(30)
    exit_code = described["containers"][0].get("exitCode")

    group = "/ecs/" + family[: family.rindex("-db-console-")] + "-db-console"
    stream = f"{database}/psql/{task.rsplit('/', 1)[-1]}"
    # A log can still be arriving after the task stops, so read until it is whole.
    for _ in range(12):
        lines = read_log(group, stream)
        if complete(lines):
            break
        time.sleep(10)
    if exit_code != 0:
        print("\n".join(lines[-20:]), file=sys.stderr)
        sys.exit(f"{database}: psql exited with {exit_code}")
    return lines


SQL_HEAD = """\\set ON_ERROR_STOP on
CREATE TEMP TABLE seed_accounts (account_id uuid, client_id uuid);
COPY seed_accounts FROM STDIN WITH (FORMAT csv);
"""

SQL_TAIL = """\\.
SELECT setseed(0.20261102);
INSERT INTO transactions (transaction_id, source_account_ref, client_id, transaction_type, amount, currency,
  transaction_date, status, description, imported_at, import_status, account_id)
SELECT format('synthetic-fs-%s-%s', a.account_id, lpad(n::text, 3, '0')), a.account_id::text, a.client_id,
  CASE WHEN random() < 0.5 THEN 'Deposit' ELSE 'Withdrawal' END,
  round((10 + random() * 4990)::numeric, 2), 'SGD',
  now() - make_interval(days => floor(random() * 365)::int, mins => floor(random() * 1440)::int),
  CASE WHEN random() < 0.90 THEN 'Completed' WHEN random() < 0.7 THEN 'Pending' ELSE 'Failed' END,
  'Synthetic load-test transaction', now(), 'Matched', a.account_id
FROM seed_accounts a CROSS JOIN generate_series(1, {per_account}) AS n
ON CONFLICT (transaction_id) DO NOTHING;
ANALYZE transactions;
SELECT 'transactions=' || count(*) FROM transactions;
"""


def account_rows(lines):
    return [l for l in lines if l.count(",") == 1 and len(l) == 73]


def expected_rows(lines):
    return next((int(l.split("=")[1]) for l in lines if l.startswith("rows=")), -1)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--per-account", type=int, default=50)
    parser.add_argument("--stack", default="infrastructure/terraform/stacks/crm")
    args = parser.parse_args()

    outputs = json.loads(run("terraform", f"-chdir={args.stack}", "output", "-json"))
    tooling = outputs.get("test_tooling", {}).get("value")
    if not tooling:
        sys.exit("no test bucket: apply the crm stack with enable_test_tooling = true")

    query = "SELECT id || ',' || client_id FROM accounts WHERE deleted_at IS NULL"
    lines = run_task(outputs, "accounts", [
        "sh", "-c",
        f'psql -v ON_ERROR_STOP=1 -At -c "{query}" && '
        'psql -At -c "SELECT \'rows=\' || count(*) FROM accounts WHERE deleted_at IS NULL"'],
        complete=lambda lines: len(account_rows(lines)) == expected_rows(lines))
    rows, expected = account_rows(lines), expected_rows(lines)
    if len(rows) != expected:
        sys.exit(f"read {len(rows)} accounts from the log, but the database has {expected}")
    print(f"accounts: {len(rows)}, so {len(rows) * args.per_account} transactions", flush=True)

    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "seed-transactions.sql.gz")
        with gzip.open(path, "wt", encoding="utf-8") as f:
            f.write(SQL_HEAD)
            f.write("\n".join(rows) + "\n")
            f.write(SQL_TAIL.replace("{per_account}", str(args.per_account)))
        key = f"s3://{tooling['bucket']}/seed/seed-transactions-{int(time.time())}.sql.gz"
        run("aws", "s3", "cp", path, key, "--only-show-errors")
    try:
        url = run("aws", "s3", "presign", key, "--expires-in", "900").strip()
        lines = run_task(outputs, "transactions",
                         ["sh", "-c", 'set -o pipefail; wget -qO- "$SEED_URL" | gunzip | psql -q'],
                         {"SEED_URL": url})
        print("\n".join(l for l in lines if l.startswith(("transactions=", "INSERT", "ERROR"))))
    finally:
        run("aws", "s3", "rm", key, "--only-show-errors")


if __name__ == "__main__":
    main()
