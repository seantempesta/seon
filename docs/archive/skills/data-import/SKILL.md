---
type: reference
status: completed
tags: [reference, archive, database]
name: data-import
description: "Options data import and ThetaData API. Use when loading data, running bulk imports, editing ingest.clj or bulk_load.clj, working with ThetaData Terminal, or parsing OCC option symbols. Use when you see api/import endpoints, bulk-progress table, or need to check import status."
---

# Data Import Patterns

## Quick Start

```bash
# 1. Start ThetaData Terminal (required)
./bin/thetadata

# 2. Start the application
./bin/run

# 3. Start import
curl -X POST http://localhost:8080/api/import/start \
  -H "Content-Type: application/json" \
  -d '{"symbols": "AAPL", "startDate": "2024-01-01", "endDate": "2024-12-31"}'

# 4. Check status
curl http://localhost:8080/api/import/status | jq '.current.status'

# 5. Stop if needed
curl -X POST http://localhost:8080/api/import/stop

```

## OCC Symbol Format

```
AAPL  240119C00150000
│     │     │  │
│     │     │  └── Strike × 1000 (150.00)
│     │     └───── Call (C) or Put (P)
│     └─────────── Expiration YYMMDD
└───────────────── Ticker (padded to 6 chars)

```

## Key Behaviors

| Feature | Behavior |
|---------|----------|
| Weekends | Auto-skipped (~28% fewer API calls) |
| Resumable | Re-running skips completed dates |
| Progress | Tracked in `:bulk-progress` table |
| Failures | Logged but don't stop import |

## Check Progress

```clojure
;; Completed dates for a symbol
(require '[ml-options.data.ingestion-state :as state])
(state/get-completed-dates (user/xtdb-node) "AAPL")

;; Record counts by symbol
(ml-options.db.node/query (user/xtdb-node)
  '(-> (from :option-greeks [asset/ticker xt/id])
       (aggregate {:cnt (count xt/id)} asset/ticker)))

```

## Key Files

| File | Purpose |
|------|---------|
| `src/ml_options/data/ingest.clj` | Core ingestion |
| `src/ml_options/data/bulk_load.clj` | Bulk orchestration |
| `src/ml_options/data/thetadata.clj` | ThetaData API client |

## Common Issues

| Problem | Solution |
|---------|----------|
| No data returned | Check ThetaData Terminal: `curl localhost:25503/v2/list/roots` |
| Import stalled | Check status, logs, stop/restart if needed |
| Missing dates | Re-run import for that date range |

## For More Details

See `docs/reference/thetadata-v3-api.md` for API reference and `docs/reference/data-ingestion.md` for pipeline architecture.
