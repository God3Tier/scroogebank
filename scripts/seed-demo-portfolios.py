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

News alerts (--profile news). Gives the agent's clients the holdings the news-alert test needs, so one client has several
tickers: the first client (accounts in id order) holds Shell, Anthropic and Apple, the second Apple and Anthropic, and every
other client Apple only. Shell, Apple and Anthropic must be in alerts-db (Shell and Apple from V8, Anthropic from V18). Two
optional extras:
  --add-websites  adds the test websites to the shared search list. Do it first: adding a website starts a search of the
                  companies the agent's clients already hold (ADR-0081), which spends Tavily credits.
  --search ID     then starts one search of that company (for example LSE-SHEL) over every website, as Search now does.

usage: ALERT_AGENT_TOKEN=<access token> python scripts/seed-demo-portfolios.py [--base-url URL] [--dry-run]
       [--profile dbs|news] [--add-websites] [--search INSTRUMENT_ID]

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

# The news-alert test (--profile news). Round demo figures, not market data; fx is to SGD.
APPLE = {"instrumentId": "NASDAQ-AAPL", "quantity": 60, "unitPrice": 230, "valuationCurrency": "USD", "fxToSgd": 1.34}
ANTHROPIC = {"instrumentId": "PRIV-ANTHROPIC", "unitPrice": 100, "valuationCurrency": "USD", "fxToSgd": 1.34}
SHELL = {"instrumentId": "LSE-SHEL", "quantity": 600, "unitPrice": 27, "valuationCurrency": "GBP", "fxToSgd": 1.75}
# A large Anthropic holding scores above the alert threshold only when it is about half of the portfolio, and a
# portfolio with Shell in it is bigger, so the client with all three holds more Anthropic.
NEWS_WEBSITES = ["cnbc.com", "finance.yahoo.com", "thenextweb.com", "techspot.com", "adage.com"]


def positions_for(profile, index):
    """The positions for the index-th account (0 first) under a profile."""
    if profile == "dbs":
        return [dict(p) for p in POSITIONS]
    if index == 0:
        return [dict(SHELL), dict(ANTHROPIC, quantity=400), dict(APPLE)]
    if index == 1:
        return [dict(APPLE), dict(ANTHROPIC, quantity=140)]
    return [dict(APPLE)]


def add_websites(base_url, token):
    """Adds the test websites. 201 is added; 409 means it is already searched, which is fine."""
    for domain in NEWS_WEBSITES:
        status, body = request(base_url, token, "POST", "/api/alerts/search-websites", {"domain": domain},
                               {"X-Correlation-Id": str(uuid.uuid4())})
        if status == 401:
            sys.exit("add website: 401, the token is missing or expired; copy a new one")
        if status in (200, 201):
            print(f"added website {domain}")
        elif status == 409:
            print(f"website {domain}: already searched, left as it is")
        else:
            sys.exit(f"add website {domain}: {status} {body}")


def search_now(base_url, token, instrument_id):
    status, body = request(base_url, token, "POST", "/api/alerts/search-now", {"instrumentId": instrument_id},
                           {"X-Correlation-Id": str(uuid.uuid4())})
    if status == 403:
        sys.exit(f"search {instrument_id}: 403, none of this agent's clients holds it")
    body = require_ok(status, body, f"search {instrument_id}")
    print(f"search {instrument_id}: {body.get('outcome')}; reports appear in about 5 to 7 minutes, refresh the page")


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
    parser.add_argument("--profile", choices=["dbs", "news"], default="dbs",
                        help="dbs: the DBS and OCBC demo (default); news: Shell, Anthropic and Apple for the news-alert test")
    parser.add_argument("--add-websites", action="store_true", help="add the test websites to the shared search list first")
    parser.add_argument("--search", metavar="INSTRUMENT_ID", help="start one search of this company at the end")
    args = parser.parse_args()
    token = os.environ.get("ALERT_AGENT_TOKEN", "").strip()
    if not token:
        sys.exit("set ALERT_AGENT_TOKEN to the agent's access token (see the usage at the top of this script)")
    base_url = args.base_url.rstrip("/")

    targets = []
    for client in agent_clients(base_url, token):
        for account in active_investment_accounts(base_url, token, client):
            targets.append((client, account))
    targets.sort(key=lambda target: target[1]["id"])  # the same order every run, so a rerun saves the same portfolios
    holders = {client["client_id"] for client, _ in targets}
    print(f"{len(targets)} active investment account(s) across {len(holders)} client(s)")
    for index, (client, account) in enumerate(targets):
        held = ", ".join(f"{p['instrumentId']} x{p['quantity']}" for p in positions_for(args.profile, index))
        print(f"  {account['id']}  {client['first_name']} {client['last_name']}: {held}")
    if len(holders) < 2:
        sys.exit("the worker searches an instrument only when at least 2 clients hold it: "
                 "verify a second client and open and activate an investment account for them")
    if args.dry_run:
        if args.add_websites:
            print("would add websites: " + ", ".join(NEWS_WEBSITES))
        if args.search:
            print(f"would search {args.search}")
        return

    # Websites first: adding one starts a search of the companies already held, so with nothing held yet it costs nothing.
    if args.add_websites:
        add_websites(base_url, token)

    # A minute back, so the server's clock never sees a valuation in the future.
    valued_at = (datetime.now(timezone.utc) - timedelta(minutes=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
    for index, (client, account) in enumerate(targets):
        positions = [dict(p, valuedAt=valued_at) for p in positions_for(args.profile, index)]
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
    if args.search:
        search_now(base_url, token, args.search)


if __name__ == "__main__":
    main()
