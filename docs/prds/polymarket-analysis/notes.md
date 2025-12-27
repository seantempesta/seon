# Implementation Notes: Polymarket Analysis

**Last Updated:** 2025-12-27

---

## Overview

Tools for downloading and analyzing Polymarket trader data, focused on understanding RN1's arbitrage strategy.

---

## API Research Findings

### Polymarket Data API

- Base URL: `https://data-api.polymarket.com`
- No authentication required for read access
- Pagination: `limit` (max 500), `offset`
- Returns JSON

### Key Endpoints

```
GET /activity?user=<wallet>&limit=500&offset=0
GET /trades?user=<wallet>&limit=500&offset=0
GET /positions?user=<wallet>
GET /value?user=<wallet>
```

### RN1 Wallet Discovery

Found via browser network inspection on profile page:
- Username: RN1
- Wallet: `0x2005d16a84ceefa912d4e380cd32e7ff827875ea`

---

## Testing Notes

### REPL Testing (each stage)

```clojure
;; Stage 1: API basics
(require '[seon.polymarket.api :as api])
(api/fetch-activity "0x2005d16a84ceefa912d4e380cd32e7ff827875ea" {:limit 5})

;; Stage 2: Full download
(def wallet "0x2005d16a84ceefa912d4e380cd32e7ff827875ea")
(api/save-activity! wallet "data/polymarket/rn1/activity.edn")
(count (api/load-activity "data/polymarket/rn1/activity.edn"))

;; Stage 3+: Analysis
(require '[seon.polymarket.analysis :as analysis])
(def data (api/load-activity "data/polymarket/rn1/activity.edn"))
(analysis/summarize-activity data)
```

### Running Tests

```bash
clj -M:test -m kaocha.runner --focus :polymarket
```

---

## Gotchas

### Gotcha 1: API Pagination

**The problem:**
Polymarket API has max 500 limit per request, but no cursor-based pagination.

**How to handle:**
Use offset-based pagination, track total count from first response.

### Gotcha 2: Wallet Address Format

**The problem:**
Must use full 0x-prefixed lowercase address, not username.

**How to handle:**
Document RN1's wallet address in PRD, validate format in code.

---

## Future Improvements

1. **XTDB Integration** - Once patterns understood, migrate to proper database
2. **Multiple Trader Comparison** - Compare RN1 to other top traders
3. **Real-time Monitoring** - WebSocket feed for live analysis
4. **Strategy Replication** - Build tools to execute similar strategies
