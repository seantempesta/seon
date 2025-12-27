# PRD: Polymarket Trader Analysis Tools

**Status:** In Progress
**Priority:** High
**Branch:** `feature/polymarket-analysis`

---

## Goals

1. **Download & Analyze** - Fetch complete trading history for Polymarket trader RN1 (made ~$2M via arbitrage)
2. **Understand Arbitrage Strategy** - Identify patterns in how RN1 profits from prediction markets
3. **Reusable Tooling** - Build general-purpose Polymarket API client for future analysis

---

## Problem Statement

A Polymarket trader (RN1) has made ~$2M in one year through apparent arbitrage strategies on sports betting markets. We want to:
- Download their complete trading history (13,338+ predictions)
- Analyze their strategy to understand how arbitrage works on prediction markets
- Build reusable tools for analyzing any Polymarket trader

**Impact:** Understanding successful arbitrage strategies for potential future trading tools.

---

## Target Trader: RN1

| Field | Value |
|-------|-------|
| Wallet | `0x2005d16a84ceefa912d4e380cd32e7ff827875ea` |
| Joined | Dec 2024 |
| Profit (past month) | $700k+ |
| Predictions | 13,338 |
| Biggest Win | $129k |
| Strategy | Sports betting arbitrage (EPL, NBA, etc.) |

---

## Polymarket Data API

Base URL: `https://data-api.polymarket.com`

| Endpoint | Purpose | Key Params |
|----------|---------|------------|
| `/activity` | All on-chain activity | `user`, `limit` (max 500), `offset`, `type`, `start`, `end` |
| `/trades` | Trade history | `user`, `limit`, `offset`, `side`, `market` |
| `/positions` | Current positions | `user`, `sizeThreshold`, `sortBy` |
| `/value` | Total position value | `user` |

No authentication required for read access. Pagination via `limit`/`offset`.

---

## Solution Design

### Architecture

```
src/seon/polymarket/
├── api.clj          ;; HTTP client for Polymarket Data API
├── analysis.clj     ;; Analysis functions (arbitrage, profitability, timing)
└── core.clj         ;; Public API, REPL helpers

data/polymarket/
└── rn1/
    ├── activity.edn     ;; Complete activity history
    └── positions.edn    ;; Current positions snapshot
```

### Key Patterns
- Follow `seon.trading.thetadata` HTTP client pattern (hato + cheshire)
- EDN files for research phase (simple, inspectable)
- REPL-driven development - all analysis verified interactively first

---

## Constraints

- Must use existing deps (hato, cheshire) - no new dependencies
- Must be REPL-friendly for exploratory analysis
- Must follow existing namespace patterns (`seon.trading.*` style)
- Tests must verify API responses and analysis functions
- Git checkpoint after each stage

---

## Staged Implementation

### Stage 1: API Client Foundation

**Goal:** Fetch data from Polymarket API with pagination support.

**Files to create:**
- `src/seon/polymarket/api.clj`

**Functions:**
```clojure
(fetch-activity wallet opts)    ;; Single page of activity
(fetch-trades wallet opts)      ;; Single page of trades
(fetch-positions wallet)        ;; Current positions
(fetch-value wallet)            ;; Total position value
```

**Verification:**
1. REPL test: `(fetch-activity "0x2005d16a84ceefa912d4e380cd32e7ff827875ea" {:limit 5})` returns data
2. REPL test: `(fetch-positions "0x2005d16a84ceefa912d4e380cd32e7ff827875ea")` returns positions
3. Unit test: Mock HTTP responses, verify parsing
4. Run tests: `clj -M:test -m kaocha.runner --focus seon.polymarket.api-test`

**Git checkpoint:** `git commit -m "Stage 1: Polymarket API client foundation"`

---

### Stage 2: Paginated Data Download

**Goal:** Download RN1's complete history (13k+ records) with pagination.

**Files to modify:**
- `src/seon/polymarket/api.clj` - add pagination functions

**Functions:**
```clojure
(fetch-all-activity wallet)     ;; Lazy seq, handles pagination
(fetch-all-trades wallet)       ;; Lazy seq of all trades
(save-activity! wallet path)    ;; Download and save to EDN
(load-activity path)            ;; Load from EDN
```

**Data files to create:**
- `data/polymarket/rn1/activity.edn`
- `data/polymarket/rn1/positions.edn`

**Verification:**
1. REPL test: `(count (fetch-all-activity wallet))` returns 13k+ records
2. REPL test: Data saved to EDN file, can be loaded back
3. Unit test: Pagination logic with mock responses
4. Verify: `(= (count (load-activity path)) (count (fetch-all-activity wallet)))`

**Git checkpoint:** `git commit -m "Stage 2: Paginated download and EDN storage"`

---

### Stage 3: Basic Analysis Functions

**Goal:** Aggregate and summarize trading data.

**Files to create:**
- `src/seon/polymarket/analysis.clj`

