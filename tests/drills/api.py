"""The CRM requests the drills make as the test agent: changes to audit, and a marker client.

- changes: creates clients, updates some of them and opens accounts for them,
  so the services write audit events while the Auditor is stopped. Each
  account opening also records the read of its client, so the audit queue
  receives more events than there were changes.
- marker-create: creates one client, "Failover Marker", committed before a
  database failover, and saves what was sent.
- marker-check: reads the marker back and compares every field that was sent.

All data is synthetic: example.com addresses and made-up names.

usage:
  python tests/drills/api.py changes --base-url <url> --token-file <file> [--clients 5] [--updates 5] [--accounts 10]
  python tests/drills/api.py marker-create --base-url <url> --token-file <file> --out <marker.json>
  python tests/drills/api.py marker-check --base-url <url> --token-file <file> --marker <marker.json>
"""
import argparse
import json
import secrets
import sys
import urllib.error
import urllib.request


def client_body(first, last):
    tag = secrets.token_hex(4)
    return {
        'first_name': first,
        'last_name': last,
        'date_of_birth': '1980-01-15T00:00:00Z',
        'gender': 'Female',
        'email': f'drill.{tag}@example.com',
        'phone_number': '+659' + ''.join(str(secrets.randbelow(10)) for _ in range(7)),
        'address': '1 Synthetic Street',
        'city': 'Singapore',
        'state': 'Singapore',
        'country': 'Singapore',
        'postal_code': '119999',
    }


class Api:
    def __init__(self, base_url, token):
        self.base_url = base_url.rstrip('/')
        self.token = token

    def call(self, method, path, body=None):
        """(status, parsed body or text) for one request."""
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base_url + path, data=data, method=method)
        req.add_header('Authorization', f'Bearer {self.token}')
        if data is not None:
            req.add_header('Content-Type', 'application/json')
        try:
            with urllib.request.urlopen(req, timeout=30) as res:
                status, text = res.status, res.read().decode()
        except urllib.error.HTTPError as e:
            status, text = e.code, e.read().decode(errors='replace')
        try:
            return status, json.loads(text)
        except ValueError:
            return status, text


def changes(api, args):
    made, failed = [], 0

    def check(what, status, expected):
        nonlocal failed
        ok = status == expected
        failed += not ok
        print(f'{what}: {status}' + ('' if ok else f' (expected {expected})'))
        return ok

    for n in range(args.clients):
        body = client_body('Drill', f'Change {n + 1}')
        status, res = api.call('POST', '/api/clients', body)
        if check(f'create client {n + 1}', status, 201):
            made.append((res['client_id'], body))
    for n, (client_id, body) in enumerate(made[:args.updates]):
        body = dict(body, address=f'{n + 2} Synthetic Street')
        status, _ = api.call('PUT', f'/api/clients/{client_id}', body)
        check(f'update client {n + 1}', status, 200)
    for n in range(args.accounts if made else 0):
        client_id = made[n % len(made)][0]
        account = {'client_id': client_id, 'account_type': 'SAVINGS', 'status': 'PENDING',
                   'initial_deposit': 1000 + n * 50, 'currency': 'SGD', 'branch_id': 'SG-LOADTEST'}
        status, _ = api.call('POST', '/api/accounts', account)
        check(f'open account {n + 1}', status, 201)

    total = args.clients + min(args.updates, len(made)) + (args.accounts if made else 0)
    print(f'{total - failed} of {total} changes succeeded')
    return 1 if failed or len(made) < args.clients else 0


def marker_create(api, args):
    body = client_body('Failover', 'Marker')
    status, res = api.call('POST', '/api/clients', body)
    if status != 201:
        print(f'marker not created: {status} {res}')
        return 1
    with open(args.out, 'w') as f:
        json.dump({'client_id': res['client_id'], 'sent': body}, f)
    print(f'marker client {res["client_id"]} created')
    return 0


def marker_check(api, args):
    marker = json.load(open(args.marker))
    status, got = api.call('GET', f'/api/clients/{marker["client_id"]}')
    if status != 200 or not isinstance(got, dict):
        print(f'marker client {marker["client_id"]}: {status} {got}')
        return 1
    def norm(key, value):
        # A date may come back in another format, so compare only its day.
        return str(value)[:10] if key == 'date_of_birth' else str(value)

    diff = [k for k, v in marker['sent'].items() if norm(k, got.get(k, '')) != norm(k, v)]
    if diff:
        print(f'marker client {marker["client_id"]} differs in: {", ".join(diff)}')
        return 1
    print(f'marker client {marker["client_id"]} read back intact ({len(marker["sent"])} fields)')
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('command', choices=['changes', 'marker-create', 'marker-check'])
    parser.add_argument('--base-url', required=True, help="the deployment's https:// address")
    parser.add_argument('--token-file', required=True, help="the test agent's access token")
    parser.add_argument('--clients', type=int, default=5, help='changes: clients to create')
    parser.add_argument('--updates', type=int, default=5, help='changes: of those, how many to update')
    parser.add_argument('--accounts', type=int, default=10, help='changes: accounts to open')
    parser.add_argument('--out', help="marker-create: where to save the marker")
    parser.add_argument('--marker', help='marker-check: the saved marker')
    args = parser.parse_args()
    if args.command == 'marker-create' and not args.out:
        parser.error('marker-create needs --out')
    if args.command == 'marker-check' and not args.marker:
        parser.error('marker-check needs --marker')
    api = Api(args.base_url, open(args.token_file).read().strip())
    sys.exit({'changes': changes, 'marker-create': marker_create, 'marker-check': marker_check}[args.command](api, args))


if __name__ == '__main__':
    main()
