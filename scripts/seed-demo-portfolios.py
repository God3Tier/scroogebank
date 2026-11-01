#!/usr/bin/env python3
"""Records demo portfolios for one agent's clients so the event-alert worker
has holdings to search and alerts to route to that agent (ADR-0066). Run it
after every deployment, since teardown empties alerts-db (ADR-0058), and within
three days of the demo: valuations older than 72 hours expire and are not
scored.

It goes through the app as the signed-in agent, so the CRM's ownership checks
and the alert.portfolio-replaced audit events apply as for a save in the UI.
It finds the agent's active investment accounts and gives each one the same
portfolio: mostly DBS (SGX-D05) with some OCBC (SGX-O39). With two or more
clients holding DBS, and DBS over half of each account, the worker searches it
under both of its rules (at least 2 clients and 20% of clients, or 50% of an
account). Each save replaces the account's whole recorded portfolio.

Needs alert_holdings_mode = "database" applied to the crm and alert-worker
stacks, and at least two of the agent's clients with an active investment
account (the client verified, then the account activated).

usage: ALERT_AGENT_TOKEN=<access token> python scripts/seed-demo-portfolios.py [--base-url URL] [--dry-run]

The token is the agent's access token: sign in to the app as the agent, then in
the browser console run
  JSON.parse(sessionStorage.getItem(Object.keys(sessionStorage).find(k => k.startsWith('oidc.user:')))).access_token
It is read from the environment, not the command line, so it stays out of the
shell history. It expires after an hour.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

# Real SGX issuers seeded in alerts-db (V5 and V7), so live news can match
# them. Prices are round demo figures, not market data.
POSITIONS = [
    {"instrumentId": "SGX-D05", "quantity": 1000, "unitPrice": 38.5, "valuationCurrency": "SGD", "fxToSgd": 1},
    {"instrumentId": "SGX-O39", "quantity": 500, "unitPrice": 16, "valuationCurrency": "SGD", "fxToSgd": 1},
]
SOURCE_LABEL = "demo-seed"


def request(base_url, token, method, path, body=None, headers=None):
    """Returns (status, parsed JSON or None). Raises only on network errors."""
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base_url + path, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    for name, value in (headers or {}).items():
        req.add_header(name, value)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as err:
        raw = err.read()
        try:
            return err.code, json.loads(raw) if raw else None
        except ValueError:
            return err.code, raw.decode(errors="replace")


def require_ok(status, body, what):
    if status == 401:
        sys.exit(f"{what}: 401, the token is missing or expired; copy a new one")
    if status != 200:
        sys.exit(f"{what}: {status} {body}")
    return body


def agent_clients(base_url, token):
    clients, page = [], 0
    while True:
        body = require_ok(*request(base_url, token, "GET", f"/api/clients?page={page}&size=100"), "list clients")
        clients.extend(body["content"])
        page += 1
        if page >= body["totalPages"]:
            return clients


def active_investment_accounts(base_url, token, client):
    query = urllib.parse.urlencode({"clientId": client["client_id"]})
    accounts = require_ok(*request(base_url, token, "GET", f"/api/accounts?{query}"),
                          f"list accounts of client {client['client_id']}")
    return [a for a in accounts
            if a["account_type"].lower() == "investment" and a["status"].lower() == "active"]


def current_revision(base_url, token, account_id):
    status, body = request(base_url, token, "GET", f"/api/alert-portfolios/{account_id}")
    if status == 404:  # nothing recorded yet; in fixture mode the save below fails instead
        return 0
    return require_ok(status, body, f"read portfolio {account_id}")["revision"]


def main():
    parser = argparse.ArgumentParser(description="Record demo alert portfolios for the signed-in agent.")
    parser.add_argument("--base-url", default="https://sg.itsag3t6.com",
                        help="the app's address (default %(default)s)")
    parser.add_argument("--dry-run", action="store_true", help="list the accounts without saving")
    args = parser.parse_args()
    token = os.environ.get("ALERT_AGENT_TOKEN", "").strip()
    if not token:
        sys.exit("set ALERT_AGENT_TOKEN to the agent's access token (see the usage at the top of this script)")
    base_url = args.base_url.rstrip("/")

    targets = []
    for client in agent_clients(base_url, token):
        for account in active_investment_accounts(base_url, token, client):
            targets.append((client, account))
    holders = {client["client_id"] for client, _ in targets}
    print(f"{len(targets)} active investment account(s) across {len(holders)} client(s)")
    for client, account in targets:
        print(f"  {account['id']}  {client['first_name']} {client['last_name']}")
    if len(holders) < 2:
        sys.exit("the worker searches an instrument only when at least 2 clients hold it: "
                 "verify a second client and open and activate an investment account for them")
    if args.dry_run:
        return

    # A minute back, so the server's clock never sees a valuation in the future.
    valued_at = (datetime.now(timezone.utc) - timedelta(minutes=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
    positions = [dict(p, valuedAt=valued_at) for p in POSITIONS]
    for client, account in targets:
        account_id = account["id"]
        body = {"expectedRevision": current_revision(base_url, token, account_id),
                "sourceLabel": SOURCE_LABEL, "positions": positions}
        status, result = request(base_url, token, "PUT", f"/api/alert-portfolios/{account_id}", body,
                                 {"Idempotency-Key": str(uuid.uuid4()), "X-Correlation-Id": str(uuid.uuid4())})
        if status == 404:
            sys.exit(f"save portfolio {account_id}: 404 {result}; is alert_holdings_mode database "
                     "in both the crm and alert-worker stacks?")
        result = require_ok(status, result, f"save portfolio {account_id}")
        print(f"saved {account_id}: revision {result['revision']}, SGD {result['totalSgd']}")
    print(f"valued at {valued_at}; these positions expire 72 hours later")


if __name__ == "__main__":
    main()