**Functions:**
```clojure
(summarize-activity data)       ;; {:total-trades N :total-redeems N ...}
(group-by-market data)          ;; Group activity by market
(group-by-type data)            ;; Group by activity type
(calculate-totals data)         ;; Total volume, profit, etc.
```

**Verification:**
1. REPL test: Summary stats match Polymarket profile page (~$700k profit)
2. Unit test: Known input produces expected output
3. Test: `(summarize-activity (load-activity path))` returns valid map

**Git checkpoint:** `git commit -m "Stage 3: Basic analysis functions"`

---

### Stage 4: Arbitrage Detection

**Goal:** Identify arbitrage patterns - betting both sides of same market.

**Files to modify:**
- `src/seon/polymarket/analysis.clj`

**Functions:**
```clojure
(find-opposing-positions data)  ;; Markets where user holds both YES and NO
(detect-arbitrage-trades data)  ;; Trades that look like arbitrage
(calculate-arbitrage-profit data) ;; Guaranteed profit from spreads
(arbitrage-summary data)        ;; High-level arbitrage stats
```

**Verification:**
1. REPL test: Find markets with opposing bets
2. REPL test: Calculate spread between opposing positions
3. Unit test: Mock data with known arbitrage, verify detection
4. Report: Generate summary of arbitrage findings

**Git checkpoint:** `git commit -m "Stage 4: Arbitrage detection"`

---

### Stage 5: Profitability Analysis

**Goal:** Understand which markets and strategies are most profitable.

**Functions:**
```clojure
(profit-by-market data)         ;; Profit breakdown by market
(profit-by-category data)       ;; Profit by category (sports, politics, etc.)
(win-rate data)                 ;; Success rate analysis
(biggest-wins data)             ;; Top N winning trades
(biggest-losses data)           ;; Top N losing trades
(roi-analysis data)             ;; Return on investment metrics
```

**Verification:**
1. REPL test: Top markets match visual inspection of profile
2. Unit test: ROI calculation correctness
3. Report: Generate profitability report

**Git checkpoint:** `git commit -m "Stage 5: Profitability analysis"`

---

### Stage 6: Trade Timing Analysis

**Goal:** Understand when and how frequently RN1 trades.

**Functions:**
```clojure
(trades-per-day data)           ;; Daily trade frequency
(trades-by-hour data)           ;; Hourly distribution
(holding-periods data)          ;; How long positions are held
(trade-velocity data)           ;; Speed of trading around events
(activity-heatmap data)         ;; Time-based activity visualization
```

**Verification:**
1. REPL test: Daily frequency makes sense (~50+ trades/day for 13k in ~8 months)
2. Unit test: Time parsing and grouping
3. Report: Trading patterns summary

**Git checkpoint:** `git commit -m "Stage 6: Trade timing analysis"`

---

### Stage 7: Public API & Documentation

**Goal:** Clean public API and REPL helpers.

**Files to create:**
- `src/seon/polymarket/core.clj`

**Functions:**
```clojure
(capabilities)                  ;; What this domain can do
(analyze-trader wallet)         ;; Full analysis for any trader
(download-trader wallet path)   ;; Download trader data
(load-trader path)              ;; Load saved data
```

**Verification:**
1. All tests pass: `clj -M:test -m kaocha.runner --focus :polymarket`
2. REPL demo: Full workflow works end-to-end
3. Documentation: Update CLAUDE.md with polymarket info

**Git checkpoint:** `git commit -m "Stage 7: Public API and documentation"`

---

## Success Criteria

1. **Data Downloaded** - Complete RN1 history (13k+ records) in EDN
2. **Arbitrage Identified** - Clear evidence of arbitrage strategy
3. **Metrics Calculated** - Profit, win rate, ROI, timing patterns
4. **Tests Pass** - All unit tests for API and analysis
5. **Reusable** - Can analyze any Polymarket trader with same tools

---

## Deliverables

- [ ] `src/seon/polymarket/api.clj` - HTTP client
- [ ] `src/seon/polymarket/analysis.clj` - Analysis functions
- [ ] `src/seon/polymarket/core.clj` - Public API
- [ ] `test/seon/polymarket/api_test.clj` - API tests
- [ ] `test/seon/polymarket/analysis_test.clj` - Analysis tests
- [ ] `data/polymarket/rn1/` - Downloaded data (git-ignored)
- [ ] Git commits for each stage

---

## Resources to Reference

| Resource | What's There |
|----------|--------------|
| `src/seon/trading/thetadata.clj` | HTTP client pattern (hato + cheshire) |
| `src/seon/trading/ingest.clj` | Data pipeline pattern |
| `src/seon/trading/signals.clj` | Analysis function pattern |
| [Polymarket Data API Docs](https://docs.polymarket.com/developers/CLOB/trades/trades-data-api) | API documentation |
| [Data API Gist](https://gist.github.com/shaunlebron/0dd3338f7dea06b8e9f8724981bb13bf) | Detailed endpoint docs |
