# GitHub Collection Method

This collection method derives CRA Annex I input values from a public GitHub repository
using GitHub's REST API and file-presence checks.

It consists of 24 automated heuristics and 5 inputs that must be assessed manually.

---

## Files

| File | Purpose |
|---|---|
| `collect.py` | Python runner — executes heuristics and writes the data file |
| `collection-method.json` | 29 heuristic definitions (24 automated + 5 manually provided) |
| `config.env.template` | Template for use case configuration |

---

## Usage

This collection method is invoked by a use case's `run.sh`. To use it standalone:

```bash
# From any directory:
python3 collection-methods/github/collect.py \
    --collection-method collection-methods/github/collection-method.json \
    --manual-values     path/to/collection-method-manual.json \
    --output-dir        path/to/output/
```

Required environment variables (set via `source config.env` in the use case):

| Variable | Description |
|---|---|
| `OWNER` | GitHub organisation or user |
| `REPO` | Repository name |
| `ORG` | Organisation for org-level checks |
| `ASSESSMENT_DATE` | ISO date of the assessment |

See `config.env.template` for the full list of variables.

---

## Adapting to a new project

1. Copy `config.env.template` to your use case directory as `config.env`
2. Fill in the values for your project
3. Create a `collection-method-manual.json` with the 5 manually assessed inputs
4. Run via your use case's `run.sh` or call `collect.py` directly
