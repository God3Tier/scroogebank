"""What the WAF blocked during a scan through the edge, for its report.

Writes two files to <out dir>:
- waf-counts.csv: blocked and allowed requests per rule, from the WAF's
  CloudWatch metrics (AWS/WAFV2, us-east-1). If the account cannot read
  CloudWatch in us-east-1, as when an SCP denies it, the file is left empty.
- waf-sampled-blocks.csv: the WAF's sampled blocked requests, per rule group
  and the rule inside it that matched, weighted to estimate counts.

AWS keeps sampled requests for only 3 hours, so run this within 3 hours of
the scan. The window is the one edge.sh writes to window.txt.

usage: python tests/security/zap/waf-blocks.py <start ISO> <end ISO> <out dir> [--acl crm-sg-prod]
  e.g. python tests/security/zap/waf-blocks.py $(cut -d' ' -f2 out/zap/edge-1/window.txt) out/zap/edge-1
Needs the AWS CLI with credentials for the CRM account.
"""
import argparse
import collections
import json
import os
import subprocess

# The rules' metric names in stacks/crm/edge.tf, after the web ACL's name.
RULES = ['common', 'large-body', 'bad-inputs', 'sqli', 'ip-reputation']


def aws(*args):
    out = subprocess.run(['aws', *args, '--region', 'us-east-1', '--output', 'json'], capture_output=True, text=True)
    if out.returncode:
        raise RuntimeError(out.stderr.strip())
    return json.loads(out.stdout or '{}')


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('start', help='the window start, e.g. 2026-11-03T05:49:00Z')
    parser.add_argument('end', help='the window end')
    parser.add_argument('out', help='the directory to write to')
    parser.add_argument('--acl', default='crm-sg-prod', help="the web ACL's name (the crm stack's name)")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    rules = [f'{args.acl}-{r}' for r in RULES]

    counts = []
    try:
        for metric in ('BlockedRequests', 'AllowedRequests'):
            for rule in ['ALL', *rules]:
                points = aws('cloudwatch', 'get-metric-statistics', '--namespace', 'AWS/WAFV2', '--metric-name', metric,
                             '--dimensions', f'Name=WebACL,Value={args.acl}', f'Name=Rule,Value={rule}',
                             '--start-time', args.start, '--end-time', args.end, '--period', '300',
                             '--statistics', 'Sum')['Datapoints']
                total = int(sum(p['Sum'] for p in points))
                if total:
                    counts.append((metric, rule, total))
    except RuntimeError as e:
        print(f'no CloudWatch counts: {(str(e).splitlines() or ["denied"])[0]}')
        counts = []
    with open(os.path.join(args.out, 'waf-counts.csv'), 'w') as f:
        f.write('metric,rule,requests\n' + ''.join(f'{m},{r},{n}\n' for m, r, n in counts))

    acl = next((a for a in aws('wafv2', 'list-web-acls', '--scope', 'CLOUDFRONT')['WebACLs'] if a['Name'] == args.acl), None)
    if not acl:
        raise SystemExit(f'no web ACL named {args.acl}')
    blocked = collections.Counter()
    for rule in rules:
        sampled = aws('wafv2', 'get-sampled-requests', '--web-acl-arn', acl['ARN'], '--rule-metric-name', rule,
                      '--scope', 'CLOUDFRONT', '--time-window', f'StartTime={args.start},EndTime={args.end}',
                      '--max-items', '500')
        for r in sampled.get('SampledRequests', []):
            if r.get('Action') == 'BLOCK':
                blocked[f'{rule}: {r.get("RuleNameWithinRuleGroup") or rule}'] += r.get('Weight', 1)
    with open(os.path.join(args.out, 'waf-sampled-blocks.csv'), 'w') as f:
        f.write('rule,sampled_blocked\n' + ''.join(f'{k},{v}\n' for k, v in blocked.most_common()))

    for m, r, n in counts:
        print(f'{m} {r}: {n}')
    for k, v in blocked.most_common():
        print(f'{k}: {v}')
    print(f'wrote waf-counts.csv and waf-sampled-blocks.csv to {args.out}')


if __name__ == '__main__':
    main()
