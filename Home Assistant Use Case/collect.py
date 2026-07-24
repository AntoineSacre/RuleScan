#!/usr/bin/env python3
"""
CRA Annex I automated data collection for GitHub-hosted open-source projects.

Usage:
    ./run.sh                    run with Home Assistant defaults
    source config.env && python3 collect.py   run manually after setting env vars

Output:
    A JSON data file in generated-json/ ready to submit to the assessment engine.
"""

import json
import subprocess
import os
import sys
from datetime import datetime
from pathlib import Path

SCRIPT_DIR        = Path(__file__).parent.resolve()
COLLECTION_METHOD = SCRIPT_DIR / "collection-method-automated.json"
MANUAL_VALUES     = SCRIPT_DIR / "collection-method-manual.json"
OUTPUT_DIR        = SCRIPT_DIR / "output"


def check_env():
    required = ["OWNER", "REPO", "ORG", "ASSESSMENT_DATE"]
    missing = [v for v in required if not os.environ.get(v)]
    if missing:
        print(f"ERROR: Missing required environment variables: {missing}")
        print("Hint: run  source config.env  before running this script.")
        sys.exit(1)


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
    return proc.stdout.strip()


def parse_value(raw):
    if raw.lower() == "true":
        return "true"
    if raw.lower() == "false":
        return "false"
    if raw.lstrip("-").isdigit():
        return int(raw)
    return raw


def main():
    check_env()

    if not COLLECTION_METHOD.exists():
        print(f"ERROR: Collection method not found at {COLLECTION_METHOD}")
        sys.exit(1)

    with open(COLLECTION_METHOD) as f:
        method = json.load(f)

    with open(MANUAL_VALUES) as f:
        manual = json.load(f)

    owner = os.environ["OWNER"]
    repo  = os.environ["REPO"]
    print(f"\nCRA Annex I data collection — {owner}/{repo}")
    print("=" * 60)

    results   = {}
    errors    = []

    for operand in method["operands"]:
        name   = operand["name"]
        source = operand["source"]
        script = operand.get("script")

        if script is None:
            value = manual.get(name)
            if value is None:
                print(f"  [MISSING ] {name} — not in manual-values.json")
                errors.append(name)
            else:
                results[name] = value
                print(f"  [MANUAL  ] {name} = {value}")
            continue

        print(f"  [{source:7s}] {name} ... ", end="", flush=True)
        raw = run_script(script)

        if not raw:
            print("ERROR (empty output)")
            errors.append(name)
            continue

        value = parse_value(raw)
        results[name] = value
        print(value)

    print("=" * 60)
    print(f"Collected : {len(results)} / {len(method['operands'])} operands")
    if errors:
        print(f"Failed    : {errors}")

    OUTPUT_DIR.mkdir(exist_ok=True)
    ts       = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    out_path = OUTPUT_DIR / f"CRA-Data-{ts}.json"

    with open(out_path, "w") as f:
        json.dump({"operands": results}, f, indent=2)

    print(f"\nOutput    : {out_path}")
    print(f"Policy    : {SCRIPT_DIR / 'policy.json'}")
    print("Submit both files to the assessment engine to run the evaluation.")


if __name__ == "__main__":
    main()
