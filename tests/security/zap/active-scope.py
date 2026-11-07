"""Copy the API's OpenAPI specs for an active ZAP scan, minus a few operations.

An active scan sends attack payloads to every operation it imports, writes
included, so it runs as a dedicated test agent against synthetic data. A few
operations are left out because each call has an effect outside the CRM that
a scan should not cause:

- POST /api/engagement/drafts asks Bedrock for an e-mail draft (cost on each call).
- POST /api/engagement/drafts/{draftId}/send sends e-mail through SES.
- POST /api/users/me/logout signs the scanning agent out everywhere, which
  would turn the rest of the scan into 401s.

usage: python active-scope.py <docs/api dir> <out dir> [--internal]   (needs PyYAML)
    --internal also copies the specs whose routes API Gateway does not
    publish (dashboard-aggregates.yaml), for a scan from inside the VPC.
"""
import pathlib
import sys

import yaml

PUBLIC = ["accounts-api.yaml", "audit-activity.yaml", "clients-api.yaml", "corrupted-logs-api.yaml",
          "dashboard-api.yaml", "engagement-api.yaml", "openapi.yaml", "transactions-api.yaml", "users-api.yaml"]
INTERNAL = ["dashboard-aggregates.yaml"]
EXCLUDED = {
    ("/api/engagement/drafts", "post"): "asks Bedrock for a draft",
    ("/api/engagement/drafts/{draftId}/send", "post"): "sends e-mail through SES",
    ("/api/users/me/logout", "post"): "signs the scanning agent out",
}

args = [a for a in sys.argv[1:] if not a.startswith("--")]
src, out = pathlib.Path(args[0]), pathlib.Path(args[1])
names = PUBLIC + (INTERNAL if "--internal" in sys.argv else [])
out.mkdir(parents=True, exist_ok=True)
for name in names:
    spec = yaml.safe_load((src / name).read_text(encoding="utf-8"))
    for (path, method), reason in EXCLUDED.items():
        if spec.get("paths", {}).get(path, {}).pop(method, None) is not None:
            print(f"{name}: left out {method.upper()} {path} ({reason})")
    (out / name).write_text(yaml.safe_dump(spec, sort_keys=False), encoding="utf-8")
    print(f"{name}: {len(spec['paths'])} paths")
