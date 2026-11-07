"""What the probe saw during a drill: requests, failures, and the outage.

Reads poller.js's k6 CSV and writes the small CSV the drill reports keep as
evidence (unix_time,status,route). It prints the failures by status and the
outage, the longest run of failed requests without a success between them,
beside the drill's timeline (events.txt, "unix_time label" lines). Status 0
is a request with no answer: a timeout or a refused connection.

usage: python tests/drills/probe-report.py <probe.csv.gz> <out.csv> [--events <events.txt>]
"""
import argparse
import csv
import gzip
from collections import Counter
from datetime import datetime, timezone


def hms(t):
    return datetime.fromtimestamp(t, timezone.utc).strftime('%H:%M:%S')


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('probe', help="poller.js's CSV (k6 run --out csv=...)")
    parser.add_argument('out', help='the evidence CSV to write')
    parser.add_argument('--events', help="the drill's timeline")
    args = parser.parse_args()

    rows = []
    with gzip.open(args.probe, 'rt', newline='') as f:
        for r in csv.DictReader(f):
            if r['metric_name'] == 'http_reqs' and r['group'] != '::setup':
                rows.append((int(r['timestamp']), r['status'] or '0', r['name']))
    rows.sort()
    with open(args.out, 'w', newline='') as f:
        w = csv.writer(f, lineterminator='\n')
        w.writerow(['unix_time', 'status', 'route'])
        w.writerows(rows)
    if not rows:
        raise SystemExit('the probe recorded no requests')

    failed = [r for r in rows if r[1] != '200']
    print(f'{len(rows)} requests {hms(rows[0][0])} to {hms(rows[-1][0])} UTC, {len(failed)} failed')
    for status, n in Counter(r[1] for r in failed).most_common():
        print(f'  status {status}: {n}')

    # The longest run of failures with no success between them.
    longest, run = [], []
    for r in rows:
        if r[1] == '200':
            run = []
        else:
            run.append(r)
            if len(run) > len(longest):
                longest = list(run)
    if longest:
        print(f'outage: {longest[-1][0] - longest[0][0] + 1} s, {hms(longest[0][0])} to {hms(longest[-1][0])} UTC, '
              f'{len(longest)} failed requests in a row')
    else:
        print('outage: none')

    if args.events:
        timeline = [(int(t), label) for t, label in (line.rstrip('\n').split(' ', 1) for line in open(args.events))]
        if failed:
            timeline += [(failed[0][0], 'first failed request'), (failed[-1][0], 'last failed request')]
        print('timeline (UTC):')
        for t, label in sorted(timeline):
            print(f'  {hms(t)} {label}')


if __name__ == '__main__':
    main()
