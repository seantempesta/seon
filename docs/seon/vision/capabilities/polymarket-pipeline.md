---
type: capability
status: partial
tags: [vision, trading]
---
# Polymarket Data Pipeline

A domain test case: ingesting, aggregating, and analyzing prediction market data. The HTTP client and basic aggregation work. The pipeline proves the infrastructure handles real-world data ingestion but lacks the analysis layer and Malli contracts that would make it a proper Seon domain module.

## What Exists

- Full HTTP client with pagination for Polymarket API
- Basic aggregation: summarize, group, top-markets, daily volume
- 25 tests passing, 142MB real data cached

## Gaps

- No arbitrage or profitability detection
- No public API (`core.clj` entry point)
- No Malli schemas on functions (violates data contracts capability)
- Arbitrage hypothesis may be wrong (all positions are BUY-side)

## Related

- PRDs: [[prds/polymarket-analysis/prd]]
