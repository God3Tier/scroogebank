"""Check that no audit event was lost during a test run.

Every service that writes audit events logs the ones it loses, so a run lost
none if, between <start> and <end>, the services' logs hold neither of:
- "AUDIT_SEND_FAILED": neither the outbox write nor the direct send to the
  audit queue worked (ADR-0078); the line holds the whole event, to send again;
- "audit event not saved" (client- and account-service) or "could not save
  transaction read audit" (transaction-service): the request's event was not
  saved, as images before ADR-0078 logged every dropped event.
It also counts the events that took the fallback straight to the queue (not
lost, but a sign the outbox was short of connections), and checks that the
audit dead-letter queue is empty. With --rows, it lists the audit rows written
in the window per service and action, from the audit database, through
scripts/db-run-sql.sh: run it once the audit queue has drained.

Exits 1 if any event was lost or the dead-letter queue holds a message.

usage: python tests/load/audit-check.py <start ISO> <end ISO> [--rows] [--services ...] [--stack <dir>]
  e.g. python tests/load/audit-check.py 2026-11-03T05:20:00Z 2026-11-03T05:23:00Z
Needs terraform and the AWS CLI with credentials for the CRM account.
"""
import argparse
import json
import os
import subprocess
from datetime import datetime

NOT_SAVED = ['audit event not saved', 'could not save transaction read audit',
             'could not encode transaction read audit']
FALLBACK = ['sent straight to the queue', 'sent directly because']


def run(*args):
    out = subprocess.run(list(args), capture_output=True, text=True)
    if out.returncode:
        raise SystemExit(f'{" ".join(args[:4])}: {out.stderr.strip()}')
    return out.stdout


def millis(iso):
    return int(datetime.fromisoformat(iso.replace('Z', '+00:00')).timestamp() * 1000)


def count(log_group, phrase, start, end):
    """The log events in the window holding the phrase, and the first of them."""
    events = json.loads(run('aws', 'logs', 'filter-log-events', '--log-group-name', log_group,
                            '--start-time', str(start), '--end-time', str(end),
                            '--filter-pattern', f'"{phrase}"', '--query', 'events[].message', '--output', 'json'))
    return len(events or []), (events or [''])[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('start', help='the run start, e.g. 2026-11-03T05:20:00Z')
    parser.add_argument('end', help='the run end')
    parser.add_argument('--services', nargs='+', default=['client-service', 'account-service', 'transaction-service'])
    parser.add_argument('--rows', action='store_true', help='also list the audit rows written in the window')
    parser.add_argument('--stack', default='infrastructure/terraform/stacks/crm', help='the crm stack, for its outputs')
    args = parser.parse_args()
    start, end = millis(args.start), millis(args.end)
    outputs = json.loads(run('terraform', f'-chdir={args.stack}', 'output', '-json'))
    cluster = outputs['ecs_cluster']['value']

    lost = 0
    print(f'{"service":<22}{"lost":>8}{"not saved":>11}{"fallback":>10}')
    for service in args.services:
        group = f'/ecs/{cluster}-{service}'
        failed, sample = count(group, 'AUDIT_SEND_FAILED', start, end)
        not_saved, other = 0, ''
        for phrase in NOT_SAVED:
            n, line = count(group, phrase, start, end)
            not_saved, other = not_saved + n, other or line
        fallback = sum(count(group, p, start, end)[0] for p in FALLBACK)
        lost += max(failed, not_saved)
        print(f'{service:<22}{failed:>8}{not_saved:>11}{fallback:>10}')
        for line in (sample, other):
            if line:
                print(f'  e.g. {line[:300]}')

    queues = {}
    for name in ('audit_queue_url', 'audit_dlq_url'):
        attrs = json.loads(run('aws', 'sqs', 'get-queue-attributes', '--queue-url', outputs[name]['value'],
                               '--attribute-names', 'ApproximateNumberOfMessages',
                               'ApproximateNumberOfMessagesNotVisible', '--output', 'json'))['Attributes']
        queues[name] = sum(int(v) for v in attrs.values())
    print(f'audit queue: {queues["audit_queue_url"]} waiting; dead-letter queue: {queues["audit_dlq_url"]}')

    if args.rows:
        if queues['audit_queue_url']:
            print('the audit queue has not drained yet, so the counts below may be short')
        os.makedirs('out/audit-check', exist_ok=True)
        path = 'out/audit-check/rows.sql'
        with open(path, 'w') as f:
            f.write("SELECT source_service, action, count(*) AS events, count(DISTINCT event_id) AS distinct_ids\n"
                    f"FROM audit_log WHERE timestamp >= '{args.start}' AND timestamp < '{args.end}'\n"
                    "GROUP BY 1, 2 ORDER BY 1, 2;\n")
        subprocess.run(['bash', 'scripts/db-run-sql.sh', 'audit', path, args.stack], check=True)

    if lost or queues['audit_dlq_url']:
        print(f'FAIL: {lost} audit events lost, {queues["audit_dlq_url"]} in the dead-letter queue')
        raise SystemExit(1)
    print('OK: no audit event lost')


if __name__ == '__main__':
    main()
