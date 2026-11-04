"""Create the test users for the load tests, ZAP scans and drills, and issue their tokens.

The specified load test signs in 100 agents and 5 admins, each as their own
user; the ZAP scans and the availability drills act as one dedicated agent,
zap.agent@example.com, with its own synthetic clients.

- create: makes the users directly in Cognito, with no invitation e-mails
  (Cognito's own sender allows 50 a day), adds each to its group, sets a
  random permanent password and enrols a TOTP authenticator: the token
  trigger grants the crm/api scope only to enrolled users (ADR-0076). It then
  inserts their rows in user-service's database with scripts/db-run-sql.sh.
- tokens: signs every user in and writes tokens.json, {"agents": [...],
  "admins": [...]}, for the k6 scripts' TOKENS_FILE, and zap.token.
- token: signs one user in and writes their access token to a file.

Access tokens last 60 minutes. Passwords and TOTP secrets are kept in the
--store file, and tokens are written beside it: keep that folder outside the
repository, and delete it when the deployment is torn down. Signing in from a
script needs ALLOW_ADMIN_USER_PASSWORD_AUTH on the web client, which the stack
does not enable: add it for the test only (tests/security/zap/README.md) and
never commit it. Needs the AWS CLI with credentials for the CRM account.

usage:
  python tests/test-users.py create --store <file> [--agents 100] [--admins 5] [--no-rows]
  python tests/test-users.py tokens --store <file>
  python tests/test-users.py token --store <file> --out <file> [--user zap.agent@example.com]
  All take --stack <crm stack dir> (the user pool and web client come from its
  outputs) or --pool and --client.
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import secrets
import struct
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor

ZAP_AGENT = 'zap.agent@example.com'
LOCK = threading.Lock()


def users(agents, admins):
    """(email, group, first name, last name) for every test user."""
    def surname(i):
        return f'Load{chr(65 + i // 26)}{chr(65 + i % 26)}'
    return ([(f'loadtest.agent{i:03d}@example.com', 'AGENT', 'Agent', surname(i)) for i in range(agents)]
            + [(f'loadtest.admin{i}@example.com', 'ADMIN', 'Admin', surname(i)) for i in range(admins)]
            + [(ZAP_AGENT, 'AGENT', 'Zap', 'Scanner')])


def run(*args):
    out = subprocess.run(list(args), capture_output=True, text=True)
    if out.returncode:
        raise SystemExit(f'{" ".join(args[:3])}: {out.stderr.strip()}')
    return out.stdout


def aws(*args):
    """Calls the AWS CLI, retrying while Cognito throttles."""
    for attempt in range(6):
        out = subprocess.run(['aws', *args, '--output', 'json'], capture_output=True, text=True)
        if out.returncode == 0:
            return json.loads(out.stdout) if out.stdout.strip() else {}
        if 'TooManyRequests' in out.stderr or 'Throttl' in out.stderr:
            time.sleep(1 + attempt)
            continue
        raise RuntimeError(f'aws {args[0]} {args[1]}: {out.stderr.strip()}')
    raise RuntimeError(f'aws {args[0]} {args[1]}: throttled')


def totp(secret, window):
    """The 6-digit TOTP code (RFC 6238, SHA-1, 30 s) for a time window."""
    key = base64.b32decode(secret + '=' * (-len(secret) % 8))
    digest = hmac.new(key, struct.pack('>Q', window), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    return f'{(struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1_000_000:06d}'


def load(path):
    return json.load(open(path)) if os.path.exists(path) else {}


def save(path, store):
    with LOCK:
        with open(path, 'w') as f:
            json.dump(store, f)
        os.chmod(path, 0o600)


def write_secret(path, text):
    with open(path, 'w') as f:
        f.write(text)
    os.chmod(path, 0o600)


def create_one(pool, client, user, store, path):
    email, group, first, last = user
    if store.get(email, {}).get('secret'):
        return
    try:
        attrs = aws('cognito-idp', 'admin-create-user', '--user-pool-id', pool, '--username', email,
                    '--message-action', 'SUPPRESS',
                    '--user-attributes', f'Name=email,Value={email}', 'Name=email_verified,Value=true',
                    f'Name=given_name,Value={first}', f'Name=family_name,Value={last}')['User']['Attributes']
    except RuntimeError as e:
        if 'UsernameExists' not in str(e):
            raise
        attrs = aws('cognito-idp', 'admin-get-user', '--user-pool-id', pool, '--username', email)['UserAttributes']
        # A secret lost with an earlier store cannot answer the MFA challenge, so enrol again.
        aws('cognito-idp', 'admin-set-user-mfa-preference', '--user-pool-id', pool, '--username', email,
            '--software-token-mfa-settings', 'Enabled=false,PreferredMfa=false')
    sub = next(a['Value'] for a in attrs if a['Name'] == 'sub')
    aws('cognito-idp', 'admin-add-user-to-group', '--user-pool-id', pool, '--username', email, '--group-name', group)
    password = 'Lt-' + secrets.token_urlsafe(18) + '9a!'
    aws('cognito-idp', 'admin-set-user-password', '--user-pool-id', pool, '--username', email,
        '--password', password, '--permanent')

    # MFA is optional in the pool (ADR-0076), so the first sign-in has no challenge, and the user
    # enrols with their own access token, as the app's enrolment page does.
    auth = aws('cognito-idp', 'admin-initiate-auth', '--user-pool-id', pool, '--client-id', client,
               '--auth-flow', 'ADMIN_USER_PASSWORD_AUTH', '--auth-parameters', f'USERNAME={email},PASSWORD={password}')
    if 'AuthenticationResult' not in auth:
        raise RuntimeError(f'{email}: expected tokens, got the {auth.get("ChallengeName")} challenge')
    access = auth['AuthenticationResult']['AccessToken']
    secret = aws('cognito-idp', 'associate-software-token', '--access-token', access)['SecretCode']
    window = int(time.time() // 30)
    verify = aws('cognito-idp', 'verify-software-token', '--access-token', access,
                 '--user-code', totp(secret, window), '--friendly-device-name', 'test')
    if verify.get('Status') != 'SUCCESS':
        raise RuntimeError(f'{email}: the TOTP code was not accepted')
    aws('cognito-idp', 'set-user-mfa-preference', '--access-token', access,
        '--software-token-mfa-settings', 'Enabled=true,PreferredMfa=true')
    with LOCK:
        store[email] = {'password': password, 'secret': secret, 'sub': sub, 'group': group, 'first': first,
                        'last': last, 'last_window': window}
    save(path, store)


def create(pool, client, args):
    store = load(args.store)
    wanted = users(args.agents, args.admins)
    with ThreadPoolExecutor(8) as ex:
        list(ex.map(lambda u: create_one(pool, client, u, store, args.store), wanted))
    print(f'{len(wanted)} users enrolled')
    if args.no_rows:
        return

    # user-service's rows, in files small enough for one db-run-sql.sh run each.
    os.makedirs('out/test-users', exist_ok=True)
    rows = [f"('{store[e]['sub']}','{store[e]['first']}','{store[e]['last']}','{e}','{store[e]['group']}',"
            f"'{store[e]['sub']}')" for e, *_ in wanted]
    for n in range(0, len(rows), 35):
        path = f'out/test-users/users-insert-{n // 35}.sql'
        with open(path, 'w') as f:
            f.write('INSERT INTO users (id, first_name, last_name, email, role, enabled, deletable, idp_sub, '
                    'created_at, updated_at)\nSELECT v.id::uuid, v.f, v.l, v.e, v.r, true, true, v.s, now(), now() '
                    'FROM (VALUES\n' + ',\n'.join(rows[n:n + 35]) + '\n) AS v(id, f, l, e, r, s)\n'
                    'ON CONFLICT DO NOTHING;\n')
        print(f'inserting {path}')
        subprocess.run(['bash', 'scripts/db-run-sql.sh', 'users', path, args.stack], check=True)


def token_for(pool, client, email, user, path, store):
    for _ in range(4):
        # Cognito refuses a code it has already accepted, so never reuse a time window.
        window = int(time.time() // 30)
        if window <= user['last_window']:
            time.sleep(30.5 - time.time() % 30)
            continue
        auth = aws('cognito-idp', 'admin-initiate-auth', '--user-pool-id', pool, '--client-id', client,
                   '--auth-flow', 'ADMIN_USER_PASSWORD_AUTH',
                   '--auth-parameters', f'USERNAME={email},PASSWORD={user["password"]}')
        if 'AuthenticationResult' not in auth:
            with LOCK:
                user['last_window'] = window
            save(path, store)
            try:
                auth = aws('cognito-idp', 'admin-respond-to-auth-challenge', '--user-pool-id', pool,
                           '--client-id', client, '--challenge-name', auth['ChallengeName'], '--session',
                           auth['Session'], '--challenge-responses',
                           f'USERNAME={email},SOFTWARE_TOKEN_MFA_CODE={totp(user["secret"], window)}')
            except RuntimeError as e:
                if any(code in str(e) for code in ('CodeMismatch', 'ExpiredCode', 'NotAuthorized')):
                    continue
                raise
        return auth['AuthenticationResult']['AccessToken']
    raise RuntimeError(f'{email}: no token after 4 attempts')


def tokens(pool, client, args):
    store = load(args.store)
    if not store:
        raise SystemExit(f'no users in {args.store}: run create first')
    emails = sorted(store)
    with ThreadPoolExecutor(8) as ex:
        got = dict(zip(emails, ex.map(lambda e: token_for(pool, client, e, store[e], args.store, store), emails)))
    folder = os.path.dirname(os.path.abspath(args.store))
    agents = [got[e] for e in emails if e.startswith('loadtest.agent')]
    admins = [got[e] for e in emails if e.startswith('loadtest.admin')]
    write_secret(os.path.join(folder, 'tokens.json'), json.dumps({'agents': agents, 'admins': admins}))
    if ZAP_AGENT in got:
        write_secret(os.path.join(folder, 'zap.token'), got[ZAP_AGENT])
    print(f'tokens.json ({len(agents)} agents, {len(admins)} admins) and zap.token written to {folder} at '
          f'{time.strftime("%H:%M", time.gmtime())} UTC')


def token(pool, client, args):
    store = load(args.store)
    if args.user not in store:
        raise SystemExit(f'no {args.user} in {args.store}: run create first')
    write_secret(args.out, token_for(pool, client, args.user, store[args.user], args.store, store))
    print(f'token for {args.user} written to {args.out} at {time.strftime("%H:%M:%S", time.gmtime())} UTC')


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('command', choices=['create', 'tokens', 'token'])
    parser.add_argument('--store', required=True, help='passwords and TOTP secrets (outside the repository)')
    parser.add_argument('--agents', type=int, default=100, help='create: load-test agents')
    parser.add_argument('--admins', type=int, default=5, help='create: load-test admins')
    parser.add_argument('--no-rows', action='store_true', help="create: skip user-service's rows")
    parser.add_argument('--user', default=ZAP_AGENT, help='token: whose token')
    parser.add_argument('--out', help='token: the file to write the token to')
    parser.add_argument('--stack', default='infrastructure/terraform/stacks/crm', help='the crm stack, for its outputs')
    parser.add_argument('--pool', help='the user pool ID (default: the stack output)')
    parser.add_argument('--client', help="the web client's ID (default: the stack output)")
    args = parser.parse_args()
    if args.command == 'token' and not args.out:
        parser.error('token needs --out')

    pool = args.pool or run('terraform', f'-chdir={args.stack}', 'output', '-raw', 'cognito_user_pool_id').strip()
    client = args.client or json.loads(run('terraform', f'-chdir={args.stack}', 'output', '-json',
                                           'frontend_build_env'))['VITE_COGNITO_CLIENT_ID']
    {'create': create, 'tokens': tokens, 'token': token}[args.command](pool, client, args)


if __name__ == '__main__':
    main()
