#!/usr/bin/env python3
"""Bounded AgentCore Web Search spike for EDA-D1-01.

The script provisions only an IAM role, an IAM-authorized AgentCore Gateway, and
one Web Search 1.2.0 target in Tokyo. Invocation requires an operator attestation
that the query contains public market data only.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import urlparse


REGION = "ap-northeast-1"
PROTOCOL_VERSION = "2025-11-25"
CONNECTOR_VERSION = "1.2.0"
DEFAULT_DOMAINS = ("mas.gov.sg", "sgx.com")
DEFAULT_MAX_RESULTS = 5
MAX_QUERY_LENGTH = 200
STATE_FILE = Path(__file__).with_name(".spike-state.json")
EVIDENCE_DIR = Path(__file__).with_name("evidence")
SAFE_QUERY = re.compile(r"^[A-Za-z0-9][A-Za-z0-9 .,&()'/:+-]*$")
FORBIDDEN_PRIVATE_TERMS = (
    "client id",
    "client name",
    "account number",
    "holding",
    "exposure",
    "internal note",
    "agent id",
    "audit id",
)


@dataclass(frozen=True)
class SpikeState:
    region: str
    account_id: str
    role_name: str
    role_arn: str
    gateway_id: str
    gateway_arn: str
    gateway_url: str
    target_id: str
    target_name: str


def aws_modules() -> tuple[Any, Any, Any]:
    try:
        import boto3
        from botocore.auth import SigV4Auth
        from botocore.awsrequest import AWSRequest
    except ImportError as exc:
        raise SystemExit(
            "Missing AWS SDK. Run: python -m pip install -r requirements.txt"
        ) from exc
    return boto3, SigV4Auth, AWSRequest


def validate_query(query: str) -> str:
    normalized = " ".join(query.split())
    if not normalized or len(normalized) > MAX_QUERY_LENGTH:
        raise ValueError("query must contain 1-200 characters")
    lowered = normalized.casefold()
    if any(term in lowered for term in FORBIDDEN_PRIVATE_TERMS):
        raise ValueError("query appears to contain a prohibited private-data field")
    if not SAFE_QUERY.fullmatch(normalized):
        raise ValueError("query contains unsupported characters")
    return normalized


def validate_domains(domains: Iterable[str]) -> list[str]:
    approved = set(DEFAULT_DOMAINS)
    normalized = sorted({domain.strip().lower() for domain in domains if domain.strip()})
    if not normalized or not set(normalized).issubset(approved):
        raise ValueError(f"domains must be a non-empty subset of {sorted(approved)}")
    return normalized


def validate_utc(value: str) -> str:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None or parsed.utcoffset() != timezone.utc.utcoffset(parsed):
        raise ValueError("date bounds must be ISO-8601 UTC values")
    return parsed.isoformat().replace("+00:00", "Z")


def wait_for_status(fetch: Any, ready: set[str], failed: set[str], timeout: int = 300) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        response = fetch()
        status = str(response.get("status", "")).upper()
        if status in ready:
            return response
        if status in failed:
            raise RuntimeError(f"resource entered terminal status {status}: {response}")
        time.sleep(5)
    raise TimeoutError(f"resource did not reach {sorted(ready)} within {timeout}s")


def wait_until_deleted(fetch: Any, timeout: int = 300) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            response = fetch()
        except Exception as exc:
            code = getattr(exc, "response", {}).get("Error", {}).get("Code")
            if code in {"ResourceNotFoundException", "NotFoundException"}:
                return
            raise
        status = str(response.get("status", "")).upper()
        if status == "FAILED":
            raise RuntimeError(f"resource deletion failed: {response}")
        time.sleep(5)
    raise TimeoutError(f"resource still existed after {timeout}s")


def provision(args: argparse.Namespace) -> None:
    if STATE_FILE.exists():
        raise SystemExit(f"Refusing to provision over existing state: {STATE_FILE}")

    boto3, _, _ = aws_modules()
    session = boto3.Session(profile_name=args.profile, region_name=REGION)
    sts = session.client("sts")
    iam = session.client("iam")
    control = session.client("bedrock-agentcore-control")
    account_id = sts.get_caller_identity()["Account"]
    role_name = args.role_name

    gateway_pattern = f"arn:aws:bedrock-agentcore:{REGION}:{account_id}:gateway/*"
    trust = {
        "Version": "2012-10-17",
        "Statement": [{
            "Sid": "AllowAgentCoreGateway",
            "Effect": "Allow",
            "Principal": {"Service": "bedrock-agentcore.amazonaws.com"},
            "Action": "sts:AssumeRole",
            "Condition": {
                "StringEquals": {"aws:SourceAccount": account_id},
                "ArnLike": {"aws:SourceArn": gateway_pattern},
            },
        }],
    }
    role = iam.create_role(
        RoleName=role_name,
        AssumeRolePolicyDocument=json.dumps(trust),
        Description="Temporary EDA-D1-01 AgentCore Web Search spike role",
        Tags=[
            {"Key": "Project", "Value": "CS301"},
            {"Key": "Feature", "Value": "event-driven-alerts"},
            {"Key": "Owner", "Value": "Azeez"},
            {"Key": "Purpose", "Value": "search-spike"},
        ],
    )["Role"]
    role_arn = role["Arn"]
    permissions = {
        "Version": "2012-10-17",
        "Statement": [
            {
                "Sid": "InvokeOnlyThisProjectsGateways",
                "Effect": "Allow",
                "Action": "bedrock-agentcore:InvokeGateway",
                "Resource": gateway_pattern,
            },
            {
                "Sid": "InvokeManagedWebSearch",
                "Effect": "Allow",
                "Action": "bedrock-agentcore:InvokeWebSearch",
                "Resource": f"arn:aws:bedrock-agentcore:{REGION}:aws:tool/web-search.v1",
            },
        ],
    }
    iam.put_role_policy(
        RoleName=role_name,
        PolicyName="InvokeAgentCoreWebSearch",
        PolicyDocument=json.dumps(permissions),
    )

    try:
        gateway = control.create_gateway(
            name=args.gateway_name,
            description="Temporary public-data-only search spike for EDA-D1-01",
            roleArn=role_arn,
            protocolType="MCP",
            protocolConfiguration={"mcp": {"supportedVersions": [PROTOCOL_VERSION]}},
            authorizerType="AWS_IAM",
            tags={
                "Project": "CS301",
                "Feature": "event-driven-alerts",
                "Owner": "Azeez",
                "Purpose": "search-spike",
            },
        )
        gateway_id = gateway["gatewayId"]
        ready_gateway = wait_for_status(
            lambda: control.get_gateway(gatewayIdentifier=gateway_id),
            {"READY"},
            {"FAILED", "DELETING"},
        )
        gateway_arn = ready_gateway["gatewayArn"]
        gateway_url = ready_gateway["gatewayUrl"]

        target = control.create_gateway_target(
            gatewayIdentifier=gateway_id,
            name=args.target_name,
            description="Web Search 1.2.0 restricted to approved official domains",
            targetConfiguration={
                "mcp": {
                    "connector": {
                        "source": {"connectorId": "web-search", "version": CONNECTOR_VERSION},
                        "configurations": [{
                            "name": "WebSearch",
                            "parameterValues": {
                                "domainFilter": {"include": list(DEFAULT_DOMAINS)}
                            },
                        }],
                    }
                }
            },
            credentialProviderConfigurations=[{"credentialProviderType": "GATEWAY_IAM_ROLE"}],
        )
        target_id = target["targetId"]
        wait_for_status(
            lambda: control.get_gateway_target(
                gatewayIdentifier=gateway_id, targetId=target_id
            ),
            {"READY"},
            {"FAILED", "DELETING"},
        )
    except Exception:
        print(
            f"Provisioning failed. Remove partial resources, including IAM role {role_name}.",
            file=sys.stderr,
        )
        raise

    state = SpikeState(
        region=REGION,
        account_id=account_id,
        role_name=role_name,
        role_arn=role_arn,
        gateway_id=gateway_id,
        gateway_arn=gateway_arn,
        gateway_url=gateway_url,
        target_id=target_id,
        target_name=args.target_name,
    )
    STATE_FILE.write_text(json.dumps(asdict(state), indent=2) + "\n", encoding="utf-8")
    print(f"Provisioned {gateway_arn}; state written to {STATE_FILE}")


def signed_mcp_call(session: Any, url: str, body: dict[str, Any]) -> tuple[dict[str, Any], float]:
    _, SigV4Auth, AWSRequest = aws_modules()
    credentials = session.get_credentials()
    if credentials is None:
        raise RuntimeError("no AWS credentials are available")
    payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
    headers = {
        "Accept": "application/json, text/event-stream",
        "Content-Type": "application/json",
        "MCP-Protocol-Version": PROTOCOL_VERSION,
    }
    request = AWSRequest(method="POST", url=url, data=payload, headers=headers)
    SigV4Auth(credentials.get_frozen_credentials(), "bedrock-agentcore", REGION).add_auth(request)
    prepared = request.prepare()
    http_request = urllib.request.Request(
        prepared.url,
        data=payload,
        headers=dict(prepared.headers.items()),
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(http_request, timeout=45) as response:
            raw = response.read().decode("utf-8")
            content_type = response.headers.get("Content-Type", "")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Gateway returned HTTP {exc.code}: {detail}") from exc
    elapsed_ms = (time.perf_counter() - started) * 1000
    return parse_mcp_response(raw, content_type), elapsed_ms


def parse_mcp_response(raw: str, content_type: str) -> dict[str, Any]:
    if "text/event-stream" not in content_type:
        parsed = json.loads(raw)
        if not isinstance(parsed, dict):
            raise ValueError("MCP response must be a JSON object")
        return parsed
    events = []
    for line in raw.splitlines():
        if line.startswith("data:"):
            events.append(json.loads(line.removeprefix("data:").strip()))
    if not events or not isinstance(events[-1], dict):
        raise ValueError("MCP event stream contained no JSON response")
    return events[-1]


def extract_tools(response: dict[str, Any]) -> list[dict[str, Any]]:
    tools = response.get("result", {}).get("tools", [])
    if not isinstance(tools, list):
        raise ValueError("tools/list response did not contain a tools array")
    return [tool for tool in tools if isinstance(tool, dict)]


def select_web_search_tool(tools: list[dict[str, Any]]) -> str:
    matches = [
        str(tool.get("name"))
        for tool in tools
        if "websearch" in str(tool.get("name", "")).replace("_", "").casefold()
    ]
    if len(matches) != 1:
        raise ValueError(f"expected one WebSearch tool, found {matches}")
    return matches[0]


def sanitized_results(response: dict[str, Any]) -> list[dict[str, Any]]:
    content = response.get("result", {}).get("content", [])
    results: list[dict[str, Any]] = []
    for item in content if isinstance(content, list) else []:
        if not isinstance(item, dict) or item.get("type") != "text":
            continue
        try:
            value = json.loads(str(item.get("text", "{}")))
        except json.JSONDecodeError:
            continue
        for result in value.get("results", []) if isinstance(value, dict) else []:
            if not isinstance(result, dict):
                continue
            url = str(result.get("url", ""))
            results.append({
                "title": str(result.get("title", ""))[:300],
                "url": url,
                "domain": (urlparse(url).hostname or "").lower(),
                "publishedDate": result.get("publishedDate"),
            })
    return results


def domain_is_allowed(hostname: str, allowed_domains: Iterable[str]) -> bool:
    host = hostname.casefold().rstrip(".")
    return any(
        host == domain.casefold() or host.endswith("." + domain.casefold())
        for domain in allowed_domains
    )


def invoke(args: argparse.Namespace) -> None:
    state = SpikeState(**json.loads(STATE_FILE.read_text(encoding="utf-8")))
    query = validate_query(args.query)
    domains = validate_domains(args.domains.split(","))
    date_from = validate_utc(args.date_from)
    date_to = validate_utc(args.date_to)
    if date_from > date_to:
        raise ValueError("date-from must not be later than date-to")
    if not 1 <= args.max_results <= 10:
        raise ValueError("spike max-results must be between 1 and 10")

    boto3, _, _ = aws_modules()
    session = boto3.Session(profile_name=args.profile, region_name=state.region)
    endpoint = state.gateway_url.rstrip("/") + "/mcp"
    list_body = {"jsonrpc": "2.0", "id": "list-tools", "method": "tools/list", "params": {}}
    tools_response, discovery_ms = signed_mcp_call(session, endpoint, list_body)
    tool_name = select_web_search_tool(extract_tools(tools_response))
    call_body = {
        "jsonrpc": "2.0",
        "id": "search-spike",
        "method": "tools/call",
        "params": {
            "name": tool_name,
            "arguments": {
                "query": query,
                "maxResults": args.max_results,
                "filters": {
                    "domainFilter": {"include": domains},
                    "publishedDateFilter": {"from": date_from, "to": date_to},
                },
            },
        },
    }
    search_response, search_ms = signed_mcp_call(session, endpoint, call_body)
    if search_response.get("error"):
        raise RuntimeError(f"MCP search failed: {search_response['error']}")
    results = sanitized_results(search_response)
    unexpected = [
        item["domain"]
        for item in results
        if not domain_is_allowed(item["domain"], domains)
    ]
    if unexpected:
        raise RuntimeError(f"response contained domains outside request allowlist: {unexpected}")

    evidence = {
        "task": "EDA-D1-01",
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "sourceRegion": args.source_region,
        "targetRegion": state.region,
        "connectorVersion": CONNECTOR_VERSION,
        "query": query,
        "domains": domains,
        "publishedDateFilter": {"from": date_from, "to": date_to},
        "maxResults": args.max_results,
        "toolDiscoveryLatencyMs": round(discovery_ms, 2),
        "searchLatencyMs": round(search_ms, 2),
        "resultCount": len(results),
        "results": results,
        "searchRequests": 1,
        "listedSearchChargeUsd": 0.007,
        "actualBilledCostUsd": None,
        "actualCostStatus": "Pending AWS billing data; verify in Cost Explorer.",
        "operatorAttestedNoPrivateData": args.ack_public_data,
    }
    EVIDENCE_DIR.mkdir(exist_ok=True)
    destination = EVIDENCE_DIR / f"run-{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}.json"
    destination.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, indent=2))
    print(f"Sanitized evidence written to {destination}")


def teardown(args: argparse.Namespace) -> None:
    state = SpikeState(**json.loads(STATE_FILE.read_text(encoding="utf-8")))
    if not args.confirm or args.confirm != state.gateway_id:
        raise SystemExit(f"Pass --confirm {state.gateway_id} to delete only this spike's resources")
    boto3, _, _ = aws_modules()
    session = boto3.Session(profile_name=args.profile, region_name=state.region)
    control = session.client("bedrock-agentcore-control")
    iam = session.client("iam")
    control.delete_gateway_target(gatewayIdentifier=state.gateway_id, targetId=state.target_id)
    wait_until_deleted(
        lambda: control.get_gateway_target(
            gatewayIdentifier=state.gateway_id, targetId=state.target_id
        )
    )
    control.delete_gateway(gatewayIdentifier=state.gateway_id)
    wait_until_deleted(lambda: control.get_gateway(gatewayIdentifier=state.gateway_id))
    iam.delete_role_policy(RoleName=state.role_name, PolicyName="InvokeAgentCoreWebSearch")
    iam.delete_role(RoleName=state.role_name)
    STATE_FILE.unlink()
    print(f"Deleted gateway {state.gateway_id}, target {state.target_id}, and role {state.role_name}")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument("--profile", help="optional AWS CLI profile")
    commands = root.add_subparsers(dest="command", required=True)
    create = commands.add_parser("provision")
    create.add_argument("--gateway-name", default="eda-search-spike")
    create.add_argument("--target-name", default="approved-public-search")
    create.add_argument("--role-name", default="EdaSearchSpikeGatewayRole")
    create.set_defaults(action=provision)
    run = commands.add_parser("invoke")
    run.add_argument("--query", required=True)
    run.add_argument("--domains", default=",".join(DEFAULT_DOMAINS))
    run.add_argument("--date-from", required=True)
    run.add_argument("--date-to", required=True)
    run.add_argument("--max-results", type=int, default=DEFAULT_MAX_RESULTS)
    run.add_argument("--source-region", default="ap-southeast-1")
    run.add_argument(
        "--ack-public-data",
        action="store_true",
        required=True,
        help="attest that the query contains public market terms and no CRM/private data",
    )
    run.set_defaults(action=invoke)
    destroy = commands.add_parser("teardown")
    destroy.add_argument("--confirm", required=True)
    destroy.set_defaults(action=teardown)
    return root


def main() -> int:
    args = parser().parse_args()
    try:
        args.action(args)
        return 0
    except (ValueError, RuntimeError, TimeoutError, FileNotFoundError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
