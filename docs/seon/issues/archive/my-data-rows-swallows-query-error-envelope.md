---
type: issue
status: resolved
severity: friction
tags: [issue, agent, database]
---

# my.data/rows swallows the query error envelope

## Problem

`src/my/data.cljs` `rows` never checked the query result for the error
envelope: `(vec <error-map>)` turned a failure into a vector of MapEntries
returned as an `ok? true` result, so an agent saw garbage rows instead of
the error value.

## Evidence

Found by the envelope conformance audit
([[../../../prds/source-cleanup/research/envelope-symbol-conformance-2026-07-20]]
§A). The other `my.*` surfaces check the envelope before shaping results.

## Resolution (2026-07-20)

`rows` now branches on `(:seon.error/message result)` before `vec` and
returns the sibling error shape `{:seon.result/ok? false :my.data/error
"rows query failed: …"}`; its response schema is the new
`:my.data/rows-response` with optional items/count/error, mirroring
`my.kb/recall-response`. The three reducers (`sum-by`/`max-by`/`group-sum`)
are pure over item maps and had no swallow site. Regression test
`my.data-test/rows-returns-the-error-value-when-the-query-fails` covers the
failure path (stubbed failing `db/query` → ok? false, message carried, no
items key). Focused ns: 7 tests / 24 assertions green; `bin/seon test
changed --path src/my/data.cljs`: 101 tests / 562 assertions green.
