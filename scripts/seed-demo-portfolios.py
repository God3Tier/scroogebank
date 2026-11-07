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

News alerts (--profile news). Gives the agent's clients the holdings the news-alert test used, in account-id order: the first
three hold Apple, Apple and Anthropic, and Apple, Anthropic and Shell; the next thirty each hold a mix of Apple, Anthropic
and Shell plus one or two of Microsoft, Nvidia, JPMorgan, Visa, Tencent and HSBC (those six are test companies that are
unlikely to find any news); any client after that holds Apple only. Apple, Shell and the six others come with V8 and Anthropic
with V18. The script never starts a search by itself: use --search, or press Search now (a single press searches every company
the agent's clients hold). Two optional extras:
  --add-websites  adds the test websites to the shared search list. Do it first: adding a website starts a search of the
                  companies the agent's clients already hold (ADR-0081), which spends Tavily credits.
  --search ID     then starts one search of that company (for example PRIV-ANTHROPIC) over every website, as Search now does.

Clients (--create-clients). Creates the agent's 33 demo clients (DEMO_CLIENTS) when they are missing, so a fresh deployment
needs no clicking: each is created under the signed-in agent, verified without a document, and given an active SGD investment
account. A client that already exists (same first and last name) is reused, and one that has an active investment account keeps
it, so a rerun creates nothing twice. Every client gets a made-up address, first.last@example.test, which cannot receive mail.
--client-emails A,B,C replaces the addresses of the first clients, in the order of DEMO_CLIENTS, with ones that can receive
mail (in the SES sandbox, verified ones); give one for every client you will send an email to. Each address must be unused. The agent itself is not created here: an admin creates it
in Manage Agents (docs/runbooks/deploy-aws.md, "Evaluation accounts"), and it then signs in once.

usage: ALERT_AGENT_TOKEN=<access token> python scripts/seed-demo-portfolios.py [--base-url URL] [--dry-run]
       [--profile dbs|news] [--create-clients [--client-emails A,B,C]] [--add-websites] [--search INSTRUMENT_ID]

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
DEMO_CLIENTS = [
    ("Chloe", "Tan"), ("Daniel", "Lim"), ("Esther", "Goh"),
    ("Aaron", "Teo"), ("Bella", "Ng"), ("Caleb", "Lee"), ("Diana", "Koh"), ("Ethan", "Chua"), ("Fiona", "Tay"),
    ("Gareth", "Ong"), ("Hannah", "Sim"), ("Ivan", "Goh"), ("Jasmine", "Yeo"), ("Kelvin", "Ho"), ("Lydia", "Chan"),
    ("Marcus", "Phua"), ("Nadia", "Lau"), ("Oscar", "Fong"), ("Priya", "Nair"), ("Quinn", "Toh"), ("Rachel", "Seah"),
    ("Samuel", "Wee"), ("Tina", "Low"), ("Umar", "Yusof"), ("Vera", "Lim"), ("Wesley", "Ang"), ("Xin Yi", "Tan"),
    ("Yusuf", "Aziz"), ("Zoe", "Chong"), ("Alvin", "Peh"), ("Bernice", "Ho"), ("Clarence", "Lew"), ("Denise", "Quek"),
]
# The six other test companies: round demo prices, fx to SGD. All six come with V8.
OTHER_COMPANIES = [
    {"instrumentId": "NASDAQ-MSFT", "unitPrice": 420, "valuationCurrency": "USD", "fxToSgd": 1.34},
    {"instrumentId": "NASDAQ-NVDA", "unitPrice": 130, "valuationCurrency": "USD", "fxToSgd": 1.34},
    {"instrumentId": "NYSE-JPM", "unitPrice": 240, "valuationCurrency": "USD", "fxToSgd": 1.34},
    {"instrumentId": "NYSE-V", "unitPrice": 310, "valuationCurrency": "USD", "fxToSgd": 1.34},
    {"instrumentId": "HKEX-0700", "unitPrice": 380, "valuationCurrency": "HKD", "fxToSgd": 0.17},
    {"instrumentId": "LSE-HSBA", "unitPrice": 8, "valuationCurrency": "GBP", "fxToSgd": 1.75},
]
CORE_MIXES = [("apple",), ("anthropic",), ("shell",), ("apple", "anthropic"), ("apple", "shell"), ("anthropic", "shell"),
              ("apple", "anthropic", "shell")]
NEWS_WEBSITES = ["cnbc.com", "finance.yahoo.com", "thenextweb.com", "techspot.com", "adage.com"]


def positions_for(profile, index):
    """The positions for the index-th account (0 first) under a profile."""
    if profile == "dbs":
        return [dict(p) for p in POSITIONS]
    if index == 0:
        return [dict(APPLE)]
    if index == 1:
        return [dict(APPLE), dict(ANTHROPIC, quantity=140)]
    if index == 2:
        return [dict(APPLE), dict(ANTHROPIC, quantity=140), dict(SHELL, quantity=400)]
    if index < len(DEMO_CLIENTS):
        return mixed_positions(index - 3)
    return [dict(APPLE)]


def mixed_positions(i):
    """The i-th of the thirty mixed clients: a core mix of Apple, Anthropic and Shell, plus one or two of the six others."""
    base = {"apple": (APPLE, 40), "anthropic": (ANTHROPIC, 150), "shell": (SHELL, 500)}
    chosen = [(dict(base[name][0]), base[name][1]) for name in CORE_MIXES[i % len(CORE_MIXES)]]
    others = [OTHER_COMPANIES[i % 6]] + ([OTHER_COMPANIES[(i + 2) % 6]] if i % 3 == 0 else [])
    chosen += [(dict(company), 20) for company in others]
    # A different size for each holding, so the shares of the portfolios differ from client to client.
    return [dict(position, quantity=quantity + ((i * 7 + n * 13) % 5) * 10) for n, (position, quantity) in enumerate(chosen)]


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


def client_body(first, last, email, index):
    """A valid demo client: adult, Singapore address, a phone number of its own (a phone number cannot be shared)."""
    return {"first_name": first, "last_name": last, "date_of_birth": "1985-06-15T00:00:00Z", "gender": "Male" if index % 2 == 0 else "Female",
            "email": email, "phone_number": f"+65910000{index:02d}", "address": f"{10 + index} Demo Street",
            "city": "Singapore", "state": "Singapore", "country": "Singapore", "postal_code": f"{180000 + index}"}


def account_body(client_id):
    return {"client_id": client_id, "account_type": "INVESTMENT", "status": "ACTIVE", "opening_date": "2026-01-01T00:00:00Z",
            "initial_deposit": 10000, "currency": "SGD", "branch_id": "SG-001"}


def default_email(first, last):
    """A made-up address that cannot receive mail, unique per client."""
    return f"{first}.{last}".lower().replace(" ", "") + "@example.test"


def create_clients(base_url, token, emails, dry_run):
    """Creates the demo clients that are missing, verifies them and opens an active investment account for each."""
    if len(emails) > len(DEMO_CLIENTS):
        sys.exit(f"--client-emails has {len(emails)} addresses but there are only {len(DEMO_CLIENTS)} clients")
    existing = {(c["first_name"], c["last_name"]): c for c in agent_clients(base_url, token)}
    for index, (first, last) in enumerate(DEMO_CLIENTS):
        email = emails[index] if index < len(emails) else default_email(first, last)
        client = existing.get((first, last))
        if client is None:
            if dry_run:
                print(f"would create client {first} {last} <{email}>, verify it and open an active investment account")
                continue
            status, body = request(base_url, token, "POST", "/api/clients", client_body(first, last, email, index + 1))
            if status == 409:
                sys.exit(f"create {first} {last}: 409, that email or phone number belongs to another client")
            if status == 401:
                sys.exit(f"create {first} {last}: 401, the token is missing or expired; copy a new one")
            if status == 403:
                sys.exit(f"create {first} {last}: 403, sign in as an agent, not an admin")
            if status != 201:
                sys.exit(f"create {first} {last}: {status} {body}")
            client = {"client_id": body["client_id"], "first_name": first, "last_name": last, "verification_status": "UNVERIFIED"}
            print(f"created client {first} {last}")
        else:
            print(f"client {first} {last}: already there, reused")
        if str(client.get("verification_status", "")).upper() != "VERIFIED":
            if dry_run:
                print(f"would verify {first} {last}")
            else:
                status, body = request(base_url, token, "POST", f"/api/clients/{client['client_id']}/verify")
                if status not in (200, 409):  # 409 = verified already
                    sys.exit(f"verify {first} {last}: {status} {body}")
                print(f"verified {first} {last}")
        if dry_run:
            continue
        if active_investment_accounts(base_url, token, client):
            print(f"{first} {last}: active investment account already there")
            continue
        status, body = request(base_url, token, "POST", "/api/accounts", account_body(client["client_id"]))
        if status != 201:
            sys.exit(f"open account for {first} {last}: {status} {body}")
        print(f"opened an active investment account for {first} {last}")


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
                        help="dbs: the DBS and OCBC demo (default); news: Anthropic and Apple for the news-alert test")
    parser.add_argument("--create-clients", action="store_true",
                        help="first create the demo clients and their active investment accounts if they are missing")
    parser.add_argument("--client-emails", default="",
                        help="with --create-clients: one email per client, comma separated, in the order " +
                             ", ".join(f"{f} {l}" for f, l in DEMO_CLIENTS))
    parser.add_argument("--add-websites", action="store_true", help="add the test websites to the shared search list first")
    parser.add_argument("--search", metavar="INSTRUMENT_ID", help="start one search of this company at the end")
    args = parser.parse_args()
    token = os.environ.get("ALERT_AGENT_TOKEN", "").strip()
    if not token:
        sys.exit("set ALERT_AGENT_TOKEN to the agent's access token (see the usage at the top of this script)")
    base_url = args.base_url.rstrip("/")

    if args.create_clients:
        create_clients(base_url, token, [e.strip() for e in args.client_emails.split(",") if e.strip()], args.dry_run)
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
    if len(holders) < 2 and not (args.dry_run and args.create_clients):
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
