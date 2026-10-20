#!/usr/bin/env python3
"""
CRA Annex I automated data collection for GitHub-hosted open-source projects.

Usage:
    python3 collect.py \
        --collection-method /path/to/collection-method.json \
        --manual-values     /path/to/collection-method-manual.json \
        --output-dir        /path/to/output/

    All arguments are optional — defaults resolve relative to this script's directory.

Replay (no GitHub calls):
    python3 collect.py --replay /path/to/CRA-Evidence-*.json --output-dir /path/to/output/

Output:
    CRA-Data-{ts}.json     — flat operand map ready to submit to the assessment engine
    CRA-Evidence-{ts}.json — provenance record (script hash, endpoints, raw API responses,
                             computed values; sufficient for offline replay and audit)
"""

import argparse
import hashlib
import json
import re
import subprocess
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

SCRIPT_VERSION = "1.1.0"
SCRIPT_DIR = Path(__file__).parent.resolve()


def _script_id():
    sha = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()[:16]
    return f"{SCRIPT_VERSION}+sha256:{sha}"


def expand_url(template):
    """Expand $VAR references in a URL template from the current environment."""
    return re.sub(r'\$(\w+)', lambda m: os.environ.get(m.group(1), ''), template)


def fetch_evidence(url, token, cache):
    """Fetch url and return {status, body} dict; uses cache to avoid duplicate calls."""
    if url in cache:
        return cache[url]
    headers = {"Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req) as resp:
            body = json.loads(resp.read())
        result = {"status": resp.status, "body": body}
    except urllib.error.HTTPError as e:
        try:
            body = json.loads(e.read())
        except Exception:
            body = None
        result = {"status": e.code, "body": body}
    except Exception as exc:
        result = {"error": str(exc)}
    cache[url] = result
    return result


def parse_args():
    parser = argparse.ArgumentParser(description="CRA Annex I GitHub data collector")
    parser.add_argument("--collection-method", type=Path,
                        default=SCRIPT_DIR / "collection-method.json")
    parser.add_argument("--manual-values", type=Path,
                        default=SCRIPT_DIR / "collection-method-manual.json")
    parser.add_argument("--output-dir", type=Path,
                        default=SCRIPT_DIR / "output")
    parser.add_argument("--replay", type=Path, metavar="EVIDENCE_FILE",
                        help="Reconstruct data file from stored evidence without GitHub API calls")
    return parser.parse_args()


def resolve_commit_sha():
    owner = os.environ["OWNER"]
    repo  = os.environ["REPO"]
    tag   = os.environ["RELEASE_TAG"]
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    auth  = f'-H "Authorization: Bearer {token}"' if token else ""
    script = (f'curl -s {auth} '
              f'"https://api.github.com/repos/{owner}/{repo}/commits/{tag}" '
              f'| jq -r \'.sha // empty\'')
    proc = subprocess.run(["bash", "-c", script], capture_output=True, text=True)
    sha = proc.stdout.strip()
    if not sha:
        print(f"ERROR: Could not resolve COMMIT_SHA for release tag '{tag}'.")
        print("       Check RELEASE_TAG and ensure GITHUB_TOKEN is set if the repo is private.")
        sys.exit(1)
    return sha


def check_env():
    required = ["OWNER", "REPO", "ORG", "ASSESSMENT_DATE", "RELEASE_TAG"]
    missing = [v for v in required if not os.environ.get(v)]
    if missing:
        print(f"ERROR: Missing required environment variables: {missing}")
        print("Hint: run  source config.env  before running this script.")
        sys.exit(1)
    if not os.environ.get("COMMIT_SHA"):
        sha = resolve_commit_sha()
        os.environ["COMMIT_SHA"] = sha
        print(f"  [Resolved] COMMIT_SHA={sha}")


def run_script(script):
    # Sending "Authorization: Bearer " (empty) causes GitHub to return
    # "Bad credentials" on every request. Strip the header when no token is set.
    if not os.environ.get('GITHUB_TOKEN', '').strip():
        script = script.replace('-H "Authorization: Bearer $GITHUB_TOKEN"', '')
    proc = subprocess.run(
        ["bash", "-c", script],
        capture_output=True,
        text=True,
        env=os.environ.copy()
    )
    if proc.returncode != 0 and proc.stderr.strip():
        print(f"\n    WARNING: {proc.stderr.strip()}")
    raw = proc.stdout.strip()
    # Treat GitHub API error responses that leaked through as empty (missing).
    if raw.startswith("{") and '"message"' in raw:
        return ""
    return raw


def parse_value(raw):
    if raw.lower() == "true":
        return "true"
    if raw.lower() == "false":
        return "false"
    if raw.lstrip("-").isdigit():
        return int(raw)
    return raw


def replay(evidence_path, output_dir):
    with open(evidence_path) as f:
        ev = json.load(f)
    print(f"\nReplaying from: {evidence_path}")
    print("=" * 60)
    results = {}
    for name, entry in ev.get("operands", {}).items():
        cv = entry.get("computed_value")
        if cv is not None:
            results[name] = parse_value(str(cv))
            print(f"  [{entry.get('source','?'):7s}] {name} = {results[name]}")
        else:
            print(f"  [MISSING ] {name}")
    print("=" * 60)
    output_dir.mkdir(parents=True, exist_ok=True)
    ts       = datetime.now(timezone.utc).strftime("%Y-%m-%d_%H-%M-%S")
    out_path = output_dir / f"CRA-Data-{ts}.json"
    with open(out_path, "w") as f:
        json.dump({"operands": results}, f, indent=2)
    print(f"\nOutput    : {out_path}")


def main():
    args = parse_args()

    if args.replay:
        replay(args.replay, args.output_dir)
        return

    check_env()

    if not args.collection_method.exists():
        print(f"ERROR: Collection method not found at {args.collection_method}")
        sys.exit(1)

    with open(args.collection_method) as f:
        method = json.load(f)

    manual = {}
    if args.manual_values.exists():
        with open(args.manual_values) as f:
            manual = json.load(f)

    owner = os.environ["OWNER"]
    repo  = os.environ["REPO"]
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    print(f"\nCRA Annex I data collection — {owner}/{repo}")
    print("=" * 60)

    results         = {}
    errors          = []
    operands_ev     = {}
    evidence_cache  = {}

    for operand in method["operands"]:
        name   = operand["name"]
        source = operand["source"]
        script = operand.get("script")

        if script is None:
            value = manual.get(name)
            if value is None:
                print(f"  [MISSING ] {name} — not in manual values file")
                errors.append(name)
            else:
                results[name] = value
                operands_ev[name] = {"source": source, "computed_value": str(value)}
                print(f"  [MANUAL  ] {name} = {value}")
            continue

        print(f"  [{source:7s}] {name} ... ", end="", flush=True)
        raw = run_script(script)

        if not raw:
            print("MISSING (empty or error response)")
            errors.append(name)
            operands_ev[name] = {"source": source, "computed_value": None}
            continue

        value = parse_value(raw)
        results[name] = value
        print(value)

        ev_entry = {"source": source, "computed_value": raw}

        # Fetch raw API evidence for reproducibility auditing
        ev_urls = operand.get("evidence_url", [])
        if isinstance(ev_urls, str):
            ev_urls = [ev_urls]
        if ev_urls:
            endpoints = []
            responses = []
            for tpl in ev_urls:
                url = expand_url(tpl)
                resp = fetch_evidence(url, token, evidence_cache)
                endpoints.append(url)
                responses.append(resp)
            ev_entry["endpoints"] = endpoints
            ev_entry["responses"] = responses

        operands_ev[name] = ev_entry

    print("=" * 60)
    print(f"Collected : {len(results)} / {len(method['operands'])} inputs")
    if errors:
        print(f"Failed    : {errors}")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    ts       = datetime.now(timezone.utc).strftime("%Y-%m-%d_%H-%M-%S")
    out_path = args.output_dir / f"CRA-Data-{ts}.json"
    ev_path  = args.output_dir / f"CRA-Evidence-{ts}.json"

    with open(out_path, "w") as f:
        json.dump({"operands": results}, f, indent=2)

    def _file_sha256(path):
        return hashlib.sha256(Path(path).read_bytes()).hexdigest()

    def _git(cmd):
        r = subprocess.run(["git", "-C", str(SCRIPT_DIR)] + cmd,
                           capture_output=True, text=True)
        return r.stdout.strip() if r.returncode == 0 else None

    rulescan_commit = _git(["rev-parse", "HEAD"])
    porcelain       = _git(["status", "--porcelain"])
    rulescan_dirty  = bool(porcelain) if rulescan_commit is not None else None

    evidence = {
        "schema_version":          "2",
        "script_id":               _script_id(),
        "collection_timestamp":    datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "rulescan_commit":         rulescan_commit,
        "rulescan_dirty":          rulescan_dirty,
        "collection_method_sha256": _file_sha256(args.collection_method),
        "manual_values_sha256":    _file_sha256(args.manual_values) if args.manual_values.exists() else None,
        "owner":                   owner,
        "repo":                    repo,
        "release_tag":             os.environ.get("RELEASE_TAG", ""),
        "commit_sha":              os.environ.get("COMMIT_SHA", ""),
        "assessment_date":         os.environ["ASSESSMENT_DATE"],
        "collection_method":       str(args.collection_method),
        "operands":                operands_ev,
    }
    with open(ev_path, "w") as f:
        json.dump(evidence, f, indent=2)

    print(f"\nOutput    : {out_path}")
    print(f"Evidence  : {ev_path}")
    print("Submit the output file and the policy file to the assessment engine.")
    print("Use  --replay  with the evidence file to reconstruct this output offline.")


if __name__ == "__main__":
    main()
