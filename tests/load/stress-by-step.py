"""Cut a stress.js run's k6 CSV into its steps, overall and per route.

k6's HTML report and summary cover the whole run, so they cannot show where
response times start to rise. This reads the run's CSV output (k6 run
--out csv=...) and cuts it at the steps stress.js ramps through: STEPS steps of
a 30 s ramp and a STEP_HOLD hold, from the start of the run (its setup), each
step counting the ramp into it. The final ramp-down is left out.

For each step it writes one row for all routes, with the failed requests, and
one row per route (k6's name tag): requests, median, p95 and p99 in ms.
Percentiles interpolate between the closest ranks, as numpy's default does.

usage: python tests/load/stress-by-step.py <run.csv.gz> <out.csv> [--steps 4] [--hold 180]
"""
import argparse
import csv
import gzip
from collections import defaultdict


def percentile(values, p):
    """The p-th percentile of sorted values."""
    k = (len(values) - 1) * p / 100
    lo = int(k)
    hi = min(lo + 1, len(values) - 1)
    return values[lo] + (values[hi] - values[lo]) * (k - lo)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('run', help="the run's k6 CSV (gzipped)")
    parser.add_argument('out', help='the CSV to write')
    parser.add_argument('--steps', type=int, default=4, help='STEPS the run used')
    parser.add_argument('--hold', type=int, default=180, help='STEP_HOLD the run used, in seconds')
    args = parser.parse_args()

    start = None
    samples = []
    with gzip.open(args.run, 'rt', newline='') as f:
        for row in csv.DictReader(f):
            t = int(row['timestamp'])
            start = t if start is None else min(start, t)
            if row['group'] != '::setup' and row['metric_name'] in ('http_req_duration', 'http_req_failed'):
                samples.append((t, row['metric_name'], row['name'], float(row['metric_value'])))

    step_seconds = 30 + args.hold
    durations = defaultdict(list)
    failed = defaultdict(int)
    for t, metric, route, value in samples:
        step = (t - start) // step_seconds
        if step >= args.steps:
            continue
        agents = (step + 1) * 100
        if metric == 'http_req_duration':
            durations[(agents, 'ALL')].append(value)
            durations[(agents, route)].append(value)
        elif value:
            failed[agents] += 1

    rows = [('virtual_agents', 'route', 'requests', 'median_ms', 'p95_ms', 'p99_ms', 'failed')]
    for agents, route in sorted(durations, key=lambda k: (k[0], k[1] != 'ALL', k[1])):
        values = sorted(durations[(agents, route)])
        median, p95, p99 = (round(percentile(values, p)) for p in (50, 95, 99))
        rows.append((agents, route, len(values), median, p95, p99, failed[agents] if route == 'ALL' else ''))
    with open(args.out, 'w', newline='') as f:
        csv.writer(f, lineterminator='\n').writerows(rows)
    print(f'wrote {args.out}: {len(rows) - 1} rows')


if __name__ == '__main__':
    main()
