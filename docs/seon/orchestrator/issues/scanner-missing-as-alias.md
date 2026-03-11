---
type: issue
status: open
tags: [issue, schema]
---
# Graph scanner doesn't handle :as-alias for :: keyword expansion

## Problem

`seon.graph.scanner/extract-aliases` only extracts `:as` aliases from `(:require ...)` clauses. If a file uses `:as-alias` (Clojure 1.11+) and then references `::alias/keyword`, the scanner's second-pass edamame auto-resolve won't find the alias. The keyword will expand incorrectly.

## Impact

Any namespace using `:as-alias` would have its `::alias/keyword` forms misresolved in the code graph. Currently may not affect any files if `:as-alias` isn't used, but it's a correctness gap.

## File Refs

- `src/seon/graph/scanner.clj` — `extract-aliases` line ~69-91, `build-auto-resolve`

## Severity

cleanup
