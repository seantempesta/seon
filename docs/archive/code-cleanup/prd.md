# Code Cleanup: Duplicate Function Extraction

## Status: TODO

## Problem

The function `local-date->eod-instant` is duplicated in 3 files:
- `src/ml_options/data/ingest.clj` (lines 41-56)
- `src/ml_options/data/bulk_load.clj` (lines 25-32)
- `src/ml_options/data/thetadata.clj` (lines 83-98)

Additionally, `get-records-by-symbol` in `stats.clj` is unused (replaced by `get-records-by-symbol-list`).

## Solution

1. Create `src/ml_options/data/date_utils.clj` with:
   - `local-date->eod-instant`
   - `instant->local-date`
   - `weekend?`

2. Update the 3 files to require and use the shared function

3. Delete unused `get-records-by-symbol` from stats.clj

## Implementation Details

See `docs/research/cleanup-code-changes.md` for:
- Complete code for new namespace
- Exact line numbers to modify
- Required changes to requires

## Verification

After changes:

```bash
clj -M:test -m kaocha.runner
clj-nrepl-eval -p 7888 "(integrant.repl/reset)"

```

## Priority

Low - Code works fine, just has duplication. Can be done during next refactor session.
