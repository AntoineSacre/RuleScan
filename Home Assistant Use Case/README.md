# Home Assistant Core — CRA Annex I Use Case

This folder demonstrates a full CRA Annex I compliance assessment of
[Home Assistant Core](https://github.com/home-assistant/core) (version 2026.6.1)
using the automated collection method and assessment engine in this repository.
It is designed as a **template**: fork it, update `config.env` and
`manual-values.json`, and run `./run.sh` against any GitHub-hosted project.

---

## What it does

1. Reads the generic collection method (`src/main/resources/InfoCollectionMethod/CRA-GitHub.json`)
2. Executes the 26 automated heuristics against the GitHub API and repository structure
3. Loads the 4 pre-filled manual and regulatory operand values from `manual-values.json`
4. Writes a complete data file to `generated-json/` ready to submit to the assessment engine

---

## Prerequisites

- `bash`, `curl`, `jq`, `python3` (standard on macOS and most Linux distributions)
- A GitHub personal access token is optional but strongly recommended
  (unauthenticated requests are rate-limited to 60/hour)

---

## Quick start

```bash
# 1. Optional: set your GitHub token to avoid rate limiting
export GITHUB_TOKEN=ghp_...

# 2. Run the collection
cd "Home Assistant Use Case"
./run.sh
```

The script prints each operand and its derived value, then writes the output file:

```
CRA Annex I data collection — home-assistant/core
============================================================
  [A1     ] product/has-digital-element ... true
  [A1     ] product/has-available-security-updates ... true
  ...
  [MANUAL ] product/has-control-access-mecanism = false
  ...
============================================================
Collected : 29 / 29 operands
Output    : generated-json/CRA-Data-2026-06-11_14-32-01.json
Policy    : src/main/resources/policies/CRA.json
```

Submit the output file and the policy file to the assessment engine to get the compliance report.

---

## Adapting this template for another project

### 1. Update `config.env`

Set `OWNER`, `REPO`, and `ORG` to your project's GitHub identifiers, and update
the repository paths to match your project's structure:

| Parameter | What it points to | HA value |
|---|---|---|
| `CONFIG_ENTRIES_PATH` | Configuration entry point | `homeassistant/config_entries.py` |
| `AUTH_PATH` | Authentication module entry point | `homeassistant/components/auth/__init__.py` |
| `ONBOARDING_PATH` | First-run setup component | `homeassistant/components/onboarding` |
| `RECORDER_PATH` | Data persistence component | `homeassistant/components/recorder` |
| `CLOUD_INTEGRATIONS_PATH` | External service integrations directory | `homeassistant/components` |
| `API_PATH` | Network API component | `homeassistant/components/api` |
| `HTTP_PATH` | HTTP/TLS configuration module | `homeassistant/components/http` |
| `ANALYTICS_PATH` | Analytics/telemetry config file | `homeassistant/components/analytics/__init__.py` |
| `LOGBOOK_PATH` | Security event logging component | `homeassistant/components/logbook` |
| `COMPONENTS_DIR` | Integration subdirectory root | `homeassistant/components` |
| `CI_SECURITY_WORKFLOW` | CI security scanning workflow | `.github/workflows/codeql.yml` |
| `PYTHON_CONFIG` | Python project metadata file | `pyproject.toml` |

You can also adjust the three numeric thresholds:

| Parameter | Default | Meaning |
|---|---|---|
| `RECENCY_THRESHOLD_DAYS` | 90 | Max days since last release |
| `HIGH_CRITICAL_FRACTION_THRESHOLD` | 0.4 | Advisory severity threshold for cybersecurity-level |
| `ATTACK_SURFACE_THRESHOLD` | 500 | Max integration count for limited-attack-surfaces |

### 2. Update `manual-values.json`

Four operands cannot be derived automatically and must be assessed manually:

| Operand | How to determine |
|---|---|
| `product/has-control-access-mecanism` | Is MFA enforced by default? Can tokens be scoped? |
| `product/availability-of-essential-function` | Does the product have redundancy and a documented SLA? |
| `product/minimise-availability-issue-on-external-services` | Does the product work offline / local-first? |
| `values/appropriate-level` | CRA classification: 1=Low, 3=Moderate, 5=High |

### 3. Run `./run.sh`

---

## File structure

```
Home Assistant Use Case/
  run.sh                  entry point — sources config and runs collect.py
  collect.py              collection runner
  config.env              parameter values for Home Assistant Core
  manual-values.json      pre-filled manual and regulatory operands
  collection-method-automated.json   GitHub heuristics for all 26 automated operands
  collection-method-manual.json      pre-filled values for the 4 manual/regulatory operands
  policy.json                        CRA Annex I policy model (21 rules)
  output/                 generated data files (created on first run)
  README.md               this file
```
