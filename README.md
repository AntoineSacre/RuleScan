# Policy-Driven Regulatory Compliance Assessment Engine

A compliance assessment engine for evaluating software products against machine-readable regulatory policies. Built on [Open Policy Agent (OPA)](https://www.openpolicyagent.org/), it uses a custom JSON policy format with obligations, permissions, pre-conditions, and predicate constraints.

This repository accompanies the paper:

> **Automated Regulatory Compliance Assessment for Open-Source Software: A Policy-Driven Approach Applied to the Cyber Resilience Act**
> Antoine Sacré, Dara Tith, Benoit Hosselet, Jean-Noël Colin
> University of Namur / Comexis Partners, 2026

---

## What it does

The engine takes two inputs:

1. **A policy file** — a regulatory framework modelled as a JSON document, with obligations, permissions, pre-conditions, and predicate constraints.
2. **A data file** — a flat JSON object mapping input names to boolean or integer values derived from the system under assessment.

It returns a rule-level compliance report: which obligations and permissions are active (pre-conditions satisfied), which are satisfied, which are violated, and which input values drove each outcome.

---

## Repository structure

```
evaluator/
  src/
    main/java/          Java/Spring Boot orchestration layer
    main/resources/
      policies/         CRA Annex I and ISO 27001 policy models (JSON)
      static/           Web UI pages (home-assistant.html, rule-editor.html)
      templates/        Server-rendered page (index.html)
    test/
      resources/test-cases/  31 black-box test cases (policy + data + expected)
  opa-policies/
    policies.rego       Generic Rego evaluation logic (OPA)
  docker-compose.yml    Starts OPA on port 8181
  Home Assistant Use Case/
    run.sh              Entry point — collects data and runs assessment
    collect.py          GitHub API collection runner
    config.env          Home Assistant project parameters
    collection-method-automated.json   25 automated + 4 manual heuristics (29 total)
    collection-method-manual.json      4 manually assessed inputs
    policy.json         CRA Annex I policy model (21 obligations)
    output/             Assessment data file (canonical run)
    index.html          Visual summary of the use case
```

---

## Quick start

### Prerequisites

- Java 21+
- Docker

`bash`, `curl`, `jq`, and `python3` are only needed for the Home Assistant use case script. The Maven wrapper (`./mvnw`) is included — no separate Maven installation required.

### 1. Start OPA

```bash
docker compose up -d
```

OPA starts on `http://localhost:8181`.

### 2. Start the engine

```bash
./mvnw spring-boot:run
```

### 3. Open the web interface

Open `http://localhost:8082` in your browser. Three pages are available:

| Page | URL | Purpose |
|---|---|---|
| General evaluator | `http://localhost:8082` | Upload any policy + data file and run an assessment |
| Home Assistant assessment | `http://localhost:8082/home-assistant.html` | CRA Annex I assessment of any GitHub repository |
| Rule editor | `http://localhost:8082/rule-editor.html` | Build and export policy models visually |

### 4. Submit an assessment via the REST API

```bash
curl -X POST http://localhost:8082/upload \
  -F "policyFile=@src/main/resources/policies/CRA.json" \
  -F "inputDataFile=@path/to/your/data.json"
```

### 5. Run the Home Assistant use case end-to-end

```bash
# Optional: set a GitHub token to avoid rate limiting (60 req/hour without)
export GITHUB_TOKEN=ghp_...

cd "Home Assistant Use Case"
./run.sh
```

The script collects all 29 inputs from GitHub's public API and, if OPA is running, prints the compliance verdict.

---

## Policy model format

Policies are JSON documents following the ODRL information model, extended with `preConditions` and `preConditionConstraints`. The `src/main/resources/policies/` directory contains two ready-to-use policies:

| File | Regulation | Rules |
|---|---|---|
| `CRA.json` | EU Cyber Resilience Act, Annex I | 21 obligations |
| `ISO27001.json` | ISO/IEC 27001:2022 (curated subset) | 18 obligations, 12 permissions |

---

## Running the test suite

```bash
./mvnw test
```

31 parameterised test cases run automatically. Each test case is a self-contained directory under `src/test/resources/test-cases/` containing `policy.json`, `data.json`, and `expected.json`.

---

## Home Assistant use case

The `Home Assistant Use Case/` folder is a self-contained template demonstrating a full CRA Annex I assessment of [Home Assistant Core](https://github.com/home-assistant/core) (version 2026.6.1). Open `index.html` in a browser for a visual summary of the results.

To adapt it for another GitHub-hosted project, update `config.env` (repository identifiers and file paths) and `collection-method-manual.json` (manually assessed inputs), then run `./run.sh`.

---

## Assessment result — Home Assistant Core 2026.6.1

| Verdict | Active rules | Satisfied | Violated |
|---|---|---|---|
| NON-COMPLIANT | 21 | 17 | 4 |

25 of 29 inputs (86%) were derived automatically from GitHub's public API without authentication.

---

## License

[GPL v3](LICENSE)
