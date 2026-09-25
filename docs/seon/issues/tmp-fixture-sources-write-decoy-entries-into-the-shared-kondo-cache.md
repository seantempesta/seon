---
type: issue
status: open
severity: high
created: 2026-09-25
tags: [issue, analyzer, clj-kondo, cache, tests]
---

# tmp/ fixture sources write decoy entries into the shared kondo cache

## Evidence (2026-09-25, lane impl-world-injection-opus)

`.clj-kondo/.cache/v1/clj` holds 75 entries for fixture namespaces
(`long.probe*`, `reaching.probe*`, `seon.transact-feedback-probe`,
`seon.schema.predicate-owner-probe`), written 2026-09-24 and 2026-09-25. Each
entry's `:filename` is a captured-source mirror of a file under the checkout's
`tmp/` or a test fixture, e.g.
`tmp/analysis-mirror/analysis9127873836136919200/Users/sean/src/seon/tmp/reaching-source-69038855b486/probe.clj`
for `long.probe11dbdc9dcf17`. The namespaces are named in
`test/seon/test_reaching_test.clj` and `test/seon/test/one_request_test.clj`.

## Problem

`seon.fn.analyzer/analyze` (`src/seon/fn/analyzer.clj`, the `invoke-kondo`
call) lets only the checkout's own declared source use the shared cache
(`checkout-source?`). The comment there names a `tmp/` fixture root as the
same defect as the stdin decoy (2026-09-16). A mirrored `tmp/` file still
passes that check, so its namespace is written into the shared cache, which
clj-kondo keys by namespace name.

## Next

Find out why `checkout-source?` accepts these mirrored paths (the manifest
source roots that `roots-of` derives from `deps.edn` may include `.`). Fix the
producer, then delete the decoy entries once. Test it with one regression:
analyzing a `tmp/` fixture through `::sources` leaves no entry in the shared cache.
