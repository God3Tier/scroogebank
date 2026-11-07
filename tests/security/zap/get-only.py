"""Copy the public API's OpenAPI specs with only their GET operations.

ZAP's OpenAPI import sends a request for every operation it finds, including
POST, PUT and DELETE with made-up bodies, so a passive scan of the full specs
would still write records. The copies keep only the reads under /api; the
internal routes (/internal, /ownership, /aggregates) are kept too, since the
scan should show they are not reachable from outside.

usage: python get-only.py <docs/api dir> <out dir>   (needs PyYAML)
"""
import pathlib
import sys

import yaml

PUBLIC = ["accounts-api.yaml", "audit-activity.yaml", "clients-api.yaml", "corrupted-logs-api.yaml",
          "dashboard-api.yaml", "engagement-api.yaml", "openapi.yaml", "transactions-api.yaml", "users-api.yaml"]
METHODS = {"get", "put", "post", "delete", "patch", "head", "options", "trace"}

src, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
for name in PUBLIC:
    spec = yaml.safe_load((src / name).read_text(encoding="utf-8"))
    kept = {}
    for path, item in spec["paths"].items():
        ops = {k: v for k, v in item.items() if k not in METHODS or k == "get"}
        if "get" in ops:
            kept[path] = ops
    spec["paths"] = kept
    (out / name).write_text(yaml.safe_dump(spec, sort_keys=False), encoding="utf-8")
    print(f"{name}: {len(kept)} GET paths")
