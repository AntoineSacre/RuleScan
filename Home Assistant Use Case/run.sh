#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OPA_URL="http://localhost:8181/v1/data"

# Load project configuration
source "$DIR/config.env"

# ── Step 1: collect data ─────────────────────────────────────────────────────
python3 "$DIR/collect.py" "$@"

# Pick up the most recently written data file
DATA_FILE=$(ls -t "$DIR/output"/CRA-Data-*.json 2>/dev/null | head -1)
if [[ -z "$DATA_FILE" ]]; then
    echo "ERROR: No data file found in output/  — collection may have failed."
    exit 1
fi

# ── Step 2: assess (requires OPA on port 8181) ───────────────────────────────
if ! curl -sf --max-time 2 "$OPA_URL" > /dev/null 2>&1; then
    echo ""
    echo "NOTE: OPA is not reachable at $OPA_URL — skipping assessment."
    echo "      Start OPA with:  docker compose up -d   (from the repository root)"
    exit 0
fi

POLICY_UUID=$(python3 -c "import json; print(json.load(open('$DIR/policy.json'))['uuid'])")

echo ""
echo "Assessment"
echo "============================================================"

# Upload policy
STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -X PUT "$OPA_URL/policies/$POLICY_UUID" \
    -H "Content-Type: application/json" \
    -d @"$DIR/policy.json")

if [[ "$STATUS" != "204" && "$STATUS" != "200" ]]; then
    echo "ERROR: Could not upload policy to OPA (HTTP $STATUS)"
    exit 1
fi

# Build OPA input: wrap collected operands with the transaction ID
OPA_INPUT=$(python3 -c "
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
payload = {'input': {'transactionId': sys.argv[2], 'operands': data['operands']}}
print(json.dumps(payload))
" "$DATA_FILE" "$POLICY_UUID")

# Evaluate and display
RESULT_FILE=$(mktemp)
curl -s -X POST "$OPA_URL/odrl/authz" \
    -H "Content-Type: application/json" \
    -d "$OPA_INPUT" > "$RESULT_FILE"

python3 - "$RESULT_FILE" <<'PYEOF'
import json, sys

with open(sys.argv[1]) as f:
    raw = json.load(f)

r        = raw.get("result", {})
allow    = r.get("allow", False)
active   = sorted(r.get("active_rules", []))
violated = sorted(r.get("violated_rules", []))
sat      = [x for x in active if x not in violated]

print(f"Verdict   : {'COMPLIANT' if allow else 'NON-COMPLIANT'}")
print(f"Active    : {len(active)} rules triggered")
print(f"Satisfied : {len(sat)}")
print(f"Violated  : {len(violated)}")

if violated:
    print()
    print("Violated obligations:")
    for v in violated:
        print(f"  - {v}")

print("============================================================")
PYEOF

rm -f "$RESULT_FILE"
